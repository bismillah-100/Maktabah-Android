package com.maktabah.utils

import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDirectionTextTest {

    @Test
    fun testSingleParagraphDirections() {
        assertTrue("بسم الله الرحمن الرحيم".isParagraphRTL())
        assertFalse("Catatan penting bahasa Indonesia".isParagraphRTL())

        val rtlAttr = "بسم الله الرحمن الرحيم".toAutoDirectionAnnotatedString()
        assertEquals(1, rtlAttr.paragraphStyles.size)
        assertEquals(TextAlign.Right, rtlAttr.paragraphStyles[0].item.textAlign)
        assertEquals(TextDirection.Rtl, rtlAttr.paragraphStyles[0].item.textDirection)

        val ltrAttr = "Catatan penting bahasa Indonesia".toAutoDirectionAnnotatedString()
        assertEquals(1, ltrAttr.paragraphStyles.size)
        assertEquals(TextAlign.Left, ltrAttr.paragraphStyles[0].item.textAlign)
        assertEquals(TextDirection.Ltr, ltrAttr.paragraphStyles[0].item.textDirection)
    }

    @Test
    fun testMixedMultiParagraphDirections() {
        val text = "الفقرة الأولى عربية\nCatatan kedua bahasa Indonesia\n123 - الفقرة الثالثة"
        val attr = text.toAutoDirectionAnnotatedString()

        assertEquals(3, attr.paragraphStyles.size)

        // First paragraph: Arabic (RTL)
        assertEquals(TextAlign.Right, attr.paragraphStyles[0].item.textAlign)
        assertEquals(TextDirection.Rtl, attr.paragraphStyles[0].item.textDirection)
        assertEquals(0, attr.paragraphStyles[0].start)
        assertEquals("الفقرة الأولى عربية\n".length, attr.paragraphStyles[0].end)

        // Second paragraph: Indonesian (LTR)
        assertEquals(TextAlign.Left, attr.paragraphStyles[1].item.textAlign)
        assertEquals(TextDirection.Ltr, attr.paragraphStyles[1].item.textDirection)

        // Third paragraph: Starts with digits then Arabic (RTL)
        assertEquals(TextAlign.Right, attr.paragraphStyles[2].item.textAlign)
        assertEquals(TextDirection.Rtl, attr.paragraphStyles[2].item.textDirection)
        assertEquals(text.length, attr.paragraphStyles[2].end)
    }

    @Test
    fun testEmptyAndNeutralFallbacks() {
        // Empty text
        val emptyAttr = "".toAutoDirectionAnnotatedString()
        assertEquals(0, emptyAttr.paragraphStyles.size)
        assertTrue("".isParagraphRTL())

        // Numbers/neutral only
        assertTrue("12345".isParagraphRTL())
        val numberAttr = "12345".toAutoDirectionAnnotatedString()
        assertEquals(TextAlign.Right, numberAttr.paragraphStyles[0].item.textAlign)
        assertEquals(TextDirection.Rtl, numberAttr.paragraphStyles[0].item.textDirection)
    }

    @Test
    fun testLeadingPunctuationBeforeStrongCharacter() {
        assertTrue("... مرحبا".isParagraphRTL())
        assertFalse("... Hello".isParagraphRTL())

        val mixed = "... مرحبا\n... Hello"
        val attr = mixed.toAutoDirectionAnnotatedString()
        assertEquals(2, attr.paragraphStyles.size)
        assertEquals(TextAlign.Right, attr.paragraphStyles[0].item.textAlign)
        assertEquals(TextAlign.Left, attr.paragraphStyles[1].item.textAlign)
    }

    @Test
    fun testContextSpanAndParagraphStyleComposition() {
        val arabicContext = "قال رسول الله"
        val arabicAttr = arabicContext.toAutoDirectionAnnotatedString()
        assertEquals(1, arabicAttr.paragraphStyles.size)
        assertEquals(TextAlign.Right, arabicAttr.paragraphStyles[0].item.textAlign)
        assertEquals(TextDirection.Rtl, arabicAttr.paragraphStyles[0].item.textDirection)

        val latinContext = "This is a quote from a book"
        val latinAttr = latinContext.toAutoDirectionAnnotatedString()
        assertEquals(1, latinAttr.paragraphStyles.size)
        assertEquals(TextAlign.Left, latinAttr.paragraphStyles[0].item.textAlign)
        assertEquals(TextDirection.Ltr, latinAttr.paragraphStyles[0].item.textDirection)
    }
}
