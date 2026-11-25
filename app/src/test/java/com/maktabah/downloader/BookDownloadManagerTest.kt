package com.maktabah.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BookDownloadManagerTest {
    @Test
    fun createValidatedIndexEntry_returnsEntryWhenRequiredFieldsPresent() {
        val entry = BookDownloadManager.createValidatedIndexEntry(
            bkid = 42,
            authno = 7,
            filename = "book_42.sqlite.zst",
            release = "v1.0",
            sizeZst = 12345L,
        )

        assertNotNull(entry)
        assertEquals(42, entry?.bkid)
        assertEquals(7, entry?.authno)
        assertEquals("book_42.sqlite.zst", entry?.filename)
        assertEquals("v1.0", entry?.release)
        assertEquals(12345L, entry?.sizeZst)
    }

    @Test
    fun createValidatedIndexEntry_allowsNullSizeAndZeroAuthor() {
        val entry = BookDownloadManager.createValidatedIndexEntry(
            bkid = 10,
            authno = 0,
            filename = "book_10.sqlite.zst",
            release = "v2.0",
            sizeZst = null,
        )

        assertNotNull(entry)
        assertEquals(0, entry?.authno)
        assertNull(entry?.sizeZst)
    }

    @Test
    fun createValidatedIndexEntry_rejectsNonPositiveBookIdOrBlankMetadata() {
        assertNull(
            BookDownloadManager.createValidatedIndexEntry(
                bkid = 0,
                authno = 5,
                filename = "book_0.sqlite.zst",
                release = "v1.0",
                sizeZst = 100L,
            )
        )
        assertNull(
            BookDownloadManager.createValidatedIndexEntry(
                bkid = -3,
                authno = 5,
                filename = "book_3.sqlite.zst",
                release = "v1.0",
                sizeZst = 100L,
            )
        )
        assertNull(
            BookDownloadManager.createValidatedIndexEntry(
                bkid = 5,
                authno = 1,
                filename = "   ",
                release = "v1.0",
                sizeZst = 100L,
            )
        )
        assertNull(
            BookDownloadManager.createValidatedIndexEntry(
                bkid = 5,
                authno = 1,
                filename = "book_5.sqlite.zst",
                release = "",
                sizeZst = 100L,
            )
        )
    }
}
