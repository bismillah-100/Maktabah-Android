package com.maktabah.manager

/**
 * Pure Kotlin domain helpers for classifying imported (non-server) books,
 * building deletion confirmation summaries, and filtering bulk downloads.
 * Kept free of Android/JNI dependencies for straightforward JVM testing.
 */
object BookCatalogClassifier {

    data class DeleteSelectionSummary(
        val importedBookIds: List<Int>,
        val downloadedServerBookIds: List<Int>,
        val skippedDownloadingBookIds: List<Int> = emptyList(),
    ) {
        val totalDeletableCount: Int
            get() = importedBookIds.size + downloadedServerBookIds.size
    }

    /**
     * Identifies book IDs in [allCatalogBookIds] that belong to imported books.
     *
     * Legacy fallback (`bookId !in serverIndexIds && bookId !in updateServerIds`) is
     * strictly fail-closed: if [serverIndexIds] is null or empty, only [explicitImportedIds]
     * are treated as imported so server catalog entries are never misclassified.
     */
    fun classifyImportedBookIds(
        allCatalogBookIds: Set<Int>,
        explicitImportedIds: Set<Int>,
        serverIndexIds: Set<Int>?,
        updateServerIds: Set<Int>,
    ): Set<Int> {
        if (allCatalogBookIds.isEmpty()) return emptySet()

        val hasValidServerIndex = !serverIndexIds.isNullOrEmpty()
        val imported = HashSet<Int>()
        for (bookId in allCatalogBookIds) {
            val isExplicitImported = explicitImportedIds.contains(bookId)
            val isLegacyImported =
                hasValidServerIndex &&
                    !serverIndexIds.contains(bookId) &&
                    !updateServerIds.contains(bookId)
            if (isExplicitImported || isLegacyImported) {
                imported.add(bookId)
            }
        }
        return imported
    }

    /**
     * Categorizes [selectedBookIds] into permanent imported deletions, local-only
     * server book deletions, and actively downloading books that must be skipped.
     */
    fun summarizeDeletionSelection(
        selectedBookIds: Set<Int>,
        downloadedBookIds: Set<Int>,
        importedBookIds: Set<Int>,
        activeDownloadingBookIds: Set<Int> = emptySet(),
    ): DeleteSelectionSummary {
        if (selectedBookIds.isEmpty()) {
            return DeleteSelectionSummary(
                importedBookIds = emptyList(),
                downloadedServerBookIds = emptyList(),
                skippedDownloadingBookIds = emptyList(),
            )
        }

        val imported = mutableListOf<Int>()
        val downloadedServer = mutableListOf<Int>()
        val skippedDownloading = mutableListOf<Int>()

        for (bookId in selectedBookIds) {
            if (activeDownloadingBookIds.contains(bookId)) {
                skippedDownloading.add(bookId)
                continue
            }
            when {
                importedBookIds.contains(bookId) -> imported.add(bookId)
                downloadedBookIds.contains(bookId) -> downloadedServer.add(bookId)
            }
        }

        return DeleteSelectionSummary(
            importedBookIds = imported,
            downloadedServerBookIds = downloadedServer,
            skippedDownloadingBookIds = skippedDownloading,
        )
    }

    /**
     * Excludes already-downloaded books and imported books (which have no server host)
     * from bulk download confirmations.
     */
    fun filterBooksNeedingDownload(
        selectedBookIds: Set<Int>,
        downloadedBookIds: Set<Int>,
        importedBookIds: Set<Int>,
    ): List<Int> {
        if (selectedBookIds.isEmpty()) return emptyList()
        return selectedBookIds.filter { bookId ->
            !downloadedBookIds.contains(bookId) && !importedBookIds.contains(bookId)
        }
    }
}
