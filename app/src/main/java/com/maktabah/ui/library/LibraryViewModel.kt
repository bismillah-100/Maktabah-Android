package com.maktabah.ui.library

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.R
import com.maktabah.config.AppConfig
import com.maktabah.database.BookArchiveIntegrator
import com.maktabah.database.BookImportManager
import com.maktabah.database.SQLiteDB
import com.maktabah.database.updater.BookUpdateCheckWorker
import com.maktabah.database.updater.BookUpdateRepository
import com.maktabah.downloader.BookDownloadManager
import com.maktabah.downloader.BookDownloadNotifier
import com.maktabah.downloader.BookDownloadService
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.BookDownloadState
import com.maktabah.models.BooksData
import com.maktabah.models.BundleBookIndexEntry
import com.maktabah.models.CategoryData
import com.maktabah.models.FlatLibraryItem
import com.maktabah.models.LibraryViewMode
import com.maktabah.models.LoadMoreAuthors
import com.maktabah.models.LoadMoreData
import com.maktabah.ui.common.UiText
import com.maktabah.ui.reader.ReaderTabManager
import com.maktabah.utils.normalizeArabic
import com.maktabah.widget.DashboardWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private val SQLITE_FILE_REGEX = Regex("\\d+\\.sqlite")

class LibraryViewModel(
    appContext: Context,
    val dataManager: LibraryDataManager,
    val updateRepository: BookUpdateRepository? = null,
) : ViewModel() {
    private val appContext: Context = appContext.applicationContext

    data class DeleteSelectionSummary(
        val customBookIds: List<Int>,
        val downloadedCoreBookIds: List<Int>,
        val skippedDownloadingBookIds: List<Int>,
    ) {
        val totalDeletableCount: Int
            get() = customBookIds.size + downloadedCoreBookIds.size
    }

    private val _downloadedBookIds = MutableStateFlow<Set<Int>>(emptySet())
    val downloadedBookIds: StateFlow<Set<Int>> = _downloadedBookIds

    private val _customBookIds = MutableStateFlow<Set<Int>>(emptySet())
    val customBookIds: StateFlow<Set<Int>> = _customBookIds

    val activeDownloadStates: StateFlow<List<BookDownloadState>> = BookDownloadService.activeDownloadStates

    init {
        viewModelScope.launch {
            BookDownloadNotifier.downloadedBookEvents.collect { bookId ->
                markBookAsDownloaded(bookId)
            }
        }
    }

    @Volatile
    private var downloadIndex: List<BundleBookIndexEntry>? = null

    @Volatile
    private var coreBookIds: Set<Int>? = null

    @Volatile
    private var coreAuthorIds: Set<Int>? = null

    private val _isDataLoaded = MutableStateFlow(false)
    val isDataLoaded: StateFlow<Boolean> = _isDataLoaded

    private val _catalogRevision = MutableStateFlow(0L)
    val catalogRevision: StateFlow<Long> = _catalogRevision

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

    val isBulkDownloading: StateFlow<Boolean> = activeDownloadStates.map { states ->
        states.any { it.isBulk && it.isDownloading }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val prefs by lazy {
        this.appContext.getSharedPreferences(AppConfig.Prefs.LIBRARY_PREFS, Context.MODE_PRIVATE)
    }
    private val _showOnlyDownloaded = MutableStateFlow(false)
    val showOnlyDownloaded: StateFlow<Boolean> = _showOnlyDownloaded

    private val _availableUpdateCount = MutableStateFlow(0)
    val availableUpdateCount: StateFlow<Int> = _availableUpdateCount

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode

    private val _selectedBookIds = MutableStateFlow<Set<Int>>(emptySet())
    val selectedBookIds: StateFlow<Set<Int>> = _selectedBookIds

    private val _pendingDeleteSummary = MutableStateFlow<DeleteSelectionSummary?>(null)
    val pendingDeleteSummary: StateFlow<DeleteSelectionSummary?> = _pendingDeleteSummary

    private val _isDeleting = MutableStateFlow(false)
    val isDeleting: StateFlow<Boolean> = _isDeleting

    private val _deleteMessages = Channel<UiText>(Channel.BUFFERED)
    val deleteMessages: Flow<UiText> = _deleteMessages.receiveAsFlow()

    private val catalogMutex = Mutex()
    private val downloadCompletionJobs = mutableMapOf<String, Job>()

    fun loadData() {
        if (_isDataLoaded.value) return
        _showOnlyDownloaded.value = prefs.getBoolean("show_only_downloaded", false)
        viewModelScope.launch {
            refreshCatalogFromDisk(initialLoad = true)
        }
        BookUpdateCheckWorker.enqueuePeriodicCheck(appContext)
        checkBookUpdatesPeriodically(force = false)
    }

    fun getBookFlow(bookId: Int): Flow<BooksData?> {
        return _catalogRevision.map { dataManager.booksById[bookId] }
    }

    fun reloadData() {
        viewModelScope.launch {
            refreshCatalogFromDisk(initialLoad = false)
        }
        checkBookUpdatesPeriodically(force = true)
    }

    private suspend fun refreshCatalogFromDisk(initialLoad: Boolean) {
        var didLoad = false
        catalogMutex.withLock {
            if (initialLoad && _isDataLoaded.value) return@withLock
            try {
                withContext(Dispatchers.IO) {
                    dataManager.loadData()
                    loadLocalIndexIfPresent()
                }
                val authors = withContext(Dispatchers.Default) {
                    dataManager.buildAuthorHierarchy()
                }
                val downloadedIds = scanDownloadedBookIds()
                _rootCategories.value = dataManager.allRootCategories
                _authorCategories.value = authors
                _downloadedBookIds.value = downloadedIds
                _customBookIds.value = computeCustomBookIds()
                updateFlatItems()
                _catalogRevision.value += 1
                didLoad = true
            } finally {
                if (initialLoad) {
                    _isDataLoaded.value = true
                }
            }
        }
        if (didLoad) {
            updateSelectionDownloadState()
            if (downloadIndex == null) {
                loadDownloadIndex()
            }
        }
    }

    fun checkBookUpdatesPeriodically(force: Boolean = false) {
        val repo = updateRepository ?: BookUpdateRepository(appContext)
        val lastCheck = repo.getLastUpdateCheck()
        val oneDayMs = 86_400_000L
        val now = System.currentTimeMillis()

        if (!force && now - lastCheck < oneDayMs) {
            _availableUpdateCount.value = repo.getCachedUpdateCount()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = repo.fetchAvailableUpdates()
                val count = items.count { it.needsUpdate }
                repo.saveUpdateCheck(now, count)
                withContext(Dispatchers.Main) {
                    _availableUpdateCount.value = count
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Ignore if offline during background check
            }
        }
    }

    private fun loadDownloadIndex() {
        viewModelScope.launch {
            if (downloadIndex == null) {
                populateDownloadIndex()
                if (_isDataLoaded.value) {
                    _customBookIds.value = computeCustomBookIds()
                    updateSelectionDownloadState()
                }
            }
        }
    }

    private suspend fun loadLocalIndexIfPresent() {
        if (downloadIndex != null && coreBookIds != null) return
        if (!java.io.File(appContext.filesDir, "index.json").exists()) return
        populateDownloadIndex()
    }

    private suspend fun populateDownloadIndex() {
        try {
            val entries = BookDownloadManager(appContext).fetchIndex()
            if (entries.isNotEmpty()) {
                val (parsedCoreBooks, parsedCoreAuthors) = deriveCoreCatalogSets(entries)
                coreBookIds = parsedCoreBooks
                coreAuthorIds = parsedCoreAuthors
                downloadIndex = entries
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LibraryViewModel", "Failed to load index.json", e)
        }
    }

    private fun computeCustomBookIds(): Set<Int> {
        return computeCustomBookIds(dataManager.booksById.keys, coreBookIds)
    }

    private suspend fun scanDownloadedBookIds(): Set<Int> = withContext(Dispatchers.IO) {
        val downloadedIds = HashSet<Int>()
        val files = appContext.filesDir.listFiles { _, name ->
            name.endsWith(".sqlite") && name.matches(SQLITE_FILE_REGEX)
        }
        files?.forEach { file ->
            coroutineContext.ensureActive()
            try {
                SQLiteDB(
                    file.absolutePath,
                    SQLiteDB.SQLITE_OPEN_READONLY or SQLiteDB.SQLITE_OPEN_FULLMUTEX,
                ).use { db ->
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
                delay(500.milliseconds)
            }

            if (query.isNotEmpty()) {
                val matchingIds = mutableSetOf<Int>()
                val viewModeVal = _viewMode.value
                val roots =
                    if (viewModeVal == LibraryViewMode.AUTHOR) _authorCategories.value else _rootCategories.value
                val cleanQuery = query.normalizeArabic()
                val normalizedNames = dataManager.normalizedNamesById

                withContext(Dispatchers.Default) {
                    fun findMatching(cats: List<Any>): Boolean {
						coroutineContext.ensureActive()
                        var matches = false
                        for (c in cats) {
                            if (c is BooksData) {
                                if (dataManager.bookContainsNormalizedQuery(c, cleanQuery, normalizedNames)) {
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
        prefs.edit { putBoolean("show_only_downloaded", _showOnlyDownloaded.value) }
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

    fun toggleSelectionMode() {
        if (_isDeleting.value) return
        _isSelectionMode.value = !_isSelectionMode.value
        if (!_isSelectionMode.value) {
            _selectedBookIds.value = emptySet()
        }
        updateSelectionDownloadState()
    }

    fun toggleBookSelection(bookId: Int) {
        if (_isDeleting.value) return
        val currentSet = _selectedBookIds.value.toMutableSet()
        if (currentSet.contains(bookId)) {
            currentSet.remove(bookId)
        } else {
            currentSet.add(bookId)
        }
        _selectedBookIds.value = currentSet
        updateSelectionDownloadState()
    }

    fun toggleCategorySelection(category: CategoryData) {
        if (_isDeleting.value) return
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
        updateSelectionDownloadState()
    }

    fun updateSelectionDownloadState() {
        val isSelection = _isSelectionMode.value
        val selected = _selectedBookIds.value
        val downloaded = _downloadedBookIds.value

        if (!isSelection || selected.isEmpty()) {
            BookDownloadService.updateStates { states -> states.filter { !(it.isBulk && !it.isDownloading) } }
            return
        }

        // If currently downloading bulk, don't overwrite/add another bulk confirmation
        if (BookDownloadService.activeDownloadStates.value.any { it.isBulk && it.isDownloading }) return

        val existingBulkState = BookDownloadService.activeDownloadStates.value.find { it.isBulk && !it.isDownloading }
        val customIds = _customBookIds.value
        val needDownload = selected.filter { it !in downloaded && it !in customIds }

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
            appContext.getString(R.string.library_all_downloaded)
        } else if (hasSizes && totalSize > 0) {
            appContext.getString(R.string.format_mb, totalSize / (1024f * 1024f))
        } else {
            appContext.getString(R.string.library_unknown_size)
        }

        val newState = BookDownloadState(
            id = existingBulkState?.id ?: java.util.UUID.randomUUID().toString(),
            bookId = -1,
            bookName = appContext.resources.getQuantityString(
                R.plurals.library_selected_books_count,
                selected.size,
                selected.size,
            ),
            sizeText = sizeText,
            isDownloading = false,
            progress = 0,
            isBulk = true,
            bulkBookIds = needDownload
        )

        if (existingBulkState != null) {
            com.maktabah.downloader.BookDownloadService.updateStates { states ->
                states.map { if (it.id == existingBulkState.id) newState else it }
            }
        } else {
            com.maktabah.downloader.BookDownloadService.updateStates { states -> states + newState }
        }
    }

    private fun filterAuthorHierarchy(
        categories: List<CategoryData>,
        cleanQuery: String,
        normalizedNames: Map<Int, String>,
    ): List<CategoryData> {
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
                    dataManager.bookContainsNormalizedQuery(child, cleanQuery, normalizedNames)
                } else false
            }

            if (matchingBooks.isNotEmpty()) {
                val cloned = category.copy(children = matchingBooks.toMutableList())
                result.add(cloned)
            }
        }

        return result
    }

    private fun filterAuthorRoots(
        roots: List<CategoryData>,
        cleanQuery: String,
        normalizedNames: Map<Int, String>,
        showOnlyDownloaded: Boolean,
        downloadedIds: Set<Int>
    ): List<CategoryData> {
        var filtered = roots
        if (cleanQuery.isNotEmpty()) {
            filtered = filterAuthorHierarchy(filtered, cleanQuery, normalizedNames)
        }
        if (showOnlyDownloaded) {
            filtered = filtered.mapNotNull { cat ->
                val downloadedBooks = cat.children.mapNotNull { child ->
                    if (child is BooksData && downloadedIds.contains(child.id)) child else null
                }
                if (downloadedBooks.isNotEmpty()) {
                    cat.copy(children = downloadedBooks.toMutableList())
                } else null
            }
        }
        return filtered
    }

    private inner class LibraryTreeFlattener(
        val expanded: Set<Int>,
        val viewModeVal: LibraryViewMode,
        val query: String,
        val cleanQuery: String,
        val normalizedNames: Map<Int, String>,
        val showOnlyDownloaded: Boolean,
        val downloadedIds: Set<Int>,
        val categoryLimits: Map<Int, Int>
    ) {
        val result = mutableListOf<FlatLibraryItem>()

        fun hasMatchingBooks(cats: List<Any>): Boolean {
            for (c in cats) {
                if (c is BooksData) {
                    val matchesQuery = dataManager.bookContainsNormalizedQuery(c, cleanQuery, normalizedNames)
                    val matchesDownloaded = !showOnlyDownloaded || downloadedIds.contains(c.id)
                    if (matchesQuery && matchesDownloaded) return true
                } else if (c is CategoryData) {
                    if (hasMatchingBooks(c.children)) return true
                }
            }
            return false
        }

        fun checkHasDownloaded(cats: List<Any>): Boolean {
            for (c in cats) {
                if (c is BooksData && downloadedIds.contains(c.id)) return true
                if (c is CategoryData && checkHasDownloaded(c.children)) return true
            }
            return false
        }

        fun traverse(categories: List<Any>, level: Int, parentCategoryId: Int?): Boolean {
            var hasVisibleContent = false
            var bookCount = 0
            var hiddenCount = 0
            val limit = if (parentCategoryId != null) categoryLimits[parentCategoryId]
                ?: 100 else Int.MAX_VALUE

            for (item in categories) {
                if (item is BooksData) {
                    val matchesQuery = if (viewModeVal == LibraryViewMode.AUTHOR) {
                        true
                    } else {
                        dataManager.bookContainsNormalizedQuery(item, cleanQuery, normalizedNames)
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
    }

    private var updateFlatItemsJob: kotlinx.coroutines.Job? = null
    private fun updateFlatItems() {
        updateFlatItemsJob?.cancel()
        updateFlatItemsJob = viewModelScope.launch(Dispatchers.Default) {
            val expanded = _expandedCategories.value
            val viewModeVal = _viewMode.value
            var roots =
                if (viewModeVal == LibraryViewMode.AUTHOR) _authorCategories.value else _rootCategories.value
            val query = _searchQuery.value
            val cleanQuery = query.normalizeArabic()
            val normalizedNames = dataManager.normalizedNamesById
            val showOnlyDownloaded = _showOnlyDownloaded.value
            val downloadedIds = _downloadedBookIds.value
            val categoryLimits = _categoryLimits.value

            if (viewModeVal == LibraryViewMode.AUTHOR) {
                roots = filterAuthorRoots(roots, cleanQuery, normalizedNames, showOnlyDownloaded, downloadedIds)
            }

            val flattener = LibraryTreeFlattener(
                expanded = expanded,
                viewModeVal = viewModeVal,
                query = query,
                cleanQuery = cleanQuery,
                normalizedNames = normalizedNames,
                showOnlyDownloaded = showOnlyDownloaded,
                downloadedIds = downloadedIds,
                categoryLimits = categoryLimits
            )

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

            flattener.traverse(roots, 0, null)

            if (viewModeVal == LibraryViewMode.AUTHOR && hasMore) {
                flattener.result.add(FlatLibraryItem(LoadMoreAuthors(remainingCount), 0))
            }

            withContext(Dispatchers.Main) {
                _flatVisibleItems.value = flattener.result
            }
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

    private var deletePreparationJob: Job? = null

    fun requestDeleteConfirmation() {
        if (_isDeleting.value) return
        if (coreBookIds != null) {
            prepareDeleteConfirmation()
            return
        }
        if (deletePreparationJob?.isActive == true) return
        deletePreparationJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                loadLocalIndexIfPresent()
            }
            if (_isDeleting.value || !_isSelectionMode.value || _selectedBookIds.value.isEmpty()) {
                return@launch
            }
            if (coreBookIds == null) {
                _deleteMessages.trySend(UiText.StringResource(R.string.library_delete_index_unavailable))
                return@launch
            }
            _customBookIds.value = computeCustomBookIds()
            prepareDeleteConfirmation()
        }
    }

    private fun activeDownloadingBookIds(): Set<Int> {
        return BookDownloadService.activeDownloadStates.value
            .filter { it.isDownloading }
            .flatMapTo(HashSet()) { state ->
                if (state.isBulk) state.bulkBookIds else listOf(state.bookId)
            }
    }

    private fun prepareDeleteConfirmation() {
        val summary = buildDeleteSelectionSummary(
            selectedBookIds = _selectedBookIds.value,
            activeDownloadingIds = activeDownloadingBookIds(),
            customBookIds = _customBookIds.value,
            downloadedBookIds = _downloadedBookIds.value,
        )

        if (summary.totalDeletableCount > 0) {
            _pendingDeleteSummary.value = summary
        } else if (summary.skippedDownloadingBookIds.isNotEmpty()) {
            _deleteMessages.trySend(
                UiText.PluralResource(
                    R.plurals.library_delete_skipped_downloading,
                    summary.skippedDownloadingBookIds.size,
                )
            )
        }
    }

    fun dismissDeleteConfirmation() {
        _pendingDeleteSummary.value = null
    }

    fun confirmDeleteSelection(tabManager: ReaderTabManager? = null) {
        val summary = _pendingDeleteSummary.value ?: return
        if (_isDeleting.value) return

        _pendingDeleteSummary.value = null
        _isDeleting.value = true

        viewModelScope.launch {
            var anyMutationOccurred = false
            var catalogMutated = false
            try {
                catalogMutex.withLock {
                    val executionResult = withContext(Dispatchers.IO) {
                        val activeDownloadingIds = activeDownloadingBookIds()
                        val customIds = summary.customBookIds.filter { it !in activeDownloadingIds }
                        val downloadedCoreIds = summary.downloadedCoreBookIds.filter { it !in activeDownloadingIds }
                        val skippedActiveCount = (summary.customBookIds.size - customIds.size) +
                            (summary.downloadedCoreBookIds.size - downloadedCoreIds.size)
                        val allTargets = (customIds + downloadedCoreIds).distinct()
                        val booksByIdSnapshot = dataManager.booksById
                        val groupedByArchive = HashMap<Int, MutableList<Int>>()
                        for (bookId in allTargets) {
                            val archiveId = booksByIdSnapshot[bookId]?.archive
                            if (archiveId != null && archiveId > 0) {
                                groupedByArchive.getOrPut(archiveId) { mutableListOf() }.add(bookId)
                            }
                        }

                        val deletedContentIds = HashSet<Int>()
                        val fullyCleanedArchiveIds = HashSet<Int>()
                        for ((archiveId, bookIdsInArchive) in groupedByArchive) {
                            coroutineContext.ensureActive()
                            val archiveResult = BookArchiveIntegrator.deleteBooksFromArchive(
                                context = appContext,
                                archiveId = archiveId,
                                bookIds = bookIdsInArchive,
                            )
                            deletedContentIds.addAll(archiveResult.deletedContentBookIds)
                            fullyCleanedArchiveIds.addAll(archiveResult.fullyCleanedBookIds)
                        }
                        if (deletedContentIds.isNotEmpty() || fullyCleanedArchiveIds.isNotEmpty()) {
                            anyMutationOccurred = true
                        }

                        val cleanCustomTargets = filterCleanCustomTargets(
                            customIds = customIds,
                            fullyCleanedArchiveIds = fullyCleanedArchiveIds,
                        ) { bookId ->
                            booksByIdSnapshot[bookId]?.archive
                        }
                        val deletedCatalogMetadataIds = if (cleanCustomTargets.isNotEmpty()) {
                            BookImportManager.deleteCatalogBookMetadata(
                                context = appContext,
                                bookIds = cleanCustomTargets,
                                coreAuthorIds = coreAuthorIds,
                            )
                        } else {
                            emptySet()
                        }
                        if (deletedCatalogMetadataIds.isNotEmpty()) {
                            anyMutationOccurred = true
                        }

                        val coreTargetSet = downloadedCoreIds.toHashSet()
                        val deletedBookIds = (
                            deletedContentIds.filter { it in coreTargetSet } +
                                deletedCatalogMetadataIds
                            ).distinct()

                        if (deletedBookIds.isNotEmpty()) {
                            val deletedSet = deletedBookIds.toHashSet()
                            BookDownloadService.updateStates { states ->
                                states.filter { it.isDownloading || it.isBulk || !deletedSet.contains(it.bookId) }
                            }
                        }

                        var refreshedAuthors: List<CategoryData>? = null
                        if (deletedCatalogMetadataIds.isNotEmpty()) {
                            dataManager.loadData()
                            refreshedAuthors = withContext(Dispatchers.Default) {
                                dataManager.buildAuthorHierarchy()
                            }
                        }

                        val newDownloadedIds = scanDownloadedBookIds()
                        val newCustomIds = computeCustomBookIds()

                        DeleteExecutionOutcome(
                            deletedBookIds = deletedBookIds,
                            catalogMutated = deletedCatalogMetadataIds.isNotEmpty(),
                            refreshedAuthors = refreshedAuthors,
                            newDownloadedIds = newDownloadedIds,
                            newCustomIds = newCustomIds,
                            skippedActiveCount = skippedActiveCount,
                        )
                    }

                    if (executionResult.deletedBookIds.isNotEmpty()) {
                        tabManager?.closeTabsForBooks(executionResult.deletedBookIds)
                        if (executionResult.catalogMutated) {
                            catalogMutated = true
                            _rootCategories.value = dataManager.allRootCategories
                            executionResult.refreshedAuthors?.let { _authorCategories.value = it }
                            _catalogRevision.value += 1
                        }
                        _downloadedBookIds.value = executionResult.newDownloadedIds
                        _customBookIds.value = executionResult.newCustomIds
                        updateFlatItems()
                        _isSelectionMode.value = false
                        _selectedBookIds.value = emptySet()
                        updateSelectionDownloadState()
                        _deleteMessages.trySend(
                            UiText.PluralResource(
                                R.plurals.library_delete_success,
                                executionResult.deletedBookIds.size,
                            )
                        )
                        launch(Dispatchers.IO) {
                            DashboardWidget.updateWidget(appContext)
                        }
                    } else {
                        if (anyMutationOccurred) {
                            _downloadedBookIds.value = scanDownloadedBookIds()
                            updateFlatItems()
                        }
                        val message = if (!anyMutationOccurred && executionResult.skippedActiveCount > 0) {
                            UiText.PluralResource(
                                R.plurals.library_delete_skipped_downloading,
                                executionResult.skippedActiveCount,
                            )
                        } else {
                            UiText.StringResource(R.string.library_delete_no_result)
                        }
                        _deleteMessages.trySend(message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("LibraryViewModel", "confirmDeleteSelection failed", e)
                if (anyMutationOccurred) {
                    catalogMutex.withLock {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                dataManager.loadData()
                                Pair(scanDownloadedBookIds(), dataManager.buildAuthorHierarchy())
                            }
                        }.onFailure { recoveryErr ->
                            if (recoveryErr is CancellationException) throw recoveryErr
                        }.onSuccess { (reScannedDownloaded, rebuiltAuthors) ->
                            catalogMutated = true
                            _rootCategories.value = dataManager.allRootCategories
                            _authorCategories.value = rebuiltAuthors
                            _downloadedBookIds.value = reScannedDownloaded
                            _customBookIds.value = computeCustomBookIds()
                            _catalogRevision.value += 1
                            updateFlatItems()
                        }
                    }
                }
                _deleteMessages.trySend(UiText.StringResource(R.string.library_delete_no_result))
            } finally {
                if (catalogMutated) {
                    checkBookUpdatesPeriodically(force = true)
                }
                _isDeleting.value = false
            }
        }
    }

    private data class DeleteExecutionOutcome(
        val deletedBookIds: List<Int>,
        val catalogMutated: Boolean,
        val refreshedAuthors: List<CategoryData>?,
        val newDownloadedIds: Set<Int>,
        val newCustomIds: Set<Int>,
        val skippedActiveCount: Int = 0,
    )

    fun showDownloadConfirmation(
        bookId: Int,
        contentId: Int? = null,
        loc: Int? = null,
        len: Int? = null,
        query: String? = null
    ) {
        // Prevent duplicate confirmation for the same book
        if (com.maktabah.downloader.BookDownloadService.activeDownloadStates.value.any { it.bookId == bookId && !it.isBulk }) return

        viewModelScope.launch {
            val bookName = dataManager.booksById[bookId]?.name
                ?: appContext.getString(R.string.library_fallback_book_name)
            if (downloadIndex == null) {
                populateDownloadIndex()
            }
            val index = downloadIndex.orEmpty()
            val entry = index.find { it.bkid == bookId }
            val sizeText = entry?.sizeZst?.let { size ->
                appContext.getString(R.string.format_mb, size / (1024f * 1024f))
            } ?: appContext.getString(R.string.library_unknown_size)

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
            com.maktabah.downloader.BookDownloadService.updateStates { states -> states + newState }
        }
    }

    fun cancelDownload(id: String) {
        downloadCompletionJobs.remove(id)?.cancel()
        val state = BookDownloadService.getState(id)
        if (state != null && state.isDownloading) {
            BookDownloadService.cancelDownload(id)
        } else {
            BookDownloadService.updateStates { states -> states.filter { it.id != id } }
        }
    }

    fun confirmDownload(
        stateId: String,
        onComplete: (Int, Int?, Int?, Int?, String?) -> Unit
    ) {
        val currentState = BookDownloadService.getState(stateId) ?: return

        BookDownloadService.startDownload(appContext, stateId)

        if (currentState.isBulk) {
            _isSelectionMode.value = false
            _selectedBookIds.value = emptySet()
        } else {
            downloadCompletionJobs.remove(stateId)?.cancel()
            val completionJob = viewModelScope.launch {
                val parentJob = coroutineContext[Job]
                var watcherJob: Job? = null
                try {
                    var sawFreshAttempt = currentState.error == null
                    watcherJob = launch {
                        BookDownloadService.activeDownloadStates.collect { states ->
                            val tracked = states.find { it.id == stateId }
                            if (!sawFreshAttempt && tracked != null && (tracked.isDownloading || tracked.error == null)) {
                                sawFreshAttempt = true
                            }
                            if (tracked == null) {
                                delay(250)
                                parentJob?.cancel()
                            } else if (sawFreshAttempt && tracked.error != null) {
                                parentJob?.cancel()
                            }
                        }
                    }
                    BookDownloadNotifier.downloadedBookEvents.first { it == currentState.bookId }
                    watcherJob.cancel()
                    onComplete(
                        currentState.bookId,
                        currentState.contentId,
                        currentState.loc,
                        currentState.len,
                        currentState.query
                    )
                } finally {
                    watcherJob?.cancel()
                    if (downloadCompletionJobs[stateId] === parentJob) {
                        downloadCompletionJobs.remove(stateId)
                    }
                }
            }
            if (completionJob.isActive) {
                downloadCompletionJobs[stateId] = completionJob
            }
        }
    }

    companion object {
        internal fun deriveCoreCatalogSets(
            entries: List<BundleBookIndexEntry>,
        ): Pair<Set<Int>, Set<Int>?> {
            val parsedCoreBooks = entries.mapTo(HashSet(entries.size)) { it.bkid }
            val parsedCoreAuthors = entries.mapNotNullTo(HashSet()) { it.authno.takeIf { id -> id > 0 } }
            return parsedCoreBooks to parsedCoreAuthors.takeIf { it.isNotEmpty() }
        }

        internal fun computeCustomBookIds(
            catalogBookIds: Set<Int>,
            coreBookIds: Set<Int>?,
        ): Set<Int> {
            val indexIds = coreBookIds ?: return emptySet()
            return catalogBookIds.filterTo(HashSet()) { it !in indexIds }
        }

        internal fun buildDeleteSelectionSummary(
            selectedBookIds: Set<Int>,
            activeDownloadingIds: Set<Int>,
            customBookIds: Set<Int>,
            downloadedBookIds: Set<Int>,
        ): DeleteSelectionSummary {
            val customTargets = mutableListOf<Int>()
            val coreTargets = mutableListOf<Int>()
            val skippedDownloading = mutableListOf<Int>()

            for (bookId in selectedBookIds) {
                when {
                    bookId in activeDownloadingIds -> skippedDownloading.add(bookId)
                    bookId in customBookIds -> customTargets.add(bookId)
                    bookId in downloadedBookIds -> coreTargets.add(bookId)
                }
            }

            return DeleteSelectionSummary(
                customBookIds = customTargets,
                downloadedCoreBookIds = coreTargets,
                skippedDownloadingBookIds = skippedDownloading,
            )
        }

        internal fun filterCleanCustomTargets(
            customIds: List<Int>,
            fullyCleanedArchiveIds: Set<Int>,
            archiveIdForBook: (Int) -> Int?,
        ): List<Int> {
            return customIds.filter { bookId ->
                val archiveId = archiveIdForBook(bookId)
                archiveId == null || archiveId <= 0 || bookId in fullyCleanedArchiveIds
            }
        }
    }
}
