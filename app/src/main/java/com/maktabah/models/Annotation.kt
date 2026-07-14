package com.maktabah.models

import androidx.compose.runtime.Immutable

@Immutable
data class Annotation(
    val id: Long? = null,
    val bkId: Int,
    val contentId: Int,
    val colorHex: String,
    val note: String?,
    val type: Int,
    val createdAt: Long,
    val page: Int,
    val context: String,
    val rangeLocation: Int = 0,
    val rangeLength: Int = 0,
    val rangeDiacLocation: Int = 0,
    val rangeDiacLength: Int = 0,
    val part: Int = 0,
    val tags: String = "",
    val ckRecordId: String? = null,
    val lastModified: Long? = null,
)

@Immutable
data class ActiveAnnotationState(
    val annotation: Annotation? = null,
    val loc: Int = 0,
    val len: Int = 0,
    val selectedText: String = "",
    val type: Int = 0, // 0: Highlight, 1: Underline
    val colorHex: String = "#FFFF00",
)
