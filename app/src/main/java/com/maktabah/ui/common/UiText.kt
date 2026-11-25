package com.maktabah.ui.common

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

sealed interface UiText {
    data object Empty : UiText
    data class DynamicString(val value: String) : UiText
    data class StringResource(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList(),
    ) : UiText {
        constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())
    }
    data class PluralResource(
        @PluralsRes val resId: Int,
        val quantity: Int,
        val args: List<Any> = listOf(quantity),
    ) : UiText {
        constructor(@PluralsRes resId: Int, quantity: Int, vararg args: Any) : this(
            resId,
            quantity,
            if (args.isEmpty()) listOf(quantity) else args.toList(),
        )
    }

    @Composable
    fun asString(): String {
        return when (this) {
            is Empty -> ""
            is DynamicString -> value
            is StringResource -> stringResource(resId, *args.toTypedArray())
            is PluralResource -> pluralStringResource(resId, quantity, *args.toTypedArray())
        }
    }

    fun asString(context: Context): String {
        return when (this) {
            is Empty -> ""
            is DynamicString -> value
            is StringResource -> context.getString(resId, *args.toTypedArray())
            is PluralResource -> context.resources.getQuantityString(resId, quantity, *args.toTypedArray())
        }
    }
}
