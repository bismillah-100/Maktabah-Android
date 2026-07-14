package com.maktabah.ui.common

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.UnderlineSpan
import android.text.style.AlignmentSpan
import android.text.Layout
import java.text.Bidi
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import com.maktabah.models.Annotation
import com.maktabah.utils.HonorificReplacementResult
import com.maktabah.utils.convertToArabicDigits
import com.maktabah.utils.isArabicHarakat
import com.maktabah.utils.removingHarakat
import com.maktabah.utils.replacingHonorificPhrasesWithEvents

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
    private val tagRegex =
        Regex(
            """<span[^>]*data-type=(?:['"]?)title(?:['"]?)[^>]*>(.*?)</span>|<a\s[^>]*href="inr://[^"]*"[^>]*>(.*?)</a>|<hadeeth[^>]*>|<man[^>]*>(.*?)</man>""",
            RegexOption.DOT_MATCHES_ALL,
        )
    private val structRegex = Regex("""^\s*\([^)]+\)|^\s*\S+\s*-""", RegexOption.MULTILINE)
    private val separatorRegex = Regex("""_{3,}""")

    /**
     * Hapus HTML tags, kumpulkan [headerRanges] untuk span title. Mengembalikan (cleanText,
     * headerRanges) dalam UTF-16 units.
     */
    private fun String.stripSpanTagsWithRanges(): Pair<String, List<IntRange>> {
        if (!contains('<')) return this to emptyList()

        data class MatchInfo(
            val fullRange: IntRange,
            val inner: String,
            val isHeader: Boolean,
        )

        val allMatches =
            tagRegex
                .findAll(this)
                .map { m ->
                    val spanText = m.groups[1]?.value
                    val anchorText = m.groups[2]?.value
                    val manText = m.groups[3]?.value
                    val inner = spanText ?: anchorText ?: manText ?: ""
                    MatchInfo(m.range, inner, spanText != null)
                }.sortedBy { it.fullRange.first }

        if (allMatches.none()) return this to emptyList()

        val sb = StringBuilder()
        val headerRanges = mutableListOf<IntRange>()
        var cursor = 0

        for (m in allMatches) {
            sb.append(this, cursor, m.fullRange.first)
            val insertStart = sb.length
            sb.append(m.inner)
            if (m.isHeader && m.inner.isNotEmpty()) {
                headerRanges.add(insertStart until insertStart + m.inner.length)
            }
            cursor = m.fullRange.last + 1
        }
        sb.append(this, cursor, length)
        return sb.toString() to headerRanges
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
        separatorRegex.findAll(text).forEach { match ->
            colored.add(match.range)
            val afterSep = match.range.last + 1
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
        highlightColor: Int = "#8B0000".toColorInt(),
        footnoteColor: Int? = null,
        showHarakat: Boolean = true,
        annotations: List<Annotation> = emptyList(),
        searchQuery: String? = null,
        isImported: Boolean = true,
        isMultiLanguage: Boolean = false,
        typeface: Typeface? = null,
        lateefTypeface: Typeface? = null,
    ): SpannableStringBuilder {
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
            val queryHarakat = searchQuery.filter { it.code != 0x0640 }
            val cleanQuery = queryHarakat.filter { !it.isArabicHarakat() }

            if (cleanQuery.isNotEmpty()) {
                val searchResult = honorific // honorific contains the final rendered text
                val renderedStr = searchResult.text

                // We use a simplified version of findQueryRange logic here to find ALL occurrences
                val cleanToOrig = mutableListOf<Int>()
                val cleanText = StringBuilder()

                for (i in renderedStr.indices) {
                    val char = renderedStr[i]
                    val expansion =
                        com.maktabah.utils.HONORIFIC_PHRASES.find { it.second == char.toString() }?.first
                    if (expansion != null) {
                        for (c in expansion) {
                            if (!c.isArabicHarakat() && c.code != 0x0640) {
                                cleanToOrig.add(i)
                                val v = c.code
                                if (v == 0x0623 || v == 0x0625 || v == 0x0622 || v == 0x0671) {
                                    cleanText.append('\u0627')
                                } else {
                                    cleanText.append(c)
                                }
                            }
                        }
                    } else if (!char.isArabicHarakat() && char.code != 0x0640) {
                        cleanToOrig.add(i)
                        val v = char.code
                        if (v == 0x0623 || v == 0x0625 || v == 0x0622 || v == 0x0671) {
                            cleanText.append('\u0627')
                        } else {
                            cleanText.append(char)
                        }
                    }
                }

                val normalizedQuery = StringBuilder()
                for (char in cleanQuery) {
                    val v = char.code
                    if (v == 0x0623 || v == 0x0625 || v == 0x0622 || v == 0x0671) {
                        normalizedQuery.append('\u0627')
                    } else {
                        normalizedQuery.append(char)
                    }
                }

                val qStr = normalizedQuery.toString()
                val tStr = cleanText.toString()
                var startSearch = 0
                while (true) {
                    val idx = tStr.indexOf(qStr, startSearch, ignoreCase = true)
                    if (idx == -1) break

                    val start = cleanToOrig[idx]
                    val end = if (idx + qStr.length < cleanToOrig.size) {
                        cleanToOrig[idx + qStr.length]
                    } else {
                        renderedStr.length
                    }

                    if (start < end) {
                        val searchQueryColor = android.graphics.Color.argb(64, 255, 213, 79)
                        spannable.setSpan(
                            BackgroundColorSpan(searchQueryColor),
                            start,
                            end,
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    startSearch = idx + 1
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
