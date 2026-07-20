package com.maktabah.models

data class ReadingEntry(
    val bookId: Int,
    val lastContentId: Int? = null,
    val lastOpenedAt: Long? = null,
    val favoritedAt: Long? = null,
    val positionUpdatedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val ckRecordId: String? = null
)