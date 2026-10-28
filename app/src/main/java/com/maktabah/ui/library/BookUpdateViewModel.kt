package com.maktabah.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.R
import com.maktabah.database.BookUpdateManager
import com.maktabah.database.updater.BookUpdateRepository
import com.maktabah.models.AuthIndexEntry
import com.maktabah.models.BookIndexEntry
import com.maktabah.models.BookUpdateItem
import com.maktabah.models.BookUpdateResult
import com.maktabah.models.UpdateAction
import com.maktabah.models.UpdateStatus
import com.maktabah.ui.common.UiText
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BookUpdateViewModel(
    private val repository: BookUpdateRepository,
) : ViewModel() {

    private val _availableUpdates = MutableStateFlow<List<BookUpdateItem>>(emptyList())
    val availableUpdates: StateFlow<List<BookUpdateItem>> = _availableUpdates.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()

    private val _isLoadingList = MutableStateFlow(false)
    val isLoadingList: StateFlow<Boolean> = _isLoadingList.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _progressMessage = MutableStateFlow<UiText>(UiText.Empty)
    val progressMessage: StateFlow<UiText> = _progressMessage.asStateFlow()

    val filteredUpdates: StateFlow<List<BookUpdateItem>> = combine(
        _availableUpdates,
        _searchText,
    ) { items, query ->
        if (query.isBlank()) {
            items
        } else {
            val cleanQuery = query.normalizeArabic()
            items.filter { item ->
                item.bookName.normalizeArabic().contains(cleanQuery, ignoreCase = true) ||
                    item.id.toString().contains(cleanQuery)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchText(query: String) {
        _searchText.value = query
    }

    fun loadAvailableUpdates() {
        if (_isLoadingList.value || _isUpdating.value) return
        viewModelScope.launch {
            _isLoadingList.value = true
            _progressMessage.value = UiText.StringResource(R.string.book_update_loading_list)
            try {
                val items = repository.fetchAvailableUpdates()
                _availableUpdates.value = items
                val needsCount = items.count { it.needsUpdate }
                _progressMessage.value = if (needsCount > 0) {
                    UiText.StringResource(R.string.book_update_found_count, needsCount)
                } else {
                    UiText.Empty
                }
            } catch (e: Exception) {
                _progressMessage.value = UiText.DynamicString("Error: ${e.localizedMessage ?: e.message ?: ""}")
            } finally {
                _isLoadingList.value = false
            }
        }
    }

    fun toggleSelection(bookId: Int) {
        if (_isUpdating.value) return
        _availableUpdates.value = _availableUpdates.value.map { item ->
            if (item.id == bookId && item.needsUpdate) {
                item.copy(isSelected = !item.isSelected)
            } else {
                item
            }
        }
    }

    fun selectAll() {
        if (_isUpdating.value) return
        _availableUpdates.value = _availableUpdates.value.map { item ->
            item.copy(isSelected = item.needsUpdate)
        }
    }

    fun deselectAll() {
        if (_isUpdating.value) return
        _availableUpdates.value = _availableUpdates.value.map { item ->
            item.copy(isSelected = false)
        }
    }

    fun selectOnlyUpdates() {
        selectAll()
    }

    fun performSelectedUpdates(onReloadLibrary: suspend () -> Unit) {
        val selectedItems = _availableUpdates.value.filter { it.isSelected && it.needsUpdate }
        if (selectedItems.isEmpty() || _isUpdating.value) return

        viewModelScope.launch {
            _isUpdating.value = true
            _progressMessage.value = UiText.StringResource(R.string.book_update_starting, selectedItems.size)
            try {
                val authEntries = try {
                    repository.fetchAuthIndexEntries()
                } catch (_: Exception) {
                    emptyList()
                }
                val authIndexMap = authEntries.associateBy { it.authId }

                // Tandai status Downloading pada item terpilih
                val selectedIds = selectedItems.map { it.id }.toSet()
                updateItemsStatus(selectedIds) { UpdateStatus.Downloading }

                // Download paralel per batch (maksimal 3 concurrent downloads)
                val stagedMap = downloadBooksInParallel(
                    selectedItems = selectedItems,
                    authIndexMap = authIndexMap,
                    maxConcurrent = 3,
                )

                _progressMessage.value = UiText.StringResource(
                    R.string.book_update_download_phase_done,
                    stagedMap.size,
                    selectedItems.size,
                )

                // Integrasi sekuensial ke database
                var completedCount = 0
                var processingIndex = 0
                val totalCount = selectedItems.size

                for (item in selectedItems) {
                    val staged = stagedMap[item.id] ?: continue
                    processingIndex++
                    updateItemStatus(item.id, UpdateStatus.Processing)
                    _progressMessage.value = UiText.StringResource(
                        R.string.book_update_processing_item,
                        item.bookName,
                        processingIndex,
                        totalCount,
                    )

                    try {
                        val result: BookUpdateResult = repository.applyStagedBookUpdate(staged)
                        when (result.action) {
                            UpdateAction.INSERTED, UpdateAction.UPDATED -> {
                                completedCount++
                                _availableUpdates.value = _availableUpdates.value.map { current ->
                                    if (current.id == item.id) {
                                        current.copy(
                                            existsInLibrary = true,
                                            currentVersion = current.newVersion,
                                            isSelected = false,
                                            status = UpdateStatus.Completed,
                                        )
                                    } else {
                                        current
                                    }
                                }
                            }
                            UpdateAction.SKIPPED -> {
                                updateItemStatus(item.id, UpdateStatus.Skipped)
                            }
                        }
                    } catch (e: Exception) {
                        updateItemStatus(item.id, UpdateStatus.Failed(e.localizedMessage ?: "Failed"))
                    }
                }

                _progressMessage.value = UiText.StringResource(
                    R.string.book_update_completed_summary,
                    completedCount,
                    totalCount,
                )

                if (completedCount > 0) {
                    onReloadLibrary()
                }
            } catch (e: Exception) {
                _progressMessage.value = UiText.DynamicString("Error: ${e.localizedMessage ?: e.message ?: ""}")
            } finally {
                repository.cleanupWorkingDirectory()
                _isUpdating.value = false
            }
        }
    }

    private suspend fun downloadBooksInParallel(
        selectedItems: List<BookUpdateItem>,
        authIndexMap: Map<Int, AuthIndexEntry>,
        maxConcurrent: Int,
    ): Map<Int, BookUpdateManager.StagedBookUpdate> = coroutineScope {
        val stagedUpdates = mutableMapOf<Int, BookUpdateManager.StagedBookUpdate>()
        var completedDownloads = 0

        for (chunk in selectedItems.chunked(maxConcurrent)) {
            val deferreds = chunk.map { item ->
                async(Dispatchers.IO) {
                    val entry = BookIndexEntry(
                        bkid = item.id,
                        bk = item.bookName,
                        category = item.category,
                        versionName = item.newVersion,
                        downloadURL = item.downloadURL,
                        fileSize = item.fileSize,
                    )
                    runCatching {
                        repository.stageBookDownload(entry, authIndexMap)
                    }.toPair(item.id)
                }
            }

            val results = deferreds.awaitAll()
            for ((bookId, result) in results) {
                completedDownloads++
                result.onSuccess { staged ->
                    stagedUpdates[bookId] = staged
                    updateItemStatus(bookId, UpdateStatus.Downloaded)
                }.onFailure { err ->
                    updateItemStatus(bookId, UpdateStatus.Failed(err.localizedMessage ?: "Download error"))
                }
                _progressMessage.value = UiText.StringResource(
                    R.string.book_update_downloading_progress,
                    completedDownloads,
                    selectedItems.size,
                )
            }
        }

        stagedUpdates
    }

    private fun <T> Result<T>.toPair(id: Int): Pair<Int, Result<T>> = id to this

    private fun updateItemStatus(bookId: Int, status: UpdateStatus) {
        _availableUpdates.value = _availableUpdates.value.map { item ->
            if (item.id == bookId) item.copy(status = status) else item
        }
    }

    private fun updateItemsStatus(bookIds: Set<Int>, statusProvider: (BookUpdateItem) -> UpdateStatus) {
        _availableUpdates.value = _availableUpdates.value.map { item ->
            if (bookIds.contains(item.id)) item.copy(status = statusProvider(item)) else item
        }
    }
}
