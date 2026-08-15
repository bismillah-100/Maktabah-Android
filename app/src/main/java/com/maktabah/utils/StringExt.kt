package com.maktabah.utils

val arabicDigits = listOf("٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩")

private val SPAN_REGEX = Regex("<[^>]*>")
private val WHITESPACE_REGEX = Regex("\\s+")
private val PUNCT_REGEX = Regex("[\\s\\p{Punct}]+")

fun Char.isArabicHarakat(): Boolean {
    val v = this.code
    return v in 0x0610..0x061A ||
        v in 0x064B..0x065F ||
        v == 0x0670 ||
        v in 0x06D6..0x06DC ||
        v in 0x06DF..0x06E8 ||
        v in 0x06EA..0x06ED ||
        v == 0x08D4 ||
        v in 0x08D6..0x08E1 ||
        v in 0x08E3..0x08FF
}

fun String.normalizeArabic(removeDiacritics: Boolean = true): String {
    val sb = StringBuilder(this.length)
    for (char in this) {
        val v = char.code

        if (removeDiacritics && char.isArabicHarakat()) continue
        if (v == 0x0640) continue // Tatweel

        when (v) {
            0x0623, 0x0625, 0x0622, 0x0671 -> {
                sb.append('\u0627') // Alif
            }
            0x0629 -> {
                sb.append('\u0647')
            }
            0x0649 -> {
                sb.append('\u064A')
            }
            0x060C -> {
                sb.append(',') // Arabic comma to English comma
            }
            else -> {
                sb.append(char)
            }
        }
    }
    return sb.toString()
}

fun String.removingHarakat(): String = this.filter { !it.isArabicHarakat() }

/**
 * Checks whether the first non-whitespace character in this string is an Arabic character.
 */
fun String.startsWithArabic(): Boolean {
    val firstChar = this.firstOrNull { !it.isWhitespace() } ?: return false
    val code = firstChar.code
    return code in 0x0600..0x06FF ||
        code in 0x0750..0x077F ||
        code in 0x08A0..0x08FF ||
        code in 0xFB50..0xFDFF ||
        code in 0xFE70..0xFEFF
}

fun String.convertToArabicDigits(): String {
    val builder = java.lang.StringBuilder(this.length)
    for (char in this) {
        if (char in '0'..'9') {
            builder.append(arabicDigits[char - '0'])
        } else {
            builder.append(char)
        }
    }
    return builder.toString()
}

fun String.stripSpanTags(): String = replace(SPAN_REGEX, "")

// ─── Honorific replacement dengan range tracking ───────────────────────────


val HONORIFIC_PHRASES =
    listOf(
        "صلى الله عليه وسلم" to "\uFDFA",
        "رحمهم الله" to "\uFD4F",
        "رحمه الله" to "\uFD40",
        "رضي الله عنهما" to "\uFD44",
        "رضي الله عنهم" to "\uFD43",
        "رضي الله عنها" to "\uFD42",
        "رضي الله عنه" to "\uFD41",
        "سبحانه وتعالى" to "\uFDFE",
        "تبارك وتعالى" to "\uFD4E",
        "عليهم السلام" to "\uFD48",
        "عليها السلام" to "\uFD4D",
        "عليه السلام" to "\uFD47",
        "عز وجل" to "\uFDFF",
    )

/**
 * Buat versi teks tanpa harakat, sekaligus simpan mapping offset
 * dari posisi normalized → posisi di teks asli (UTF-16 units).
 */
private fun String.normalizedHonorificSearchText(): Pair<String, IntArray> {
    val sb = StringBuilder(length)
    // normalizedToOriginal[i] = offset UTF-16 di string asli untuk karakter normalized ke-i
    val offsets = ArrayList<Int>(length)
    var originalOffset = 0
    for (ch in this) {
        val charLen = ch.toString().length // selalu 1 untuk BMP; surrogate pairs = 2
        if (!ch.isArabicHarakat()) {
            offsets.add(originalOffset)
            sb.append(ch)
        }
        originalOffset += charLen
    }
    offsets.add(originalOffset) // sentinel untuk end-of-string
    return sb.toString() to offsets.toIntArray()
}

