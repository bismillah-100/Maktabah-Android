package com.maktabah.ui.annotation

import com.maktabah.models.ActiveAnnotationState
import com.maktabah.models.Annotation
import com.maktabah.models.BookContent
import com.maktabah.ui.common.ArabicTextRenderer
import com.maktabah.utils.calculateRangeWithoutHarakat
import com.maktabah.utils.findRangeInOriginal
import java.util.UUID

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

    fun buildEditedAnnotation(
        active: ActiveAnnotationState,
        bookId: Int,
        content: BookContent?,
        showHarakat: Boolean,
        selectedType: Int,
        selectedColor: String,
        noteText: String,
        tagsText: String,
    ): Annotation? {
        if (active.annotation == null && content == null) return null

        val (diacLoc, diacLen, plainLoc, plainLen) =
            if (active.annotation == null) {
                calculateBothRanges(
                    active.loc,
                    active.len,
                    content!!.nass,
                    showHarakat,
                )
            } else {
                intArrayOf(
                    active.annotation.rangeDiacLocation,
                    active.annotation.rangeDiacLength,
                    active.annotation.rangeLocation,
                    active.annotation.rangeLength,
                )
            }

        return Annotation(
            id = active.annotation?.id,
            bkId = bookId,
            contentId = active.annotation?.contentId ?: content!!.id,
            colorHex = if (selectedType == 1) "#000000" else selectedColor,
            note = noteText.ifBlank { null },
            type = selectedType,
            createdAt = active.annotation?.createdAt?.let {
                if (it in 1L..9999999999L) it * 1000L else it
            } ?: System.currentTimeMillis(),
            page = active.annotation?.page ?: content!!.page,
            context = active.annotation?.context ?: active.selectedText,
            rangeLocation = plainLoc,
            rangeLength = plainLen,
            rangeDiacLocation = diacLoc,
            rangeDiacLength = diacLen,
            part = active.annotation?.part ?: content!!.part,
            tags = tagsText
                .replace("،", ",")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(","),
            ckRecordId = active.annotation?.ckRecordId ?: UUID.randomUUID().toString(),
        )
    }
}
