package com.maktabah.ui.common

import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import com.maktabah.models.Annotation
import com.maktabah.utils.HonorificReplacementResult
import com.maktabah.utils.convertToArabicDigits
import com.maktabah.utils.findArabicMatchingRanges
import com.maktabah.utils.findArabicMatchingRangesWithIndex
import com.maktabah.utils.filterRangesForNearMode
import com.maktabah.utils.isArabicHarakat
import com.maktabah.utils.normalizeArabic
import com.maktabah.utils.removingHarakat
import com.maktabah.utils.replacingHonorificPhrasesWithEvents
import java.text.Bidi

class AnnotationSpan(val annotation: Annotation)

/**
 * Data pipeline (mengikuti iOS ArabicTextRenderer):
 * 1. convertToArabicDigits
 * 2. removingHarakat (jika !showHarakat)
 * 3. stripSpanTagsWithRanges → headerRanges
 * 4. cleanedTextWithRanges → coloredRanges (simbol), track offset
 * 5. replacingHonorificPhrasesWithEvents → remap semua ranges
 * 6. Build SpannableStringBuilder, apply spans
 * 7. Apply anotasi dengan remapped ranges
 */
object ArabicTextRenderer {

    private data class CacheKey(
        val bookId: Int,
        val contentId: Int,
        val showHarakat: Boolean,
        val isMultiLanguage: Boolean
    )

    private data class CachedParsedData(
        val honorific: HonorificReplacementResult,
        val finalHeaderRanges: List<IntRange>,
        val finalColoredRanges: List<IntRange>,
        val finalFootnoteRanges: List<IntRange>
    )

    private val parsedCache = LruCache<CacheKey, CachedParsedData>(100)

    private val structRegex = Regex("""^\s*\([^)]+\)|^\s*\S+\s*-""", RegexOption.MULTILINE)
    private val separatorRegex = Regex("""^[ \t]*(_{3,})[^\p{L}\r\n]*$""", RegexOption.MULTILINE)

    private enum class SpanTagKind {
        HEADER,
        ANCHOR_B,
        OTHER,
    }

    private data class ParsedTag(
        val tagName: String,
        val attributes: String,
        val isClosing: Boolean,
        val isSelfClosing: Boolean,
    ) {
        val isSpan: Boolean
            get() = tagName.equals("span", ignoreCase = true)
    }

    private fun spanKind(attributes: String): SpanTagKind {
        if (attributes.isEmpty()) return SpanTagKind.OTHER
        val lower = attributes.lowercase()
        if (lower.contains("data-type=\"title\"") ||
            lower.contains("data-type='title'") ||
            lower.contains("data-type=title") ||
            lower.contains("id=\"aya-") ||
            lower.contains("id='aya-") ||
            lower.contains("id=aya-")
        ) {
            return SpanTagKind.HEADER
        }
        if (lower.contains("id=\"b-") ||
            lower.contains("id='b-") ||
            lower.contains("id=b-")
        ) {
            return SpanTagKind.ANCHOR_B
        }
        return SpanTagKind.OTHER
    }

    private fun parseTagDetails(tagContent: String): ParsedTag {
        val trimmed = tagContent.trim { it.isWhitespace() }
        val isClosing = trimmed.startsWith('/')
        val isSelfClosing = !isClosing && trimmed.endsWith('/')

        val body =
            when {
                isClosing -> trimmed.drop(1).trim { it.isWhitespace() }
                isSelfClosing -> trimmed.dropLast(1).trim { it.isWhitespace() }
                else -> trimmed
            }

        var firstDelim = body.length
        for (i in body.indices) {
            val ch = body[i]
            if (ch.isWhitespace() || ch == '/') {
                firstDelim = i
                break
            }
        }

        return ParsedTag(
            tagName = body.substring(0, firstDelim),
            attributes = body.substring(firstDelim),
            isClosing = isClosing,
            isSelfClosing = isSelfClosing,
        )
    }

    private class SpanTagScanner(capacity: Int) {
        private val sb = StringBuilder(capacity)
        private val headerRanges = mutableListOf<IntRange>()
        private var headerDepth = 0
        private var currentHeaderStart: Int? = null
        private var skipInnerDepth = 0

        fun processSpanTag(
            attributes: String,
            isClosing: Boolean,
            isSelfClosing: Boolean,
        ) {
            if (isClosing) {
                handleClosingSpan()
            } else if (!isSelfClosing) {
                handleOpeningSpan(attributes)
            }
        }