fun String.replacingHonorificPhrasesWithEvents(): HonorificReplacementResult {
    val (normalized, offsets) = normalizedHonorificSearchText()

    data class Match(
        val normalizedStart: Int, // idx di normalized string
        val normalizedEnd: Int, // idx di normalized string (exclusive)
        val originalStart: Int, // offset di teks asli
        val originalEnd: Int,
        val glyph: String,
    )

    val matches = mutableListOf<Match>()
    var searchStart = 0

    while (searchStart < normalized.length) {
        var best: Match? = null
        for ((phrase, glyph) in HONORIFIC_PHRASES) {
            val idx = normalized.indexOf(phrase, searchStart)
            if (idx < 0) continue
            if (best == null || idx < best.normalizedStart) {
                best =
                    Match(
                        normalizedStart = idx,
                        normalizedEnd = idx + phrase.length,
                        originalStart = offsets[idx],
                        originalEnd = offsets[idx + phrase.length],
                        glyph = glyph,
                    )
            }
        }
        if (best == null) break
        matches.add(best)
        searchStart = best.normalizedEnd // lanjut setelah frasa yang di-match
    }

    if (matches.isEmpty()) return HonorificReplacementResult(this, this, emptyList())

    val finalText = StringBuilder(length)
    val events = mutableListOf<HonorificReplacementEvent>()
    var cursor = 0
    val nsThis = this

    for (m in matches) {
        if (cursor < m.originalStart) finalText.append(nsThis, cursor, m.originalStart)
        finalText.append(m.glyph)
        events.add(HonorificReplacementEvent(m.originalStart, m.originalEnd, m.glyph.length))
        cursor = m.originalEnd
    }
    if (cursor < nsThis.length) finalText.append(nsThis, cursor, nsThis.length)

    return HonorificReplacementResult(this, finalText.toString(), events)
}

// ─── Range diacritics ↔ plain ─────────────────────────────────────────────

/**
 * Hitung range equivalen di teks *tanpa* harakat,
 * dari [com.maktabah.ui.annotation.AnnotationCoordinator.calculateBothRanges]
 * di [sourceTextWithHarakat].
 *
 * Mengembalikan (start, length) dalam unit UTF-16.
 */
fun calculateRangeWithoutHarakat(
    sourceStart: Int,
    sourceLength: Int,
    sourceTextWithHarakat: String,
): Pair<Int, Int> {
    var startOffset = 0
    for (i in 0 until sourceStart) {
        if (!sourceTextWithHarakat[i].isArabicHarakat()) startOffset++
    }
    var selLength = 0
    for (i in sourceStart until sourceStart + sourceLength) {
        if (!sourceTextWithHarakat[i].isArabicHarakat()) selLength++
    }
    return startOffset to selLength
}

/**
 * Dari teks *dengan* harakat (this), temukan range yang cocok
 * dengan [selectedText] (tanpa harakat) mendekati [approxStart].
 *
 * [approxStart] berada dalam koordinat NO-HARAKAT (sourceText yang sudah di-strip),
 * sedangkan [this] adalah teks WITH-HARAKAT (nash asli).
 *
 * Solusi: bangun versi no-harakat dari this + offsetMap ke posisi asli,
 * cari di no-harakat space (koordinat cocok dengan approxStart) dalam radius tertentu,
 * lalu gunakan offsetMap untuk menghasilkan range di with-harakat string.
 *
 * Mengembalikan (start, length) di teks dengan harakat.
 */
