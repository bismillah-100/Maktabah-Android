package com.maktabah.ui.search

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.R
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.BooksData
import com.maktabah.models.CategoryData
import com.maktabah.models.FlatLibraryItem
import com.maktabah.models.LoadMoreData
import com.maktabah.models.SavedResultsItem
import com.maktabah.models.SearchMode
import com.maktabah.models.SearchResult
import com.maktabah.search.SearchEngine
import com.maktabah.utils.convertToArabicDigits
import com.maktabah.utils.normalizeArabic
import com.maktabah.utils.snippetAround
import com.maktabah.utils.snippetNear
import com.maktabah.utils.stripSpanTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

class SearchViewModel : ViewModel() {
    private val searchEngine = SearchEngine()

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _filteredSearchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val filteredSearchResults: StateFlow<List<SearchResult>> = _filteredSearchResults.asStateFlow()

    private val _bookFilter = MutableStateFlow("")
    val bookFilter: StateFlow<String> = _bookFilter.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // ----------------------------------------------------
    // Book Selection Tree-View States (Downloaded books only)
    // ----------------------------------------------------
    private val _downloadedBookIds = MutableStateFlow<Set<Int>>(emptySet())
    private val _selectedBookIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedBookIds: StateFlow<Set<Int>> = _selectedBookIds.asStateFlow()

    private val _expandedCategories = MutableStateFlow<Set<Int>>(emptySet())
    val expandedCategories: StateFlow<Set<Int>> = _expandedCategories.asStateFlow()

    private val _flatVisibleItems = MutableStateFlow<List<FlatLibraryItem>>(emptyList())
    val flatVisibleItems: StateFlow<List<FlatLibraryItem>> = _flatVisibleItems.asStateFlow()

    private val _isTreeLoaded = MutableStateFlow(false)
    val isTreeLoaded: StateFlow<Boolean> = _isTreeLoaded.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _categoryLimits = MutableStateFlow<Map<Int, Int>>(emptyMap())

    // ----------------------------------------------------
    // Search Progress States
    // ----------------------------------------------------
    private val _completedBooks = MutableStateFlow(0)
    val completedBooks: StateFlow<Int> = _completedBooks.asStateFlow()

    private val _totalBooks = MutableStateFlow(0)
    val totalBooks: StateFlow<Int> = _totalBooks.asStateFlow()

    private val _currentBookProgress =
        MutableStateFlow<Pair<Int, Int>?>(null) // Pair(current, total)
    val currentBookProgress: StateFlow<Pair<Int, Int>?> = _currentBookProgress.asStateFlow()

    private val _currentBookName = MutableStateFlow("")
    val currentBookName: StateFlow<String> = _currentBookName.asStateFlow()

    private val _lastSearchQuery = MutableStateFlow("")
    val lastSearchQuery: StateFlow<String> = _lastSearchQuery.asStateFlow()

    private val _lastSearchMode = MutableStateFlow(SearchMode.PHRASE)
    val lastSearchMode: StateFlow<SearchMode> = _lastSearchMode.asStateFlow()

    private val _showSavedResults = MutableStateFlow(false)
    val showSavedResults: StateFlow<Boolean> = _showSavedResults.asStateFlow()

    private val _searchHistory = MutableStateFlow<List<String>>(emptyList())
    val searchHistory: StateFlow<List<String>> = _searchHistory.asStateFlow()

    private val _nearDistance = MutableStateFlow(10)
    val nearDistance: StateFlow<Int> = _nearDistance.asStateFlow()

