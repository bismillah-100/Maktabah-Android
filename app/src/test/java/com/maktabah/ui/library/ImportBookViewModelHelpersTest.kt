package com.maktabah.ui.library

import com.maktabah.R
import com.maktabah.models.ImportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportBookViewModelHelpersTest {
    @Test
    fun resolveInvalidatedBookId_returnsNullForNewImportAndSelectedIdOtherwise() {
        assertNull(ImportBookViewModel.resolveInvalidatedBookId(ImportMode.NEW, 42))
        assertEquals(42, ImportBookViewModel.resolveInvalidatedBookId(ImportMode.REPLACE, 42))
        assertEquals(42, ImportBookViewModel.resolveInvalidatedBookId(ImportMode.CHANGE_ID, 42))
        assertNull(ImportBookViewModel.resolveInvalidatedBookId(ImportMode.CHANGE_ID, null))
    }

    @Test
    fun successMessageResFor_mapsEachImportModeToDedicatedString() {
        assertEquals(R.string.import_success, ImportBookViewModel.successMessageResFor(ImportMode.NEW))
        assertEquals(R.string.replace_book_success, ImportBookViewModel.successMessageResFor(ImportMode.REPLACE))
        assertEquals(R.string.change_id_success, ImportBookViewModel.successMessageResFor(ImportMode.CHANGE_ID))
    }
}