fun String.findRangeInOriginal(
    selectedText: String,
    approxStart: Int,
): Pair<Int, Int> {
    val cleanSelected = selectedText.normalizeArabic()
    if (cleanSelected.isEmpty()) return approxStart to selectedText.length

    // Bangun versi no-harakat dari this + offsetMap: noHarakatIdx → withHarakatIdx
    val noHarakatSb = StringBuilder(length)
    val offsetMap = ArrayList<Int>(length + 1)

    for (i in indices) {
        val ch = this[i]
        if (!ch.isArabicHarakat() && ch.code != 0x0640) {
            offsetMap.add(i)
            val normalizedChar = when (ch.code) {
                0x0623, 0x0625, 0x0622, 0x0671 -> '\u0627'
                0x0629 -> '\u0647'
                0x0649 -> '\u064A'
                0x060C -> ','
                else -> ch
            }
            noHarakatSb.append(normalizedChar)
        }
    }
    offsetMap.add(length) // sentinel

    // Cari di no-harakat space (koordinat sama dengan approxStart)
    val noHarakatText = noHarakatSb.toString()
    val totalLen = noHarakatText.length
    val cleanLen = cleanSelected.length
    val radius = 300
    val searchStart = maxOf(0, approxStart - radius)
    val searchEnd = minOf(totalLen, approxStart + cleanLen + radius)

    var foundIdx = -1
    if (searchStart < searchEnd) {
        foundIdx = noHarakatText.indexOf(cleanSelected, searchStart)
        if (foundIdx < 0 || foundIdx >= searchEnd) {
            // tidak ditemukan dalam radius, coba full search
            foundIdx = noHarakatText.indexOf(cleanSelected)
        }
    } else {
        foundIdx = noHarakatText.indexOf(cleanSelected)
    }

    if (foundIdx < 0) return approxStart to selectedText.length

    // Map noHarakat index → withHarakat index via offsetMap
    val mapStart = foundIdx.coerceAtMost(offsetMap.size - 1)
    val mapEnd = (foundIdx + cleanLen).coerceAtMost(offsetMap.size - 1)
    val harakatStart = offsetMap[mapStart]
    val harakatEnd = offsetMap[mapEnd]
    return harakatStart to maxOf(0, harakatEnd - harakatStart)
}

// ─── Lucene Arabic Light10 Stemmer ──────────────────────────────────────────

object ArabicLightStemmer {
    private val prefixStrings = listOf(
        "والله", "وبالله", "فالله", "فبالله",
        "والل", "فالل", "بالل", "كالل", "وللم", "فللم",
        "وال", "فال", "بال", "كال", "لل", "ال"
    ).map { it.removingHarakat() }

    private val suffixStrings = listOf(
        "هما", "تاني", "تَيْن", "كُمَا", "هُمَا",
        "ان", "ات", "ون", "ين", "يه", "ية", "هم", "هن", "كم", "نا", "ها", "وا", "يا", "ك"
    ).map { it.removingHarakat() }

    private fun stemWordToBuffer(input: CharSequence, output: StringBuilder) {
        val clean = StringBuilder(input.length)
        for (i in input.indices) {
            val ch = input[i]
            if (ch.isArabicHarakat() || ch.code == 0x0640) continue

            val valCode = ch.code
            when (valCode) {
                0x0623, 0x0625, 0x0622, 0x0671 -> {
                    clean.append('\u0627')
                }
                0x0629 -> {
                    clean.append('\u0647')
                }
                0x0649 -> {
                    clean.append('\u064A')
                }
                else -> {
                    clean.append(ch)
                }
            }
        }

        var start = 0
        var count = clean.length

        // Prefix trimming
        for (prefix in prefixStrings) {
            val pLen = prefix.length
            if (count - pLen >= 3) {
                var isMatch = true
                for (i in 0 until pLen) {
                    if (clean[0 + i] != prefix[i]) {
                        isMatch = false
                        break
                    }
                }
                if (isMatch) {
                    start += pLen
                    count -= pLen
                    break
                }
            }
        }

        // Suffix trimming
        for (suffix in suffixStrings) {
            val sLen = suffix.length
            if (count - sLen >= 3) {
                var isMatch = true
                for (i in 0 until sLen) {
                    if (clean[start + count - sLen + i] != suffix[i]) {
                        isMatch = false
                        break
                    }
                }
                if (isMatch) {
                    count -= sLen
                    break
                }
            }
        }

        if (count > 0) {
            output.append(clean, start, start + count)
        }
    }

