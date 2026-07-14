package com.maktabah.ui.annotation

import com.maktabah.ui.common.ArabicTextRenderer
import com.maktabah.utils.calculateRangeWithoutHarakat
import com.maktabah.utils.findRangeInOriginal

object AnnotationCoordinator {
    /**
     * Kalkulasi (diacLoc, diacLen, plainLoc, plainLen) sesuai iOS ArabicRangeCalculator.
     *
     * Ranges disimpan dalam **sourceText space** (step3/pre-honorific, post-cleaning) — sama persis
     * dengan iOS di mana ann.range dan ann.rangeDiacritics mengacu ke ArabicRenderResult.sourceText,
     * bukan ke rawNass maupun final displayed text.
     *
     * @param loc Posisi seleksi di FINAL displayed text (output IbarotTextView)
     * @param len Panjang seleksi di FINAL displayed text
     * @param rawNass Teks asli dari DB
     * @param showHarakat True jika saat ini harakat ditampilkan
     */
    fun calculateBothRanges(
        loc: Int,
        len: Int,
        rawNass: String,
        showHarakat: Boolean,
    ): IntArray =
        if (showHarakat) {
            // Pipeline dengan harakat → dapatkan honorific result
            val hr = ArabicTextRenderer.processTextWithResult(rawNass, showHarakat = true)
            // Konversi displayed → sourceText (step3 with-harakat) space
            val (srcStart, srcLen) = hr.remapSourceRange(loc, len)
            // Hitung plain range dari step3 (strip harakat + hitung offset)
            val (plainLoc, plainLen) = calculateRangeWithoutHarakat(srcStart, srcLen, hr.sourceText)
            intArrayOf(srcStart, srcLen, plainLoc, plainLen)
        } else {
            // Pipeline tanpa harakat → step3 without-harakat
            val hrNoDiac = ArabicTextRenderer.processTextWithResult(rawNass, showHarakat = false)
            val (srcStart, srcLen) = hrNoDiac.remapSourceRange(loc, len)
            // Hitung diac range: cari di step3 WITH harakat
            val hrDiac = ArabicTextRenderer.processTextWithResult(rawNass, showHarakat = true)
            val selectedText =
                hrNoDiac.sourceText.let {
                    if (srcStart + srcLen <= it.length) {
                        it.substring(srcStart, srcStart + srcLen)
                    } else {
                        ""
                    }
                }
            val (diacLoc, diacLen) = hrDiac.sourceText.findRangeInOriginal(selectedText, srcStart)
            intArrayOf(diacLoc, diacLen, srcStart, srcLen)
        }
}
