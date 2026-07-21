package com.maktabah.models

data class FlatLibraryItem(val item: Any, val level: Int, val isDownloaded: Boolean = false)
data class LoadMoreData(val categoryId: Int, val hiddenCount: Int)

enum class LibraryViewMode(val value: Int) {
    CATEGORY(0),
    AUTHOR(1)
}

data class LoadMoreAuthors(val hiddenCount: Int)
enum class IntegratePhase {
    DOWNLOAD,
    DATA,
    FTS
}

@androidx.compose.runtime.Immutable
data class BookDownloadState(
    val id: String = java.util.UUID.randomUUID().toString(),
    val bookId: Int,
    val bookName: String,
    val sizeText: String,
    val isDownloading: Boolean = false,
    val phase: IntegratePhase = IntegratePhase.DOWNLOAD,
    val progress: Int = 0,
    val error: String? = null,
    val contentId: Int? = null,
    val loc: Int? = null,
    val len: Int? = null,
    val query: String? = null,
    val isBulk: Boolean = false,
    val bulkBookIds: List<Int> = emptyList()
)