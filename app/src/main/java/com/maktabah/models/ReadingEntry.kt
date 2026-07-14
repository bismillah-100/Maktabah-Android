package com.maktabah.models

data class ReadingEntry(
    val bookId: Int,
    var lastContentId: Int? = null,
    var lastOpenedAt: Long? = null,
    var favoritedAt: Long? = null,
    var positionUpdatedAt: Long? = null,
    var updatedAt: Long = System.currentTimeMillis(),
    var isFavorite: Boolean = false,
    var ckRecordId: String? = null
)