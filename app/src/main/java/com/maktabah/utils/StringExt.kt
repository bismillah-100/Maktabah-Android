package com.maktabah.utils

val arabicDigits = listOf("٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩")

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

        if (v == 0x0623 || v == 0x0625 || v == 0x0622 || v == 0x0671) {
            sb.append('\u0627') // Alif
        } else {
            sb.append(char)
        }
    }
    return sb.toString()
}

fun String.removingHarakat(): String = this.filter { !it.isArabicHarakat() }

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

fun String.stripSpanTags(): String = replace(Regex("<[^>]*>"), "")

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
 * Mengembalikan (start, length) di teks dengan harakat.
 */
fun String.findRangeInOriginal(
    selectedText: String,
    approxStart: Int,
): Pair<Int, Int> {
    val cleanSelected = selectedText.removingHarakat()
    if (cleanSelected.isEmpty()) return approxStart to selectedText.length

    val cleanSelf = this.removingHarakat()
    val idx = cleanSelf.indexOf(cleanSelected)
    if (idx < 0) return approxStart to selectedText.length

    // Konversi posisi di cleanSelf → posisi di self (dengan harakat)
    var origStart = 0
    var cleanCount = 0
    for (i in this.indices) {
        if (cleanCount == idx) {
            origStart = i
            break
        }
        if (!this[i].isArabicHarakat()) cleanCount++
    }
    var origEnd = origStart
    cleanCount = 0
    for (i in origStart until this.length) {
        if (cleanCount == cleanSelected.length) break
        if (!this[i].isArabicHarakat()) cleanCount++
        origEnd = i + 1
    }
    return origStart to (origEnd - origStart)
}

/**
 * Mengambil potongan teks di sekitar keyword yang ditemukan.
 * - keywords: List kata kunci yang dicari.
 * - contextLength: Jumlah karakter sebelum dan sesudah keyword.
 */
fun String.snippetAround(keywords: List<String>, contextLength: Int = 60): String {
    if (keywords.isEmpty()) {
        val limit = minOf(this.length, contextLength * 2)
        return this.substring(0, limit)
            .replace("\\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    var bestStart = -1
    var bestEnd = -1

    for (keyword in keywords) {
        if (keyword.isEmpty()) continue
        val found = this.indexOf(keyword, ignoreCase = true)
        if (found != -1) {
            if (bestStart == -1 || found < bestStart) {
                bestStart = found
                bestEnd = found + keyword.length
            }
        }
    }

    if (bestStart == -1) {
        val limit = minOf(this.length, contextLength * 2)
        return this.substring(0, limit)
            .replace("\\n", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    var startIdx = maxOf(0, bestStart - contextLength)
    var endIdx = minOf(this.length, bestEnd + contextLength)

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
        .replace(Regex("\\s+"), " ")
        .trim()

    if (startIdx > 0) {
        cleanSnippet = "...$cleanSnippet"
    }
    if (endIdx < this.length) {
        cleanSnippet = "$cleanSnippet..."
    }

    return cleanSnippet
}

