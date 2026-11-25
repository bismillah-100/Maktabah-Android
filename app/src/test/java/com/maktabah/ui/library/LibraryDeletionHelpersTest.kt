package com.maktabah.ui.library

import com.maktabah.models.BundleBookIndexEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDeletionHelpersTest {
    @Test
    fun deriveCoreCatalogSets_collectsBookIdsAndPositiveAuthors() {
        val entries = listOf(
            BundleBookIndexEntry(bkid = 1, filename = "1.zst", release = "r1", sizeZst = 10L, authno = 100),
            BundleBookIndexEntry(bkid = 2, filename = "2.zst", release = "r1", sizeZst = null, authno = 0),
            BundleBookIndexEntry(bkid = 3, filename = "3.zst", release = "r1", sizeZst = 20L, authno = -5),
        )

        val (books, authors) = LibraryViewModel.deriveCoreCatalogSets(entries)

        assertEquals(setOf(1, 2, 3), books)
        assertEquals(setOf(100), authors)
    }

    @Test
    fun deriveCoreCatalogSets_returnsNullAuthorsWhenNonePositive() {
        val entries = listOf(
            BundleBookIndexEntry(bkid = 10, filename = "10.zst", release = "r1", sizeZst = null, authno = 0),
        )

        val (books, authors) = LibraryViewModel.deriveCoreCatalogSets(entries)

        assertEquals(setOf(10), books)
        assertNull(authors)
    }

    @Test
    fun computeCustomBookIds_failsClosedWhenCoreIndexMissing() {
        val result = LibraryViewModel.computeCustomBookIds(
            catalogBookIds = setOf(1, 2, 999999),
            coreBookIds = null,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun computeCustomBookIds_returnsOnlyNonCoreBookIds() {
        val result = LibraryViewModel.computeCustomBookIds(
            catalogBookIds = setOf(1, 2, 999999, 1000001),
            coreBookIds = setOf(1, 2, 3),
        )

        assertEquals(setOf(999999, 1000001), result)
    }

    @Test
    fun buildDeleteSelectionSummary_prioritizesActiveDownloadsOverDeletion() {
        val summary = LibraryViewModel.buildDeleteSelectionSummary(
            selectedBookIds = setOf(1, 2, 3, 4, 9001, 9002),
            activeDownloadingIds = setOf(2, 9002),
            customBookIds = setOf(9001, 9002),
            downloadedBookIds = setOf(1, 2, 9001, 9002),
        )

        assertEquals(listOf(9001), summary.customBookIds)
        assertEquals(listOf(1), summary.downloadedCoreBookIds)
        assertEquals(setOf(2, 9002), summary.skippedDownloadingBookIds.toSet())
        assertEquals(2, summary.totalDeletableCount)
    }

    @Test
    fun filterCleanCustomTargets_rejectsBooksWhoseArchiveCleanupFailed() {
        val archiveByBookId = mapOf(
            9001 to 5,
            9002 to 6,
            9003 to null,
            9004 to 0,
        )

        val cleaned = LibraryViewModel.filterCleanCustomTargets(
            customIds = listOf(9001, 9002, 9003, 9004),
            fullyCleanedArchiveIds = setOf(9001),
        ) { bookId ->
            archiveByBookId[bookId]
        }

        assertEquals(listOf(9001, 9003, 9004), cleaned)
    }
}
