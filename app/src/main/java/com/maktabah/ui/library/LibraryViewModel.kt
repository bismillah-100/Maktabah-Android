package com.maktabah.ui.library

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.R
import com.maktabah.database.SQLiteDB
import com.maktabah.downloader.BookDownloadManager
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.BookDownloadState
import com.maktabah.models.BooksData
import com.maktabah.models.BundleBookIndexEntry
import com.maktabah.models.CategoryData
import com.maktabah.models.FlatLibraryItem
import com.maktabah.models.LibraryViewMode
import com.maktabah.models.LoadMoreAuthors
import com.maktabah.models.LoadMoreData
import com.maktabah.ui.reader.ReaderTabManager
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LibraryViewModel(val dataManager: LibraryDataManager) : ViewModel() {

    private val _downloadedBookIds = MutableStateFlow<Set<Int>>(emptySet())
    val downloadedBookIds: StateFlow<Set<Int>> = _downloadedBookIds

    private val _activeDownloadStates = MutableStateFlow<List<BookDownloadState>>(emptyList())
    val activeDownloadStates: StateFlow<List<BookDownloadState>> = _activeDownloadStates

    private var downloadIndex: List<BundleBookIndexEntry>? = null

    private val _isDataLoaded = MutableStateFlow(false)
    val isDataLoaded: StateFlow<Boolean> = _isDataLoaded

    private val _rootCategories = MutableStateFlow<List<CategoryData>>(emptyList())

    private val _viewMode = MutableStateFlow(LibraryViewMode.CATEGORY)
    val viewMode: StateFlow<LibraryViewMode> = _viewMode

    private val _authorCategories = MutableStateFlow<List<CategoryData>>(emptyList())

    private val _displayedAuthorCount = MutableStateFlow(100)

    private val _expandedCategories = MutableStateFlow<Set<Int>>(emptySet())
    val expandedCategories: StateFlow<Set<Int>> = _expandedCategories

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _categoryLimits = MutableStateFlow<Map<Int, Int>>(emptyMap())

    private val _flatVisibleItems = MutableStateFlow<List<FlatLibraryItem>>(emptyList())
    val flatVisibleItems: StateFlow<List<FlatLibraryItem>> = _flatVisibleItems

    val isBulkDownloading: StateFlow<Boolean> = _activeDownloadStates.map { states ->
        states.any { it.isBulk && it.isDownloading }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var prefs: android.content.SharedPreferences? = null
    private val _showOnlyDownloaded = MutableStateFlow(false)
    val showOnlyDownloaded: StateFlow<Boolean> = _showOnlyDownloaded

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode

    private val _selectedBookIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedBookIds: StateFlow<Set<Int>> = _selectedBookIds

    fun loadData(context: Context) {
        if (_isDataLoaded.value) return
        if (prefs == null) {
            prefs = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
            _showOnlyDownloaded.value = prefs?.getBoolean("show_only_downloaded", false) ?: false
        }
        viewModelScope.launch {
            _isDataLoaded.value = false
            withContext(Dispatchers.IO) {
                dataManager.loadData()
            }
            _rootCategories.value = dataManager.allRootCategories
            _authorCategories.value = dataManager.buildAuthorHierarchy()
            updateFlatItems()
            _isDataLoaded.value = true
        }
        loadDownloadedBooks(context)
        loadDownloadIndex(context)
    }

    fun reloadData(context: Context) {
        viewModelScope.launch {
            _isDataLoaded.value = false
            withContext(Dispatchers.IO) { dataManager.loadData() }
            _rootCategories.value = dataManager.allRootCategories
            _authorCategories.value = dataManager.buildAuthorHierarchy()
            updateFlatItems()
            loadDownloadedBooks(context)
            _isDataLoaded.value = true
        }
        loadDownloadIndex(context)
    }

    fun loadDownloadIndex(context: Context) {
        viewModelScope.launch {
            if (downloadIndex == null) {
                try {
                    val manager = BookDownloadManager(context)
                    downloadIndex = manager.fetchIndex()
                    updateSelectionDownloadState(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }


    fun loadDownloadedBooks(context: Context) {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) {
                val downloadedIds = mutableSetOf<Int>()
                val files = context.filesDir.listFiles { _, name ->
                    name.endsWith(".sqlite") && name.matches(Regex("\\d+\\.sqlite"))
                }
                files?.forEach { file ->
                    try {
                        SQLiteDB(file.absolutePath, SQLiteDB.SQLITE_OPEN_READONLY).use { db ->
                            db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'b%';")
                                ?.use { stmt ->
                                    while (stmt.step() == SQLiteDB.SQLITE_ROW) {
                                        val tableName = stmt.columnText(0)
                                        if (tableName != null && tableName.startsWith("b")) {
                                            tableName.substring(1).toIntOrNull()?.let {
                                                downloadedIds.add(it)
                                            }
                                        }
                                    }
                                }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                downloadedIds
            }
            _downloadedBookIds.value = ids
            updateFlatItems()
        }
    }

    fun toggleCategory(categoryId: Int) {
        val current = _expandedCategories.value.toMutableSet()
        if (current.contains(categoryId)) {
            current.remove(categoryId)
            val limits = _categoryLimits.value.toMutableMap()
            limits.remove(categoryId)
            _categoryLimits.value = limits
        } else {
            current.add(categoryId)
        }
        _expandedCategories.value = current
        updateFlatItems()
    }

    fun loadMore(categoryId: Int) {
        val limits = _categoryLimits.value.toMutableMap()
        limits[categoryId] = (limits[categoryId] ?: 100) + 100
        _categoryLimits.value = limits
        updateFlatItems()
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _displayedAuthorCount.value = 100

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isNotEmpty()) {
                delay(500)
            }

            if (query.isNotEmpty()) {
                val matchingIds = mutableSetOf<Int>()
                val viewModeVal = _viewMode.value
                val roots =
                    if (viewModeVal == LibraryViewMode.AUTHOR) _authorCategories.value else _rootCategories.value
                val cleanQuery = query.normalizeArabic()

                withContext(Dispatchers.Default) {
                    fun findMatching(cats: List<Any>): Boolean {
						coroutineContext.ensureActive()
                        var matches = false
                        for (c in cats) {
                            if (c is BooksData) {
                                if (cleanQuery.isEmpty() || c.name.normalizeArabic()
                                        .contains(cleanQuery, ignoreCase = true)
                                ) {
                                    matches = true
                                }
                            } else if (c is CategoryData) {
                                val catMatches =
                                    c.name.normalizeArabic().contains(cleanQuery, ignoreCase = true)
                                val childrenMatch = findMatching(c.children)
                                if (catMatches || childrenMatch) {
                                    matchingIds.add(c.id)
                                    matches = true
                                }
                            }
                        }
                        return matches
                    }

                    findMatching(roots)
                }
                _expandedCategories.value = matchingIds
            } else {
                _expandedCategories.value = emptySet()
            }

            updateFlatItems()
        }
    }

    fun toggleShowOnlyDownloaded() {
        _showOnlyDownloaded.value = !_showOnlyDownloaded.value
        prefs?.edit { putBoolean("show_only_downloaded", _showOnlyDownloaded.value)?.apply() }
        _displayedAuthorCount.value = 100
        updateFlatItems()
    }

    fun setViewMode(mode: LibraryViewMode) {
        if (_viewMode.value != mode) {
            _viewMode.value = mode
            _expandedCategories.value = emptySet()
            _categoryLimits.value = emptyMap()
            _displayedAuthorCount.value = 100
            updateFlatItems()
        }
    }

    fun loadMoreAuthors() {
        val total = if (_searchQuery.value.isNotEmpty()) {
            10000
        } else {
            _authorCategories.value.size
        }
        _displayedAuthorCount.value = minOf(_displayedAuthorCount.value + 100, total)
        updateFlatItems()
    }

    fun toggleSelectionMode(context: Context? = null) {
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedBookIds.value = emptySet()
        }
        if (context != null) {
            updateSelectionDownloadState(context)
        } else {
            // Remove pending bulk confirmation
            _activeDownloadStates.value = _activeDownloadStates.value.filter { !(it.isBulk && !it.isDownloading) }
        }
    }

    fun toggleBookSelection(context: Context, bookId: Int) {
        val currentSet = _selectedBookIds.value.toMutableSet()
        if (currentSet.contains(bookId)) {
            currentSet.remove(bookId)
        } else {
            currentSet.add(bookId)
        }
        _selectedBookIds.value = currentSet
        updateSelectionDownloadState(context)
    }

    fun toggleCategorySelection(context: Context, category: CategoryData) {
        val currentSet = _selectedBookIds.value.toMutableSet()
        val showOnlyDownloadedVal = _showOnlyDownloaded.value
        val downloaded = _downloadedBookIds.value

        fun getAllBooks(cats: List<Any>): List<Int> {
            val list = mutableListOf<Int>()
            for (c in cats) {
                if (c is BooksData) {
                    if (!showOnlyDownloadedVal || downloaded.contains(c.id)) {
                        list.add(c.id)
                    }
                } else if (c is CategoryData) {
                    list.addAll(getAllBooks(c.children))
                }
            }
            return list
        }

        val allBooks = getAllBooks(category.children)
        if (allBooks.isEmpty()) return

        val allSelected = allBooks.all { currentSet.contains(it) }
        if (allSelected) {
            currentSet.removeAll(allBooks.toSet())
        } else {
            currentSet.addAll(allBooks)
        }
        _selectedBookIds.value = currentSet
        updateSelectionDownloadState(context)
    }

    fun updateSelectionDownloadState(context: Context) {
        val isSelection = _isSelectionMode.value
        val selected = _selectedBookIds.value
        val downloaded = _downloadedBookIds.value

        if (!isSelection || selected.isEmpty()) {
            _activeDownloadStates.value = _activeDownloadStates.value.filter { !(it.isBulk && !it.isDownloading) }
            return
        }

        // If currently downloading bulk, don't overwrite/add another bulk confirmation
        if (_activeDownloadStates.value.any { it.isBulk && it.isDownloading }) return

        val existingBulkState = _activeDownloadStates.value.find { it.isBulk && !it.isDownloading }
        val needDownload = selected.filter { !downloaded.contains(it) }

        // Compute total size
        var totalSize = 0L
        var hasSizes = false
        val index = downloadIndex
        if (index != null) {
            for (bookId in needDownload) {
                val entry = index.find { it.bkid == bookId }
                if (entry?.sizeZst != null) {
                    totalSize += entry.sizeZst
                    hasSizes = true
                }
            }
        }

        val sizeText = if (needDownload.isEmpty()) {
            context.getString(R.string.library_all_downloaded)
        } else if (hasSizes && totalSize > 0) {
            context.getString(R.string.format_mb, totalSize / (1024f * 1024f))
        } else {
            context.getString(R.string.library_unknown_size)
        }

        val newState = BookDownloadState(
            id = existingBulkState?.id ?: java.util.UUID.randomUUID().toString(),
            bookId = -1,
            bookName = context.getString(R.string.library_selected_books_count, selected.size),
            sizeText = sizeText,
            isDownloading = false,
            progress = 0,
            isBulk = true,
            bulkBookIds = needDownload
        )

        if (existingBulkState != null) {
            _activeDownloadStates.value = _activeDownloadStates.value.map {
                if (it.id == existingBulkState.id) newState else it
            }
        } else {
            _activeDownloadStates.value += newState
        }
    }

    private fun filterAuthorHierarchy(
        categories: List<CategoryData>,
        query: String
    ): List<CategoryData> {
        val cleanQuery = query.normalizeArabic()
        if (cleanQuery.isEmpty()) return categories

        val matchedAuthors = mutableListOf<CategoryData>()
        for (category in categories) {
            if (category.name.normalizeArabic().contains(cleanQuery, ignoreCase = true)) {
                matchedAuthors.add(category.copy(children = category.children.toMutableList()))
            }
        }

        if (matchedAuthors.isNotEmpty()) {
            return matchedAuthors
        }

        val result = mutableListOf<CategoryData>()
        for (category in categories) {
            val matchingBooks = category.children.filter { child ->
                if (child is BooksData) {
                    child.name.normalizeArabic().contains(cleanQuery, ignoreCase = true)
                } else false
            }

            if (matchingBooks.isNotEmpty()) {
                val cloned = category.copy(children = matchingBooks.toMutableList())
                result.add(cloned)
            }
        }

        return result
    }

    private var updateFlatItemsJob: kotlinx.coroutines.Job? = null
    private fun updateFlatItems() {
        updateFlatItemsJob?.cancel()
        updateFlatItemsJob = viewModelScope.launch(Dispatchers.Default) {
            val result = mutableListOf<FlatLibraryItem>()
            val expanded = _expandedCategories.value
            val viewModeVal = _viewMode.value
            var roots =
                if (viewModeVal == LibraryViewMode.AUTHOR) _authorCategories.value else _rootCategories.value
            val query = _searchQuery.value
            val cleanQuery = query.normalizeArabic()
            val showOnlyDownloaded = _showOnlyDownloaded.value
            val downloadedIds = _downloadedBookIds.value

            if (viewModeVal == LibraryViewMode.AUTHOR) {
                if (query.isNotEmpty()) {
                    roots = filterAuthorHierarchy(roots, query)
                }
                if (showOnlyDownloaded) {
                    roots = roots.mapNotNull { cat ->
                        val downloadedBooks = cat.children.filterIsInstance<BooksData>()
                            .filter { downloadedIds.contains(it.id) }
                        if (downloadedBooks.isNotEmpty()) {
                            cat.copy(children = downloadedBooks.toMutableList())
                        } else null
                    }
                }
            }

            fun hasMatchingBooks(cats: List<Any>): Boolean {
                for (c in cats) {
                    if (c is BooksData) {
                        val matchesQuery = cleanQuery.isEmpty() || c.name.normalizeArabic()
                            .contains(cleanQuery, ignoreCase = true)
                        val matchesDownloaded = !showOnlyDownloaded || downloadedIds.contains(c.id)
                        if (matchesQuery && matchesDownloaded) return true
                    } else if (c is CategoryData) {
                        if (hasMatchingBooks(c.children)) return true
                    }
                }
                return false
            }

            fun traverse(categories: List<Any>, level: Int, parentCategoryId: Int?): Boolean {
                var hasVisibleContent = false
                var bookCount = 0
                var hiddenCount = 0
                val limit = if (parentCategoryId != null) _categoryLimits.value[parentCategoryId]
                    ?: 100 else Int.MAX_VALUE

                for (item in categories) {
                    if (item is BooksData) {
                        val matchesQuery = if (viewModeVal == LibraryViewMode.AUTHOR) {
                            true
                        } else {
                            cleanQuery.isEmpty() || item.name.normalizeArabic()
                                .contains(cleanQuery, ignoreCase = true)
                        }
                        val matchesDownloaded =
                            !showOnlyDownloaded || downloadedIds.contains(item.id)

                        if (matchesQuery && matchesDownloaded) {
                            if (bookCount >= limit) {
                                hiddenCount++
                            } else {
                                result.add(FlatLibraryItem(item, level, isDownloaded = downloadedIds.contains(item.id)))
                                hasVisibleContent = true
                                bookCount++
                            }
                        }
                    } else if (item is CategoryData) {
                        val isSearching =
                            if (viewModeVal == LibraryViewMode.AUTHOR) false else query.isNotEmpty()
                        val isCategoryVisible = if (isSearching) {
                            hasMatchingBooks(item.children)
                        } else {
                            true
                        }

                        if (isCategoryVisible) {
                            val categoryIndex = result.size
                            result.add(FlatLibraryItem(item, level))

                            val isExpanded = expanded.contains(item.id)

                            val childrenHasVisible = if (isExpanded) {
                                traverse(item.children, level + 1, item.id)
                            } else {
                                if (showOnlyDownloaded) {
                                    fun checkHasDownloaded(cats: List<Any>): Boolean {
                                        for (c in cats) {
                                            if (c is BooksData && downloadedIds.contains(c.id)) return true
                                            if (c is CategoryData && checkHasDownloaded(c.children)) return true
                                        }
                                        return false
                                    }
                                    checkHasDownloaded(item.children)
                                } else {
                                    true
                                }
                            }

                            if (showOnlyDownloaded && !childrenHasVisible) {
                                result.removeAt(categoryIndex)
                            } else {
                                hasVisibleContent = true
                            }
                        }
                    }
                }

                if (hiddenCount > 0 && parentCategoryId != null) {
                    result.add(FlatLibraryItem(LoadMoreData(parentCategoryId, hiddenCount), level))
                }

                return hasVisibleContent
            }

            val totalRootsCount = roots.size
            val displayedCount = _displayedAuthorCount.value
            var hasMore = false
            var remainingCount = 0
            if (viewModeVal == LibraryViewMode.AUTHOR) {
                if (totalRootsCount > displayedCount) {
                    hasMore = true
                    remainingCount = totalRootsCount - displayedCount
                    roots = roots.take(displayedCount)
                }
            }

            traverse(roots, 0, null)

            if (viewModeVal == LibraryViewMode.AUTHOR && hasMore) {
                result.add(FlatLibraryItem(LoadMoreAuthors(remainingCount), 0))
            }

            _flatVisibleItems.value = result
        }
    }

    fun isBookDownloadedById(bookId: Int): Boolean {
        return _downloadedBookIds.value.contains(bookId)
    }

    fun markBookAsDownloaded(bookId: Int) {
        val current = _downloadedBookIds.value.toMutableSet()
        current.add(bookId)
        _downloadedBookIds.value = current
        updateFlatItems()
    }

    fun deleteSelectedBooks(
        context: Context,
        tabManager: ReaderTabManager? = null,
        onResult: (String) -> Unit
    ) {
        val selected = _selectedBookIds.value.toList()
        if (selected.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            var deletedCount = 0
            val deletedBookIds = mutableListOf<Int>()
            for (bookId in selected) {
                if (_downloadedBookIds.value.contains(bookId)) {
                    val archiveId = dataManager.booksById[bookId]?.archive
                    if (archiveId != null) {
                        val file = File(context.filesDir, "${archiveId}.sqlite")
                        if (file.exists() && file.delete()) {
                            deletedCount++
                            deletedBookIds.add(bookId)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                if (deletedCount > 0) {
                    tabManager?.closeTabsForBooks(deletedBookIds)
                    loadDownloadedBooks(context)
                    _isSelectionMode.value = false
                    _selectedBookIds.value = emptySet()
                    updateSelectionDownloadState(context)
                    onResult(context.getString(R.string.library_delete_success, deletedCount))
                } else {
                    onResult(context.getString(R.string.library_delete_no_result))
                }
            }
        }
    }

    fun showDownloadConfirmation(
        context: Context,
        bookId: Int,
        contentId: Int? = null,
        loc: Int? = null,
        len: Int? = null,
        query: String? = null
    ) {
        // Prevent duplicate confirmation for the same book
        if (_activeDownloadStates.value.any { it.bookId == bookId && !it.isBulk }) return

        viewModelScope.launch {
            val bookName = dataManager.booksById[bookId]?.name
                ?: context.getString(R.string.library_fallback_book_name)
            val manager = BookDownloadManager(context)
            val index = manager.fetchIndex()
            val entry = index.find { it.bkid == bookId }
            val sizeText = entry?.sizeZst?.let { size ->
                context.getString(R.string.format_mb, size / (1024f * 1024f))
            } ?: context.getString(R.string.library_unknown_size)

            val newState = BookDownloadState(
                bookId = bookId,
                bookName = bookName,
                sizeText = sizeText,
                contentId = contentId,
                loc = loc,
                len = len,
                query = query,
                isDownloading = false,
                progress = 0
            )
            _activeDownloadStates.value += newState
        }
    }

    fun cancelDownload(id: String) {
        _activeDownloadStates.value = _activeDownloadStates.value.filter { it.id != id }
    }

    fun confirmDownload(
        context: Context,
        stateId: String,
        onComplete: (Int, Int?, Int?, Int?, String?) -> Unit
    ) {
        val currentState = _activeDownloadStates.value.find { it.id == stateId } ?: return

        // Update state to downloading
        _activeDownloadStates.value = _activeDownloadStates.value.map {
            if (it.id == stateId) it.copy(isDownloading = true, progress = 0) else it
        }

        if (currentState.isBulk) {
            viewModelScope.launch {
                val manager = BookDownloadManager(context)
                val index = manager.fetchIndex()
                val toDownload = currentState.bulkBookIds
                var successCount = 0

                for ((idx, bookId) in toDownload.withIndex()) {
                    val entry = index.find { it.bkid == bookId }
                    if (entry != null) {
                        _activeDownloadStates.value = _activeDownloadStates.value.map {
                            if (it.id == stateId) it.copy(progress = (idx * 100 / toDownload.size)) else it
                        }

                        val success = manager.downloadBook(entry, onPhaseChanged = { phase ->
                            _activeDownloadStates.value = _activeDownloadStates.value.map {
                                if (it.id == stateId) it.copy(phase = phase) else it
                            }
                        }) { progress ->
                            val overallProgress = (idx * 100 + progress) / toDownload.size
                            _activeDownloadStates.value = _activeDownloadStates.value.map {
                                if (it.id == stateId) it.copy(progress = overallProgress) else it
                            }
                        }
                        if (success) {
                            markBookAsDownloaded(bookId)
                            successCount++
                        }
                    }
                }

                _activeDownloadStates.value = _activeDownloadStates.value.filter { it.id != stateId }
                _isSelectionMode.value = false
                _selectedBookIds.value = emptySet()
            }
        } else {
            viewModelScope.launch {
                val manager = BookDownloadManager(context)
                val index = manager.fetchIndex()
                val entry = index.find { it.bkid == currentState.bookId }
                if (entry != null) {
                    val success = manager.downloadBook(entry, onPhaseChanged = { phase ->
                        _activeDownloadStates.value = _activeDownloadStates.value.map {
                            if (it.id == stateId) it.copy(phase = phase) else it
                        }
                    }) { progress ->
                        _activeDownloadStates.value = _activeDownloadStates.value.map {
                            if (it.id == stateId) it.copy(progress = progress) else it
                        }
                    }
                    if (success) {
                        markBookAsDownloaded(currentState.bookId)
                        _activeDownloadStates.value = _activeDownloadStates.value.filter { it.id != stateId }
                        onComplete(
                            currentState.bookId,
                            currentState.contentId,
                            currentState.loc,
                            currentState.len,
                            currentState.query
                        )
                    } else {
                        _activeDownloadStates.value = _activeDownloadStates.value.map {
                            if (it.id == stateId) it.copy(
                                isDownloading = false,
                                error = context.getString(R.string.library_download_failed)
                            ) else it
                        }
                    }
                } else {
                    _activeDownloadStates.value = _activeDownloadStates.value.map {
                        if (it.id == stateId) it.copy(
                            isDownloading = false,
                            error = context.getString(R.string.library_book_not_found_index)
                        ) else it
                    }
                }
            }
        }
    }
}