    fun updateNearDistance(distance: Int, context: Context? = null) {
        _nearDistance.value = distance
        if (context != null && distance > 0) {
            val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
            prefs.edit { putInt("searchNearDistance", distance).apply() }
        }
    }

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        val distance = prefs.getInt("searchNearDistance", 10)
        _nearDistance.value = if (distance <= 0) 10 else distance
    }

    private var isInitialized = false

    fun initialize(
        context: Context,
        dataManager: LibraryDataManager,
        downloadedBookIdsFlow: StateFlow<Set<Int>>
    ) {
        if (isInitialized) return
        isInitialized = true
        loadHistory(context)
        loadPreferences(context)

        viewModelScope.launch {
            downloadedBookIdsFlow.collect { ids ->
                setDownloadedBookIds(ids, dataManager)
            }
        }
    }

    fun setDownloadedBookIds(ids: Set<Int>, dataManager: LibraryDataManager) {
        if (_downloadedBookIds.value != ids) {
            _downloadedBookIds.value = ids
            val currentSelected = _selectedBookIds.value
            val newSelected = currentSelected.intersect(ids)
            if (newSelected.size != currentSelected.size) {
                _selectedBookIds.value = newSelected
            }
            updateFlatItems(dataManager)
        }
    }


    fun loadHistory(context: Context) {
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        val historyString = prefs.getString("search_history_list", "") ?: ""
        if (historyString.isNotEmpty()) {
            _searchHistory.value = historyString.split("\n").filter { it.isNotEmpty() }
        } else {
            _searchHistory.value = emptyList()
        }
    }

    fun addToHistory(context: Context, query: String) {
        if (query.isBlank()) return
        val current = _searchHistory.value.toMutableList()
        current.remove(query)
        current.add(0, query)
        if (current.size > 20) {
            current.removeAt(current.lastIndex)
        }
        _searchHistory.value = current
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("search_history_list", current.joinToString("\n")) }
    }

    fun removeFromHistory(context: Context, query: String) {
        val current = _searchHistory.value.toMutableList()
        current.remove(query)
        _searchHistory.value = current
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        prefs.edit { putString("search_history_list", current.joinToString("\n")) }
    }

    fun clearHistory(context: Context) {
        _searchHistory.value = emptyList()
        val prefs = context.getSharedPreferences("search_prefs", Context.MODE_PRIVATE)
        prefs.edit { remove("search_history_list") }
    }


    fun toggleCategory(categoryId: Int, dataManager: LibraryDataManager) {
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
        updateFlatItems(dataManager)
    }

    fun loadMore(categoryId: Int, dataManager: LibraryDataManager) {
        val limits = _categoryLimits.value.toMutableMap()
        limits[categoryId] = (limits[categoryId] ?: 100) + 100
        _categoryLimits.value = limits
        updateFlatItems(dataManager)
    }

    private var searchJob: kotlinx.coroutines.Job? = null

    fun updateSearchQuery(query: String, dataManager: LibraryDataManager) {
        _searchQuery.value = query

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isNotEmpty()) {
                delay(500.milliseconds)
            }

            if (query.isNotEmpty()) {
                val matchingIds = mutableSetOf<Int>()
                val roots = dataManager.allRootCategories
                val cleanQuery = query.normalizeArabic()

                withContext(Dispatchers.Default) {
                    fun findMatching(cats: List<Any>): Boolean {
                        coroutineContext.ensureActive()
                        var matches = false
                        for (c in cats) {
                            if (c is BooksData) {
                                if (c.name.matchesQuery(cleanQuery)) {
                                    matches = true
                                }
                            } else if (c is CategoryData) {
                                val catMatches = c.name.matchesQuery(cleanQuery)
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

            updateFlatItems(dataManager)
        }
    }

    fun toggleBookSelection(bookId: Int) {
        val currentSet = _selectedBookIds.value.toMutableSet()
        if (currentSet.contains(bookId)) {
            currentSet.remove(bookId)
        } else {
            currentSet.add(bookId)
        }
        _selectedBookIds.value = currentSet
    }

    fun toggleCategorySelection(category: CategoryData) {
        val currentSet = _selectedBookIds.value.toMutableSet()
        val downloadedIds = _downloadedBookIds.value

        fun getAllDownloadedBooks(cats: List<Any>): List<Int> {
            val list = mutableListOf<Int>()
            for (c in cats) {
                if (c is BooksData) {
                    if (downloadedIds.contains(c.id)) {
                        list.add(c.id)
                    }
                } else if (c is CategoryData) {
                    list.addAll(getAllDownloadedBooks(c.children))
                }
            }
            return list
        }

        val allBooks = getAllDownloadedBooks(category.children)
        if (allBooks.isEmpty()) return

        val allSelected = allBooks.all { currentSet.contains(it) }
        if (allSelected) {
            currentSet.removeAll(allBooks.toSet())
        } else {
            currentSet.addAll(allBooks)
        }
        _selectedBookIds.value = currentSet
    }

    fun clearSelection() {
        _selectedBookIds.value = emptySet()
    }

    fun setShowSavedResults(show: Boolean) {
        _showSavedResults.value = show
    }

    fun clearResults() {
        _searchResults.value = emptyList()
        _bookFilter.value = ""
        _filteredSearchResults.value = emptyList()
        _completedBooks.value = 0
        _totalBooks.value = 0
        _currentBookProgress.value = null
        _currentBookName.value = ""
        _lastSearchQuery.value = ""
    }

    fun refreshData(dataManager: LibraryDataManager) {
        updateFlatItems(dataManager)
    }

    private var updateFlatItemsJob: kotlinx.coroutines.Job? = null
    private fun updateFlatItems(dataManager: LibraryDataManager) {
        updateFlatItemsJob?.cancel()
        updateFlatItemsJob = viewModelScope.launch(Dispatchers.Default) {
            val result = mutableListOf<FlatLibraryItem>()
            val expanded = _expandedCategories.value
            val roots = dataManager.allRootCategories
            val downloadedIds = _downloadedBookIds.value
            val query = _searchQuery.value
            val cleanQuery = query.normalizeArabic()

            fun hasMatchingBooks(cats: List<Any>): Boolean {
                for (c in cats) {
                    if (c is BooksData) {
                        val matchesQuery = c.name.matchesQuery(cleanQuery)
                        val matchesDownloaded = downloadedIds.contains(c.id)
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
                        val matchesQuery = item.name.matchesQuery(cleanQuery)
                        val matchesDownloaded = downloadedIds.contains(item.id)

                        if (matchesQuery && matchesDownloaded) {
                            if (bookCount >= limit) {
                                hiddenCount++
                            } else {
                                result.add(FlatLibraryItem(item, level))
                                hasVisibleContent = true
                                bookCount++
                            }
                        }
                    } else if (item is CategoryData) {
                        val isSearching = query.isNotEmpty()
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
                                fun checkHasDownloaded(cats: List<Any>): Boolean {
                                    for (c in cats) {
                                        if (c is BooksData && downloadedIds.contains(c.id)) return true
                                        if (c is CategoryData && checkHasDownloaded(c.children)) return true
                                    }
                                    return false
                                }
                                checkHasDownloaded(item.children)
                            }

                            if (!childrenHasVisible) {
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

            traverse(roots, 0, null)
            _flatVisibleItems.value = result
            _isTreeLoaded.value = true
        }
    }

    fun performSearch(
        context: Context,
        query: String,
        mode: SearchMode,
        dataManager: LibraryDataManager
    ) {
        val selectedIds = _selectedBookIds.value.ifEmpty { _downloadedBookIds.value }
        if (query.isBlank() || selectedIds.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }

        addToHistory(context, query)

        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = emptyList()
            _completedBooks.value = 0
            _totalBooks.value = selectedIds.size
            _currentBookProgress.value = null
            _lastSearchQuery.value = query
            _lastSearchMode.value = mode

            val searchKeywords = when (mode) {
                SearchMode.PHRASE -> {
                    val normalized = query.normalizeArabic()
                    if (normalized.isBlank()) emptyList() else listOf(normalized)
                }

                else -> {
                    query.normalizeArabic().split(",").map { it.trim() }.filter { it.isNotBlank() }
                }
            }.map { it.convertToArabicDigits() }

            val allResults = mutableListOf<SearchResult>()
            var processed = 0
            val filesDir = context.filesDir

            for (bookId in selectedIds) {
                val book = dataManager.booksById[bookId] ?: continue
                _currentBookName.value = book.name
                _currentBookProgress.value = null

                val archiveFile = File(filesDir, "${book.archive}.sqlite")
                val ftsFile = File(filesDir, "${book.archive}_fts.sqlite")

                if (archiveFile.exists() && ftsFile.exists()) {
                    try {
                        val effectiveDistance = if (_nearDistance.value <= 0) 10 else _nearDistance.value
                        val bookResults = searchEngine.searchInBook(
                            bookId = book.id,
                            archiveFile = archiveFile,
                            archiveFtsFile = ftsFile,
                            query = query,
                            mode = mode,
                            nearDistance = effectiveDistance,
                            limit = 50, // Limit per book to ensure UI speed
                            onRowProgress = { current, total ->
                                if (current % 10 == 0 || current == total) {
                                    _currentBookProgress.value = Pair(current, total)
                                }
                            }
                        )

                        val mapped = withContext(Dispatchers.Default) {
                            bookResults.map {
                                val stripped = it.nass.stripSpanTags()
                                val normalized = stripped.convertToArabicDigits()
                                val snippet = if (mode == SearchMode.NEAR) {
                                    normalized.snippetNear(searchKeywords, effectiveDistance, contextLength = 60)
                                } else {
                                    normalized.snippetAround(searchKeywords, contextLength = 60)
                                }

                                SearchResult(
                                    bookId = bookId,
                                    contentId = it.id,
                                    text = snippet,
                                    page = it.page,
                                    part = it.part
                                )
                            }
                        }
                        allResults.addAll(mapped)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                processed++
                _completedBooks.value = processed
            }

            _searchResults.value = allResults
            _bookFilter.value = ""
            _filteredSearchResults.value = allResults
            _isSearching.value = false
            _currentBookProgress.value = null
            _currentBookName.value = ""

            if (allResults.isEmpty()) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.reader_toc_no_results),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun loadSavedResults(items: List<SavedResultsItem>, context: Context, dataManager: LibraryDataManager) {
        val firstItem = items.firstOrNull() ?: return
        _lastSearchQuery.value = firstItem.query
        _lastSearchMode.value = SearchMode.entries.getOrElse(firstItem.searchMode) { SearchMode.PHRASE }
        _nearDistance.value = firstItem.nearDistance

        viewModelScope.launch {
            _isSearching.value = true
            _searchResults.value = emptyList()
            _completedBooks.value = 0

            val groupedByArchive = items.groupBy { it.archive }
            _totalBooks.value = groupedByArchive.size
            _currentBookProgress.value = null

            val allResults = mutableListOf<SearchResult>()
            var processed = 0

            withContext(Dispatchers.IO) {
                val zstdCtx = com.maktabah.database.ZstdContextPool.getDecompressCtx()
                try {
                    for ((archiveId, groupItems) in groupedByArchive) {
                        val archiveFile = File(context.filesDir, "$archiveId.sqlite")
                    if (!archiveFile.exists()) continue

                    try {
                        com.maktabah.database.SQLiteDB(
                            archiveFile.absolutePath,
                            com.maktabah.database.SQLiteDB.SQLITE_OPEN_READONLY or com.maktabah.database.SQLiteDB.SQLITE_OPEN_FULLMUTEX
                        ).use { db ->
                            val itemsByBook = groupItems.groupBy { it.tableName.toIntOrNull() }.mapNotNull { (k, v) -> k?.let { it to v } }
                            for ((bkId, bookItems) in itemsByBook) {
                                val book = dataManager.booksById[bkId]
                                val currentName = book?.name ?: bookItems.firstOrNull()?.bookTitle ?: ""
                                withContext(Dispatchers.Main) {
                                    _currentBookName.value = currentName
                                }

                                try {
                                    bookItems.chunked(900).forEach { chunk ->
                                        val placeholders = chunk.joinToString(",") { "?" }
                                        db.prepare("SELECT id, nass, page, part FROM b$bkId WHERE id IN ($placeholders)")
                                            ?.use { stmt ->
                                                chunk.forEachIndexed { index, item ->
                                                    stmt.bindLong(index + 1, item.bookId.toLong())
                                                }

                                                val itemsById = chunk.groupBy { it.bookId }

                                                while (stmt.step() == com.maktabah.database.SQLiteDB.SQLITE_ROW) {
                                                    val contentId = stmt.columnInt(0)
                                                    val nassBlob = stmt.columnBlobDirect(1)
                                                    val page = stmt.columnInt(2)
                                                    val part = stmt.columnInt(3)

                                                if (nassBlob != null) {
                                                    val nassString = com.maktabah.database.decompressBlob(nassBlob, zstdCtx)
                                                    val stripped = nassString.stripSpanTags()
                                                    val normalized = stripped.normalizeArabic().convertToArabicDigits()

                                                    itemsById[contentId]?.forEach { item ->
                                                        val queryConverted = item.query.normalizeArabic().convertToArabicDigits()
                                                        val searchKeywords = queryConverted
                                                            .split(",")
                                                            .map { it.trim() }
                                                            .filter { it.isNotEmpty() }
                                                            .map { it.trim() }
                                                            .filter { it.isNotEmpty() }
                                                        val effectiveDistance = if (_nearDistance.value <= 0) 10 else _nearDistance.value
                                                        val snippet = if (_lastSearchMode.value == SearchMode.NEAR) {
                                                            normalized.snippetNear(searchKeywords, effectiveDistance, contextLength = 60)
                                                        } else {
                                                            normalized.snippetAround(searchKeywords, contextLength = 60)
                                                        }

                                                        allResults.add(
                                                            SearchResult(
                                                                bookId = bkId,
                                                                contentId = contentId,
                                                                text = snippet,
                                                                page = page,
                                                                part = part
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Skip missing tables or bad rows
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    processed++
                    _completedBooks.value = processed
                }
                } finally {
                    com.maktabah.database.ZstdContextPool.releaseDecompressCtx(zstdCtx)
                }
            }

            _searchResults.value = allResults
            _bookFilter.value = ""
            _filteredSearchResults.value = allResults
            _isSearching.value = false
            _currentBookProgress.value = null
            _currentBookName.value = ""
        }
    }

    fun updateBookFilter(query: String, dataManager: LibraryDataManager) {
        _bookFilter.value = query
        applyBookFilter(dataManager)
    }

    private fun String.matchesQuery(cleanQuery: String): Boolean {
        if (cleanQuery.isEmpty()) return true
        return this.normalizeArabic().contains(cleanQuery, ignoreCase = true)
    }

    private var filterJob: kotlinx.coroutines.Job? = null
    private fun applyBookFilter(dataManager: LibraryDataManager) {
        val currentFilter = _bookFilter.value
        val allResults = _searchResults.value
        val booksMap = dataManager.booksById // Capture thread-safe reference

        filterJob?.cancel()

        if (currentFilter.isEmpty()) {
            _filteredSearchResults.value = allResults
        } else {
            filterJob = viewModelScope.launch(Dispatchers.Default) {
                delay(500.milliseconds) // UI Debounce
                val cleanQuery = currentFilter.normalizeArabic()
                if (cleanQuery.isEmpty()) {
                    _filteredSearchResults.value = allResults
                    return@launch
                }
                val normalizedNames = mutableMapOf<Int, String>()
                val filtered = allResults.filter { result ->
                    ensureActive() // Cooperative cancellation
                    val normalizedName = normalizedNames.getOrPut(result.bookId) {
                        booksMap[result.bookId]?.name?.normalizeArabic() ?: ""
                    }
                    cleanQuery.isEmpty() || normalizedName.contains(cleanQuery, ignoreCase = true)
                }
                _filteredSearchResults.value = filtered
            }
        }
    }
}