        private fun handleClosingSpan() {
            if (skipInnerDepth > 0) {
                skipInnerDepth--
            } else if (headerDepth > 0) {
                headerDepth--
                if (headerDepth == 0) {
                    val start = currentHeaderStart
                    if (start != null && sb.length > start) {
                        headerRanges.add(start until sb.length)
                    }
                    currentHeaderStart = null
                }
            }
        }

        private fun handleOpeningSpan(attributes: String) {
            when (spanKind(attributes)) {
                SpanTagKind.HEADER -> {
                    headerDepth++
                    if (headerDepth == 1) {
                        currentHeaderStart = sb.length
                    }
                }

                SpanTagKind.ANCHOR_B -> {
                    skipInnerDepth++
                }

                SpanTagKind.OTHER -> Unit
            }
        }

        fun appendChunk(
            source: String,
            start: Int,
            end: Int,
        ) {
            if (start >= end) return

            if (skipInnerDepth > 0) {
                var newlineIdx = -1
                for (i in start until end) {
                    val ch = source[i]
                    if (ch == '\n' || ch == '\r' || ch == '\u0085' || ch == '\u2028' || ch == '\u2029') {
                        newlineIdx = i
                        break
                    }
                }
                if (newlineIdx == -1) return
                skipInnerDepth = 0
                sb.append(source, newlineIdx, end)
                return
            }

            sb.append(source, start, end)
        }

        fun finalizeResult(): Pair<String, List<IntRange>> {
            if (headerDepth > 0) {
                val start = currentHeaderStart
                if (start != null && sb.length > start) {
                    headerRanges.add(start until sb.length)
                }
            }
            return sb.toString() to headerRanges
        }
    }

    /**
     * Hapus HTML tags dengan single-pass scanner, kumpulkan [headerRanges] untuk span title/aya.
     * Mengembalikan (cleanText, headerRanges) dalam UTF-16 units.
     */
    internal fun String.stripSpanTagsWithRanges(): Pair<String, List<IntRange>> {
        var ltIndex = indexOf('<')
        if (ltIndex == -1) return this to emptyList()

        val scanner = SpanTagScanner(length)
        var textStart = 0

        while (true) {
            if (textStart < ltIndex) {
                scanner.appendChunk(this, textStart, ltIndex)
            }

            val afterLt = ltIndex + 1
            val tagEnd = indexOf('>', afterLt)
            if (tagEnd == -1) {
                scanner.appendChunk(this, ltIndex, length)
                break
            }

            val tag = parseTagDetails(substring(afterLt, tagEnd))
            if (tag.isSpan) {
                scanner.processSpanTag(
                    attributes = tag.attributes,
                    isClosing = tag.isClosing,
                    isSelfClosing = tag.isSelfClosing,
                )
            }

            textStart = tagEnd + 1
            val nextLt = indexOf('<', textStart)
            if (nextLt == -1) {
                if (textStart < length) {
                    scanner.appendChunk(this, textStart, length)
                }
                break
            }
            ltIndex = nextLt
        }

        return scanner.finalizeResult()
    }

    /**
     * Bersihkan \n, ¬, §, {} dan kumpulkan [coloredRanges] untuk simbol. Mengembalikan (cleanText,
     * coloredRanges, List<DeltaEvent>).
     */
    private data class DeltaEvent(
        val oldOffset: Int,
        val delta: Int,
    )

    private data class CleanedResult(
        val text: String,
        val coloredRanges: List<IntRange>,
        val footnoteRanges: List<IntRange>,
        val events: List<DeltaEvent>,
    )

