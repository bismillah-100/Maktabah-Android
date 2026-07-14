package com.maktabah.utils

data class HonorificReplacementEvent(
    val oldStart: Int,
    val oldEnd: Int,
    val newLength: Int,
)

data class HonorificReplacementResult(
    val sourceText: String,
    val text: String,
    val events: List<HonorificReplacementEvent>,
) {
    /** Remap range dari sourceText (sebelum penggantian) ke text (setelah penggantian). */
    fun remapDisplayedRange(
        start: Int,
        length: Int,
    ): Pair<Int, Int> {
        if (events.isEmpty()) return start to length
        var delta = 0
        val newStart =
            run {
                for (e in events) {
                    if (start < e.oldStart) break
                    if (start == e.oldStart) return@run e.oldStart + delta
                    if (start < e.oldEnd) return@run e.oldStart + delta + e.newLength
                    delta += e.newLength - (e.oldEnd - e.oldStart)
                }
                start + delta
            }
        delta = 0
        val endOffset = start + length
        val newEnd =
            run {
                for (e in events) {
                    if (endOffset < e.oldStart) break
                    if (endOffset == e.oldStart) return@run e.oldStart + delta
                    if (endOffset < e.oldEnd) return@run e.oldStart + delta + e.newLength
                    delta += e.newLength - (e.oldEnd - e.oldStart)
                }
                endOffset + delta
            }
        return newStart to maxOf(0, newEnd - newStart)
    }

    /**
     * Inverse dari remapDisplayedRange: konversi posisi di DISPLAYED text
     * kembali ke posisi di SOURCE text (pre-honorific/step3 space).
     * Setara iOS: ArabicRenderResult.sourceOffset(forDisplayedOffset:affinity:)
     */
    fun remapSourceRange(
        displayedStart: Int,
        displayedLength: Int,
    ): Pair<Int, Int> {
        fun sourceOffset(
            displayedOffset: Int,
            trailing: Boolean,
        ): Int {
            var delta = 0
            for (e in events) {
                val evStart = e.oldStart + delta
                val evEnd = evStart + e.newLength
                if (displayedOffset < evStart) break
                if (displayedOffset == evStart) return e.oldStart
                if (displayedOffset < evEnd) return if (trailing) e.oldEnd else e.oldStart
                delta += e.newLength - (e.oldEnd - e.oldStart)
            }
            return displayedOffset - delta
        }

        val srcStart = sourceOffset(displayedStart, trailing = false)
        val srcEnd = sourceOffset(displayedStart + displayedLength, trailing = true)
        return srcStart to maxOf(0, srcEnd - srcStart)
    }

    /**
     * Range di teks final tempat glyph honorific muncul.
     * Digunakan untuk mewarnai glyph seperti iOS replacementDisplayRanges.
     */
    val replacementDisplayRanges: List<IntRange>
        get() {
            if (events.isEmpty()) return emptyList()
            var delta = 0
            return events.map { e ->
                val displayStart = e.oldStart + delta
                delta += e.newLength - (e.oldEnd - e.oldStart)
                displayStart until displayStart + e.newLength
            }
        }
}