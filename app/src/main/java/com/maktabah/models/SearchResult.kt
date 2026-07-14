package com.maktabah.models

import androidx.compose.runtime.Immutable

@Immutable
data class SearchResult(
    val contentId: Int,
    val bookId: Int,
    val text: String,
    val page: Int,
    val part: Int
)