    private fun String.cleanedTextWithRanges(isMultiLanguage: Boolean): CleanedResult {
        val sb = StringBuilder(length)
        val colored = mutableListOf<IntRange>()
        val events = mutableListOf<DeltaEvent>()
        var oldOffset = 0
        var currentDelta = 0
        val removable = setOf('¬', '§')
        var i = 0
        var isCurrentParagraphLtr = false
        var isAtParagraphStart = true

        while (i < length) {
            val ch = this[i]
            val charLen = 1

            if (isMultiLanguage && isAtParagraphStart) {
                isCurrentParagraphLtr = this.isParagraphLtr(i)
                isAtParagraphStart = false
            }

            if (ch in removable) {
                val nextDelta = currentDelta - charLen
                events.add(DeltaEvent(oldOffset + charLen, nextDelta))
                currentDelta = nextDelta
                i++
                oldOffset += charLen
                continue
            }

            if (ch == '\\' && i + 1 < length && this[i + 1] == 'n') {
                sb.append('\n')
                val nextDelta = currentDelta - 1
                events.add(DeltaEvent(oldOffset + 2, nextDelta))
                currentDelta = nextDelta
                i += 2
                oldOffset += 2
                isAtParagraphStart = true
                continue
            }

            if (ch == '\r') {
                if (i + 1 < length && this[i + 1] == '\n') {
                    val nextDelta = currentDelta - 1
                    events.add(DeltaEvent(oldOffset + 1, nextDelta))
                    currentDelta = nextDelta
                    i++
                    oldOffset++
                    isAtParagraphStart = true
                    continue
                } else {
                    sb.append('\n')
                    i++
                    oldOffset++
                    isAtParagraphStart = true
                    continue
                }
            }

            when (ch) {
                '{' -> {
                    val r = if (isCurrentParagraphLtr) " ﴾" else " ﴿"
                    val s = sb.length
                    sb.append(r)
                    colored.add(s until s + r.length)
                    val nextDelta = currentDelta + (r.length - charLen)
                    events.add(DeltaEvent(oldOffset + charLen, nextDelta))
                    currentDelta = nextDelta
                }

                '}' -> {
                    val r = if (isCurrentParagraphLtr) "﴿ " else "﴾ "
                    val s = sb.length
                    sb.append(r)
                    colored.add(s until s + r.length)
                    val nextDelta = currentDelta + (r.length - charLen)
                    events.add(DeltaEvent(oldOffset + charLen, nextDelta))
                    currentDelta = nextDelta
                }

                '(', ')', '[', ']', '«', '»', '.', '،', ',', ':', '!', '/', '؟', '?', '"', ';', '؛', '|' -> {
                    val s = sb.length
                    sb.append(ch)
                    colored.add(s until s + 1)
                }

                else -> {
                    sb.append(ch)
                }
            }
            if (ch == '\n') {
                isAtParagraphStart = true
            }
            i++
            oldOffset += charLen
        }

        // Structural: (…) di awal baris dan token - di awal baris
        val text = sb.toString()
        structRegex.findAll(text).forEach { colored.add(it.range) }

        val footnoteRanges = mutableListOf<IntRange>()
        val matches = separatorRegex.findAll(text).toList()

        matches.forEach { match ->
            val underscoreGroup = match.groups[1]
            if (underscoreGroup != null) {
                colored.add(underscoreGroup.range)
            } else {
                colored.add(match.range)
            }
        }

        if (matches.isNotEmpty()) {
            val lastMatch = matches.last()
            val afterSep = lastMatch.range.last + 1
            if (afterSep < text.length) {
                footnoteRanges.add(afterSep until text.length)
            }
        }

        return CleanedResult(text, colored, footnoteRanges, events)
    }

    /** Remap sebuah range menggunakan daftar DeltaEvent dari cleanedTextWithRanges. */
    private fun remapCleanedRange(
        start: Int,
        length: Int,
        events: List<DeltaEvent>,
    ): Pair<Int, Int> {
        fun remap(offset: Int): Int {
            var delta = 0
            for (e in events) {
                if (offset >= e.oldOffset) delta = e.delta else break
            }
            return offset + delta
        }

        val newStart = remap(start)
        val newEnd = remap(start + length)
        return newStart to maxOf(0, newEnd - newStart)
    }

