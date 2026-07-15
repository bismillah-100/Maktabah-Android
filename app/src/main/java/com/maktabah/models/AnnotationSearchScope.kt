package com.maktabah.models

import androidx.annotation.StringRes
import com.maktabah.R

enum class AnnotationSearchScope(@StringRes val labelRes: Int) {
    ALL(R.string.annotations_search_all),
    BOOK(R.string.annotations_search_book),
    CONTEXT(R.string.annotations_search_context),
    NOTE(R.string.annotations_search_note),
    TAG(R.string.annotations_search_tag)
}
