package com.maktabah.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test
    fun dynamicString_storesValue() {
        val text = UiText.DynamicString("hello")
        assertEquals("hello", text.value)
    }

    @Test
    fun stringResource_varargMatchesListConstructor() {
        val fromList = UiText.StringResource(resId = 101, args = listOf(3, "ok"))
        val fromVararg = UiText.StringResource(101, 3, "ok")

        assertEquals(fromList, fromVararg)
        assertEquals(listOf(3, "ok"), fromVararg.args)
    }

    @Test
    fun pluralResource_defaultsArgsToQuantityList() {
        val singleCount = UiText.PluralResource(resId = 202, quantity = 5)
        assertEquals(202, singleCount.resId)
        assertEquals(5, singleCount.quantity)
        assertEquals(listOf(5), singleCount.args)

        val customArgs = UiText.PluralResource(202, 5, 5, "books")
        assertEquals(listOf(5, "books"), customArgs.args)
    }
}