    fun render(
        text: String,
        bookId: Int = -1,
        contentId: Int = -1,
        highlightColor: Int = "#8B0000".toColorInt(),
        footnoteColor: Int? = null,
        showHarakat: Boolean = true,
        annotations: List<Annotation> = emptyList(),
        searchQuery: String? = null,
        searchMode: Int = 0,
        nearDistance: Int = 10,
        isImported: Boolean = true,
        isMultiLanguage: Boolean = false,
        typeface: Typeface? = null,
        lateefTypeface: Typeface? = null,
    ): SpannableStringBuilder {
        val cacheKey = if (bookId != -1 && contentId != -1) CacheKey(bookId, contentId, showHarakat, isMultiLanguage) else null
        var cachedData = cacheKey?.let { parsedCache.get(it) }

        if (cachedData == null) {
            // 1. Digits + harakat
            val step1 =
                text.convertToArabicDigits().let { if (showHarakat) it else it.removingHarakat() }

            // 2. Strip HTML tags, kumpulkan header ranges
            val (step2, headerRanges) = if (isImported) step1.stripSpanTagsWithRanges() else step1 to emptyList()

            // 3. Clean \n / ¬ / § / {}, kumpulkan symbol ranges
            val cleanedResult = step2.cleanedTextWithRanges(isMultiLanguage)
            val step3 = cleanedResult.text
            val coloredRanges = cleanedResult.coloredRanges
            val footnoteRanges = cleanedResult.footnoteRanges
            val cleanEvents = cleanedResult.events

            // 4. Honorific replacement dengan event tracking
            val honorific = step3.replacingHonorificPhrasesWithEvents()

            // 5. Remap header ranges: step2 → step3 (cleanEvents) → final (honorific)
            val finalHeaderRanges =
                headerRanges.map { r ->
                    val (s, l) = remapCleanedRange(r.first, r.last - r.first + 1, cleanEvents)
                    val (hs, hl) = honorific.remapDisplayedRange(s, l)
                    hs until hs + hl
                }

            // 6. coloredRanges sudah dalam step3 space (direkam saat membangun sb),
            // jadi hanya perlu remap honorific saja (bukan cleanEvents).
            // Tambah juga range glyph honorific agar diwarnai seperti iOS.
            val finalColoredRanges: List<IntRange> =
                buildList {
                    for (r in coloredRanges) {
                        val (hs, hl) = honorific.remapDisplayedRange(r.first, r.last - r.first + 1)
                        add(hs until hs + hl)
                    }
                    addAll(honorific.replacementDisplayRanges)
                }

            val finalFootnoteRanges: List<IntRange> =
                footnoteRanges.map { r ->
                    val (hs, hl) = honorific.remapDisplayedRange(r.first, r.last - r.first + 1)
                    hs until hs + hl
                }

            cachedData = CachedParsedData(
                honorific = honorific,
                finalHeaderRanges = finalHeaderRanges,
                finalColoredRanges = finalColoredRanges,
                finalFootnoteRanges = finalFootnoteRanges
            )

            if (cacheKey != null) {
                parsedCache.put(cacheKey, cachedData)
            }
        }

        val honorific = cachedData.honorific
        val finalHeaderRanges = cachedData.finalHeaderRanges
        val finalColoredRanges = cachedData.finalColoredRanges
        val finalFootnoteRanges = cachedData.finalFootnoteRanges

        val spannable = SpannableStringBuilder(honorific.text)

        // 7. Fallback font for honorifics if missing in current typeface
        if (lateefTypeface != null && honorific.replacementDisplayRanges.isNotEmpty()) {
            val paint = Paint()
            typeface?.let { paint.typeface = it }
            for (range in honorific.replacementDisplayRanges) {
                if (range.first >= 0 && range.last < spannable.length) {
                    val glyph = honorific.text.substring(range.first, range.last + 1)
                    if (!paint.hasGlyph(glyph)) {
                        spannable.setSpan(
                            CustomTypefaceSpan(lateefTypeface),
                            range.first,
                            range.last + 1,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        }

        // 7. Apply Search Query Highlights (All occurrences)
        if (!searchQuery.isNullOrBlank()) {
            val searchResult = honorific // honorific contains the final rendered text
            val renderedStr = searchResult.text
            val searchTerms = searchQuery
                .replace("،", ",")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.replacingHonorificPhrasesWithEvents().text }

            if (searchTerms.isNotEmpty()) {
                val colors = listOf(
                    android.graphics.Color.argb(64, 255, 213, 79), // Yellow
                    android.graphics.Color.argb(102, 255, 0, 255), // Magenta
                    android.graphics.Color.argb(102, 255, 105, 180), // Pink
                    android.graphics.Color.argb(102, 128, 0, 128), // Purple
                    android.graphics.Color.argb(102, 75, 0, 130)  // Indigo
                )

                if (searchMode == 3 && searchTerms.size > 1) { // 3 is NEAR mode
                    val rangesWithIndex = renderedStr.findArabicMatchingRangesWithIndex(searchTerms)
                    val filteredRanges = renderedStr.filterRangesForNearMode(rangesWithIndex, searchTerms.size, nearDistance)
                    for ((i, r) in filteredRanges.withIndex()) {
                        val color = colors[i % colors.size]
                        if (r.first >= 0 && r.last < spannable.length && r.first <= r.last) {
                            spannable.setSpan(
                                BackgroundColorSpan(color),
                                r.first,
                                r.last + 1,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                } else {
                    for ((index, term) in searchTerms.withIndex()) {
                        val ranges = renderedStr.findArabicMatchingRanges(listOf(term))
                        val color = colors[index % colors.size]

                        for (r in ranges) {
                            if (r.first >= 0 && r.last < spannable.length && r.first <= r.last) {
                                spannable.setSpan(
                                    BackgroundColorSpan(color),
                                    r.first,
                                    r.last + 1,
                                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            }
                        }
                    }
                }
            }
        }


        // Apply footnote spans first so they act as a base style for the footnote area
        for (r in finalFootnoteRanges) {
            if (r.first >= 0 && r.last < spannable.length && r.first < r.last) {
                footnoteColor?.let {
                    spannable.setSpan(
                        ForegroundColorSpan(it),
                        r.first,
                        r.last + 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                spannable.setSpan(
                    RelativeSizeSpan(0.85f),
                    r.first,
                    r.last + 1,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        // Apply header color spans
        for (r in finalHeaderRanges) {
            if (r.first >= 0 && r.last < spannable.length) {
                spannable.setSpan(
                    ForegroundColorSpan(highlightColor),
                    r.first,
                    r.last + 1,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        // Apply symbol/structural color spans
        for (r in finalColoredRanges) {
            if (r.first >= 0 && r.last < spannable.length) {
                spannable.setSpan(
                    ForegroundColorSpan(highlightColor),
                    r.first,
                    r.last + 1,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        // 7. Apply anotasi dengan remappedRanges
        // Ranges disimpan di sourceText (step3/pre-honorific) space.
        // Hanya butuh remap melalui honorific events (bukan cleanEvents).
        for (ann in annotations) {
            val srcStart = if (showHarakat) ann.rangeDiacLocation else ann.rangeLocation
            val srcLen = if (showHarakat) ann.rangeDiacLength else ann.rangeLength

            val (hs, hl) = honorific.remapDisplayedRange(srcStart, srcLen)
            val end = hs + hl

            if (hs < 0 || end > spannable.length || hl == 0) continue

            spannable.setSpan(
                AnnotationSpan(ann),
                hs,
                end,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )

            when (ann.type) {
                0 -> { // HIGHLIGHT
                    val baseColor =
                        try {
                            ann.colorHex.toColorInt()
                        } catch (_: Exception) {
                            android.graphics.Color.YELLOW
                        }
                    val color40 = ColorUtils.setAlphaComponent(baseColor, (0.4f * 255).toInt())
                    spannable.setSpan(
                        BackgroundColorSpan(color40),
                        hs,
                        end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                1 -> { // UNDERLINE
                    spannable.setSpan(
                        UnderlineSpan(),
                        hs,
                        end,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }

        // 8. MultiLanguage Paragraph Alignment
        if (isMultiLanguage) {
            val str = spannable.toString()
            var pStart = 0
            while (pStart < str.length) {
                var pEnd = str.indexOf('\n', pStart)
                if (pEnd == -1) pEnd = str.length

                if (pStart < pEnd) {
                    val paragraph = str.substring(pStart, pEnd)
                    val bidi = Bidi(paragraph, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT)
                    if (bidi.baseLevel == 0) { // 0 means LTR
                        spannable.setSpan(
                            AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL),
                            pStart,
                            pEnd,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }

                pStart = pEnd + 1
            }
        }

        return spannable
    }

    /**
     * Jalankan pipeline teks tanpa membuat spannable. Digunakan oleh calculateBothRanges saat
     * menyimpan anotasi. Mengembalikan HonorificReplacementResult yang sourceText-nya adalah step3
     * (pre-honorific, post-cleaning) — sama dengan iOS ArabicRenderResult.sourceText.
     */
    fun processTextWithResult(
        rawNass: String,
        showHarakat: Boolean,
    ): HonorificReplacementResult {
        val step1 =
            rawNass.convertToArabicDigits().let {
                if (showHarakat) it else it.removingHarakat()
            }
        val (step2, _) = step1.stripSpanTagsWithRanges()
        val (step3, _, _) = step2.cleanedTextWithRanges(isMultiLanguage = false)
        return step3.replacingHonorificPhrasesWithEvents()
    }

    private fun String.isParagraphLtr(start: Int): Boolean {
        for (i in start until length) {
            val ch = this[i]
            if (ch == '\n' || ch == '\r') break
            if (ch.isLetter()) {
                if (ch in 'a'..'z' || ch in 'A'..'Z') {
                    return true
                }
                if (ch.code in 0x0600..0x06FF) {
                    return false
                }
            }
        }
        return false
    }

    private class CustomTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(ds: TextPaint) {
            applyCustomTypeface(ds, typeface)
        }

        override fun updateMeasureState(paint: TextPaint) {
            applyCustomTypeface(paint, typeface)
        }

        private fun applyCustomTypeface(paint: Paint, tf: Typeface) {
            paint.typeface = tf
        }
    }
}
