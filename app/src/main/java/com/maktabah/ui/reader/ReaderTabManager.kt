package com.maktabah.ui.reader

import androidx.lifecycle.ViewModel
import com.maktabah.models.BooksData
import com.maktabah.models.FlashTarget
import com.maktabah.models.ReaderTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReaderTabManager : ViewModel() {

    private val _tabs = MutableStateFlow<List<ReaderTab>>(emptyList())
    val tabs: StateFlow<List<ReaderTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    val activeTab: ReaderTab?
        get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }
            ?: _tabs.value.firstOrNull()

    /** Buka buku. Jika sudah terbuka, switch ke tab yang ada. */
    fun openTab(
        book: BooksData,
        archivePath: String,
        initialContentId: Int? = null,
        flashTarget: FlashTarget? = null,
        searchQuery: String? = null,
        setActive: Boolean = true,
    ): ReaderTab {
        val existing = _tabs.value.firstOrNull { it.bookId == book.id }
        if (existing != null) {
            if (setActive) _activeTabId.value = existing.id
            if (flashTarget != null) existing.viewModel.setFlashTarget(flashTarget)
            if (searchQuery != null) existing.viewModel.setSearchQuery(searchQuery)
            if (initialContentId != null) existing.viewModel.loadContentById(initialContentId)
            return existing
        }

        val newViewModel = ReaderViewModel()
        if (flashTarget != null) newViewModel.setFlashTarget(flashTarget)
        if (searchQuery != null) newViewModel.setSearchQuery(searchQuery)
        val tab = ReaderTab(
            bookId = book.id,
            bookName = book.name,
            archivePath = archivePath,
            viewModel = newViewModel,
        )
        _tabs.value += tab
        if (setActive) _activeTabId.value = tab.id
        return tab
    }

    fun closeTab(id: String) {
        val current = _tabs.value
        val idx = current.indexOfFirst { it.id == id }
        if (idx == -1) return

        val remaining = current.toMutableList().also { it.removeAt(idx) }
        _tabs.value = remaining

        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.getOrNull(idx - 1)?.id
                ?: remaining.getOrNull(idx)?.id
                    ?: remaining.firstOrNull()?.id
        }
    }

    fun closeTabsForBooks(bookIds: List<Int>) {
        val current = _tabs.value
        val tabsToClose = current.filter { bookIds.contains(it.bookId) }
        for (tab in tabsToClose) {
            closeTab(tab.id)
        }
    }

    fun switchTab(id: String) {
        if (_tabs.value.any { it.id == id }) {
            _activeTabId.value = id
        }
    }

    fun saveScrollY(tabId: String, y: Int) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) it.copy(savedScrollY = y) else it
        }
    }

    fun getSavedScrollY(tabId: String): Int =
        _tabs.value.firstOrNull { it.id == tabId }?.savedScrollY ?: 0
}