    /**
     * Stems all Arabic words in a given text block in a single pass.
     */
    fun stemText(text: String): String {
        if (text.isEmpty()) return text

        val output = StringBuilder(text.length)
        val currentToken = StringBuilder(32)

        for (i in text.indices) {
            val ch = text[i]
            val valCode = ch.code
            val isArabic = valCode in 0x0600..0x06FF ||
                    valCode in 0x0750..0x077F ||
                    valCode in 0x08A0..0x08FF

            if (isArabic) {
                currentToken.append(ch)
            } else {
                if (currentToken.isNotEmpty()) {
                    stemWordToBuffer(currentToken, output)
                    currentToken.setLength(0)
                }
                output.append(ch)
            }
        }

        if (currentToken.isNotEmpty()) {
            stemWordToBuffer(currentToken, output)
        }

        return output.toString()
    }
}

/**
 * Stems Arabic text using Lucene Light10 algorithm.
 */
fun String.stemArabicLight10(): String = ArabicLightStemmer.stemText(this)

// ─── Arabic Matching Ranges & Snippet Around ──────────────────────────────────

data class RangeWithIndex(val range: IntRange, val keywordIndex: Int)

/**
 * Returns all IntRanges in `this` string that match any of the given Arabic keywords or multi-word phrases,
 * handling Alif, Ta Marbuta/Ha, Alif Maqsura/Ya, diacritics/tatweel stripping, and prefix variations.
 */
fun String.findArabicMatchingRanges(keywords: List<String>): List<IntRange> {
    return findArabicMatchingRangesWithIndex(keywords).map { it.range }
}

fun String.findArabicMatchingRangesWithIndex(keywords: List<String>): List<RangeWithIndex> {
    if (keywords.isEmpty() || this.isEmpty()) return emptyList()

    val normalizedChars = StringBuilder(this.length)
    val indexMap = IntArray(this.length)
    var normCount = 0

    var utf16Offset = 0
    var idx = 0
    while (idx < this.length) {
        val char = this[idx]
        val isDiacritic = char.isArabicHarakat()
        val isTatweel = char.code == 0x0640

        if (isDiacritic || isTatweel) {
            utf16Offset++
            idx++
            continue
        }

        val normalizedChar: Char = when (char.code) {
            0x0623, 0x0625, 0x0622, 0x0671 -> 'ا'
            0x0629 -> 'ه'
            0x0649 -> 'ي'
            else -> char
        }

        if (normCount < indexMap.size) {
            indexMap[normCount] = utf16Offset
        }
        normalizedChars.append(normalizedChar)
        normCount++
        utf16Offset++
        idx++
    }

    val normalizedText = normalizedChars.toString()
    val ranges = mutableListOf<RangeWithIndex>()

    fun coreWord(s: String): String = s.stemArabicLight10()

    fun normalizeToken(token: CharSequence): String {
        val norm = StringBuilder()
        for (i in token.indices) {
            val ch = token[i]
            if (ch.isArabicHarakat() || ch.code == 0x0640) continue
            when (ch.code) {
                0x0623, 0x0625, 0x0622, 0x0671 -> norm.append('ا')
                0x0629 -> norm.append('ه')
                0x0649 -> norm.append('ي')
                else -> norm.append(ch)
            }
        }
        return norm.toString()
    }

    class TextWord(
        val text: String,
        val core: String,
        val normStartIdx: Int,
        val normEndIdx: Int
    )

    val textWords = mutableListOf<TextWord>()
    var wordStart: Int? = null

    for (idx in normalizedText.indices) {
        val ch = normalizedText[idx]
        if (ch.isWhitespace() || !ch.isLetterOrDigit()) {
            if (wordStart != null) {
                val start = wordStart
                val wordStr = normalizedText.substring(start, idx)
                val core = coreWord(wordStr)
                textWords.add(TextWord(wordStr, core, start, idx))
                wordStart = null
            }
        } else {
            if (wordStart == null) {
                wordStart = idx
            }
        }
    }
    if (wordStart != null) {
        val start = wordStart
        val wordStr = normalizedText.substring(start)
        val core = coreWord(wordStr)
        textWords.add(TextWord(wordStr, core, start, normalizedText.length))
    }

    if (textWords.isEmpty()) return emptyList()

    for ((keywordIndex, keyword) in keywords.withIndex()) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) continue

        val rawTokens = trimmed.split(PUNCT_REGEX).filter { it.isNotEmpty() }
        class QueryWord(val norm: String, val core: String)

        val queryWords = rawTokens.mapNotNull { token ->
            val norm = normalizeToken(token)
            if (norm.isEmpty()) null else QueryWord(norm, coreWord(norm))
        }

        if (queryWords.isEmpty()) continue
        val m = queryWords.size

        if (m <= textWords.size) {
            for (i in 0..textWords.size - m) {
                var sequenceMatches = true
                for (j in 0 until m) {
                    val tw = textWords[i + j]
                    val qw = queryWords[j]
                    if (tw.text != qw.norm && tw.core != qw.core && tw.text != qw.core && tw.core != qw.norm) {
                        sequenceMatches = false
                        break
                    }
                }

                if (sequenceMatches) {
                    val firstWord = textWords[i]
                    val lastWord = textWords[i + m - 1]

                    val normStartIdx = firstWord.normStartIdx
                    val normEndIdx = lastWord.normEndIdx

                    if (normStartIdx < normCount) {
                        val rawUtf16Start = indexMap[normStartIdx]
                        val rawUtf16End = if (normEndIdx < normCount) {
                            indexMap[normEndIdx]
                        } else {
                            utf16Offset
                        }

                        if (rawUtf16End > rawUtf16Start) {
                            val range = IntRange(rawUtf16Start, rawUtf16End - 1)
                            if (!ranges.any { it.range == range && it.keywordIndex == keywordIndex }) {
                                ranges.add(RangeWithIndex(range, keywordIndex))
                            }
                        }
                    }
                }
            }
        }
    }

    ranges.sortBy { it.range.first }
    return ranges
}

