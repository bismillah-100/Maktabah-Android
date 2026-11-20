package com.maktabah.manager

/**
 * Pure Kotlin domain helpers for classifying non-core catalog books (manual imports
 * and Google Drive update-added books), building deletion confirmation summaries,
 * and filtering bulk downloads.
 * Kept free of Android/JNI dependencies for straightforward JVM testing.
 */
object BookCatalogClassifier {

    data class CatalogBookClassification(
        val importedBookIds: Set<Int>,
        val updateAddedBookIds: Set<Int>,
    ) {
        val removableCatalogBookIds: Set<Int>
            get() = importedBookIds + updateAddedBookIds
    }

    data class DeleteSelectionSummary(
        val importedBookIds: List<Int>,
        val updateAddedBookIds: List<Int> = emptyList(),
        val downloadedServerBookIds: List<Int>,
        val skippedDownloadingBookIds: List<Int> = emptyList(),
    ) {
        val catalogRemovalBookIds: List<Int>
            get() = importedBookIds + updateAddedBookIds

        val totalDeletableCount: Int
            get() = importedBookIds.size + updateAddedBookIds.size + downloadedServerBookIds.size
    }

    /**
     * Classifies book IDs in [allCatalogBookIds] into manual imports and Google Drive
     * update-added books (books absent from the GitHub release [serverIndexIds]).
     *
     * Legacy fallbacks are strictly fail-closed: if [serverIndexIds] is null or empty,
     * only [explicitImportedIds] and [explicitUpdateAddedIds] are classified as removable
     * catalog entries so core catalog entries are never misclassified.
     */
    fun classifyCatalogBooks(
        allCatalogBookIds: Set<Int>,
        explicitImportedIds: Set<Int>,
        explicitUpdateAddedIds: Set<Int>,
        serverIndexIds: Set<Int>?,
        updateServerIds: Set<Int>,
    ): CatalogBookClassification {
        if (allCatalogBookIds.isEmpty()) {
            return CatalogBookClassification(emptySet(), emptySet())
        }

        val hasValidServerIndex = !serverIndexIds.isNullOrEmpty()
        val imported = HashSet<Int>()
        val updateAdded = HashSet<Int>()

        for (bookId in allCatalogBookIds) {
            if (explicitImportedIds.contains(bookId)) {
                imported.add(bookId)
                continue
            }

            val inGitHubIndex = hasValidServerIndex && serverIndexIds.contains(bookId)
            if (inGitHubIndex) {
                continue
            }

            val isExplicitUpdateAdded = explicitUpdateAddedIds.contains(bookId)
            val isLegacyUpdateAdded = hasValidServerIndex && updateServerIds.contains(bookId)
            if (isExplicitUpdateAdded || isLegacyUpdateAdded) {
                updateAdded.add(bookId)
                continue
            }

            if (hasValidServerIndex) {
                imported.add(bookId)
            }
        }

        return CatalogBookClassification(
            importedBookIds = imported,
            updateAddedBookIds = updateAdded,
        )
    }

    /**
     * Categorizes [selectedBookIds] into permanent imported deletions, update-added
     * catalog removals, local-only server book deletions, and actively downloading
     * books that must be skipped.
     */
    fun summarizeDeletionSelection(
        selectedBookIds: Set<Int>,
        downloadedBookIds: Set<Int>,
        importedBookIds: Set<Int>,
        updateAddedBookIds: Set<Int> = emptySet(),
        activeDownloadingBookIds: Set<Int> = emptySet(),
    ): DeleteSelectionSummary {
        if (selectedBookIds.isEmpty()) {
            return DeleteSelectionSummary(
                importedBookIds = emptyList(),
                updateAddedBookIds = emptyList(),
                downloadedServerBookIds = emptyList(),
                skippedDownloadingBookIds = emptyList(),
            )
        }

        val imported = mutableListOf<Int>()
        val updateAdded = mutableListOf<Int>()
        val downloadedServer = mutableListOf<Int>()
        val skippedDownloading = mutableListOf<Int>()

        for (bookId in selectedBookIds) {
            if (activeDownloadingBookIds.contains(bookId)) {
                skippedDownloading.add(bookId)
                continue
            }
            when {
                importedBookIds.contains(bookId) -> imported.add(bookId)
                updateAddedBookIds.contains(bookId) -> updateAdded.add(bookId)
                downloadedBookIds.contains(bookId) -> downloadedServer.add(bookId)
            }
        }

        return DeleteSelectionSummary(
            importedBookIds = imported,
            updateAddedBookIds = updateAdded,
            downloadedServerBookIds = downloadedServer,
            skippedDownloadingBookIds = skippedDownloading,
        )
    }

    /**
     * Excludes already-downloaded books and non-GitHub books (imports and Google Drive
     * update-only books) from Library bulk download confirmations.
     */
    fun filterBooksNeedingDownload(
        selectedBookIds: Set<Int>,
        downloadedBookIds: Set<Int>,
        nonServerDownloadableBookIds: Set<Int>,
    ): List<Int> {
        if (selectedBookIds.isEmpty()) return emptyList()
        return selectedBookIds.filter { bookId ->
            !downloadedBookIds.contains(bookId) && !nonServerDownloadableBookIds.contains(bookId)
        }
    }
}
