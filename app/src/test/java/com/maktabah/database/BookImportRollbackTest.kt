package com.maktabah.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookImportRollbackTest {
    @Test
    fun shouldCleanTargetArchiveOnFailure_cleansNewImportWhenContentIntegrated() {
        assertTrue(
            BookImportManager.shouldCleanTargetArchiveOnFailure(
                contentIntegrated = true,
                isNewBook = true,
                catalogCommitted = false,
                oldArchiveId = null,
                targetArchiveId = 4,
            )
        )
        assertTrue(
            BookImportManager.shouldCleanTargetArchiveOnFailure(
                contentIntegrated = true,
                isNewBook = true,
                catalogCommitted = true,
                oldArchiveId = null,
                targetArchiveId = 4,
            )
        )
    }

    @Test
    fun shouldCleanTargetArchiveOnFailure_cleansCrossArchiveReplaceBeforeCatalogCommit() {
        assertTrue(
            BookImportManager.shouldCleanTargetArchiveOnFailure(
                contentIntegrated = true,
                isNewBook = false,
                catalogCommitted = false,
                oldArchiveId = 2,
                targetArchiveId = 5,
            )
        )
    }

    @Test
    fun shouldCleanTargetArchiveOnFailure_preservesTargetArchiveOnSameArchiveOrCommittedReplace() {
        assertFalse(
            BookImportManager.shouldCleanTargetArchiveOnFailure(
                contentIntegrated = true,
                isNewBook = false,
                catalogCommitted = false,
                oldArchiveId = 5,
                targetArchiveId = 5,
            )
        )
        assertFalse(
            BookImportManager.shouldCleanTargetArchiveOnFailure(
                contentIntegrated = true,
                isNewBook = false,
                catalogCommitted = true,
                oldArchiveId = 2,
                targetArchiveId = 5,
            )
        )
        assertFalse(
            BookImportManager.shouldCleanTargetArchiveOnFailure(
                contentIntegrated = false,
                isNewBook = true,
                catalogCommitted = false,
                oldArchiveId = null,
                targetArchiveId = 5,
            )
        )
    }
}
