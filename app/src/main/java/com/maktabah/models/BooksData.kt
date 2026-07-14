package com.maktabah.models

import androidx.compose.runtime.Immutable

@Immutable
data class BooksData(
    val id: Int,
    val categoryId: Int,
    val name: String,
    val archive: Int,
    val info: String,
    val betaka: String,
    val authno: Int,
    val auth: String,
    val authInf: String,
    val isMultiLanguage: Boolean = false,
)