/**
 * Mengambil potongan teks di sekitar keyword yang ditemukan.
 * - keywords: List kata kunci yang dicari.
 * - contextLength: Jumlah karakter sebelum dan sesudah keyword.
 */
fun String.snippetAround(keywords: List<String>, contextLength: Int = 60): String {
    val ranges = findArabicMatchingRanges(keywords)
    val firstRange = ranges.firstOrNull()
    if (firstRange == null) {
        val limit = minOf(this.length, contextLength * 2)
        return this.substring(0, limit)
            .replace("\\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    var startIdx = maxOf(0, firstRange.first - contextLength)
    var endIdx = minOf(this.length, firstRange.last + 1 + contextLength)

    if (startIdx > 0) {
        val spaceIdx = this.lastIndexOf(' ', startIdx)
        if (spaceIdx != -1) {
            startIdx = spaceIdx + 1
        }
    }
    if (endIdx < this.length) {
        val spaceIdx = this.indexOf(' ', endIdx)
        if (spaceIdx != -1) {
            endIdx = spaceIdx
        }
    }

    var cleanSnippet = this.substring(startIdx, endIdx)
        .replace("\\n", " ")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    if (startIdx > 0) {
        cleanSnippet = "...$cleanSnippet"
    }
    if (endIdx < this.length) {
        cleanSnippet = "$cleanSnippet..."
    }

    return cleanSnippet
}

fun String.snippetNear(keywords: List<String>, nearDistance: Int, contextLength: Int = 60): String {
    val rangesWithIndex = findArabicMatchingRangesWithIndex(keywords)
    if (rangesWithIndex.isEmpty()) {
        val limit = minOf(this.length, contextLength * 2)
        return this.substring(0, limit).replace(WHITESPACE_REGEX, " ").trim()
    }

    val sortedRanges = rangesWithIndex.sortedBy { it.range.first }
    val uniqueKeywordsCount = sortedRanges.map { it.keywordIndex }.toSet().size
    var bestStartIdx: Int? = null
    var bestEndIdx: Int? = null
    var foundCluster = false

    if (uniqueKeywordsCount > 1) {
        val validRanges = filterRangesForNearMode(rangesWithIndex, uniqueKeywordsCount, nearDistance)
        if (validRanges.isNotEmpty()) {
            val sortedValid = validRanges.sortedBy { it.first }
            bestStartIdx = sortedValid.first().first
            bestEndIdx = sortedValid.last().last + 1
            foundCluster = true
        }
    }

    if (!foundCluster) {
        bestStartIdx = sortedRanges[0].range.first
        bestEndIdx = sortedRanges[0].range.last + 1
    }

    val targetStart = bestStartIdx ?: 0
    val targetEnd = bestEndIdx ?: 0

    var startIdx = maxOf(0, targetStart - contextLength)
    var endIdx = minOf(this.length, targetEnd + contextLength)

    if (startIdx > 0) {
        val spaceIdx = this.lastIndexOf(' ', startIdx)
        if (spaceIdx != -1) {
            startIdx = spaceIdx + 1
        }
    }
    if (endIdx < this.length) {
        val spaceIdx = this.indexOf(' ', endIdx)
        if (spaceIdx != -1) {
            endIdx = spaceIdx
        }
    }

    var cleanSnippet = this.substring(startIdx, endIdx)
        .replace("\\n", " ")
        .replace("\n", " ")
        .replace("\r", " ")
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    if (startIdx > 0) {
        cleanSnippet = "...$cleanSnippet"
    }
    if (endIdx < this.length) {
        cleanSnippet = "$cleanSnippet..."
    }

    return cleanSnippet
}

fun String.filterRangesForNearMode(rangesWithIndex: List<RangeWithIndex>, keywordsCount: Int, nearDistance: Int): List<IntRange> {
    if (rangesWithIndex.size < 2 || keywordsCount <= 1) return rangesWithIndex.map { it.range }

    val sortedRanges = rangesWithIndex.sortedBy { it.range.first }
    val uniqueKeywordsCount = sortedRanges.map { it.keywordIndex }.toSet().size
    if (uniqueKeywordsCount <= 1) return emptyList()

    val maxAllowedDistance = nearDistance * (uniqueKeywordsCount - 1) + 5
    val validRanges = mutableSetOf<IntRange>()

    var left = 0
    val countMap = mutableMapOf<Int, Int>()
    var satisfiedTypes = 0

    for (right in sortedRanges.indices) {
        val rightKw = sortedRanges[right].keywordIndex
        val count = countMap.getOrDefault(rightKw, 0)
        countMap[rightKw] = count + 1
        if (countMap[rightKw] == 1) satisfiedTypes++

        while (satisfiedTypes == uniqueKeywordsCount) {
            val leftKw = sortedRanges[left].keywordIndex
            val leftCount = countMap.getOrDefault(leftKw, 0)
            if (leftCount > 1) {
                countMap[leftKw] = leftCount - 1
                left++
            } else {
                break
            }
        }

        if (satisfiedTypes != uniqueKeywordsCount) continue

        val startRange = sortedRanges[left].range
        val endRange = sortedRanges[right].range

        if (startRange == endRange) {
            validRanges.add(startRange)
            continue
        }

        val startBound = startRange.last + 1
        val endBound = endRange.first

        if (startBound <= endBound && endBound <= this.length) {
            val textBetween = this.substring(startBound, endBound)
            val wordsBetween = textBetween.split(WHITESPACE_REGEX).count { it.isNotEmpty() }

            if (wordsBetween <= maxAllowedDistance) {
                for (k in left..right) {
                    validRanges.add(sortedRanges[k].range)
                }
            }
        }
    }

    return sortedRanges.map { it.range }.filter { validRanges.contains(it) }
}


