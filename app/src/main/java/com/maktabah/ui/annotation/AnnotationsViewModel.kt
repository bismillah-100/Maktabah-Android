package com.maktabah.ui.annotation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maktabah.database.AnnotationManager
import com.maktabah.manager.LibraryDataManager
import com.maktabah.models.Annotation
import com.maktabah.models.AnnotationChange
import com.maktabah.models.AnnotationGroup
import com.maktabah.models.AnnotationGroupingMode
import com.maktabah.models.AnnotationSearchScope
import com.maktabah.models.AnnotationSortField
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.FlowPreview::class)
class AnnotationsViewModel : ViewModel() {
    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())
    val annotations: StateFlow<List<Annotation>> = _annotations.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val searchQuery = MutableStateFlow("")

    private val _searchScope = MutableStateFlow(AnnotationSearchScope.ALL)
    val searchScope = _searchScope.asStateFlow()

    private val _groupingMode = MutableStateFlow(AnnotationGroupingMode.BOOK)
    val groupingMode = _groupingMode.asStateFlow()

    private val _sortField = MutableStateFlow(AnnotationSortField.CREATED_AT)
    val sortField = _sortField.asStateFlow()

    private val _sortAscending = MutableStateFlow(false)
    val sortAscending = _sortAscending.asStateFlow()

    private val _bookIdFilter = MutableStateFlow<Int?>(null)

    private val _expandedGroups = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val expandedGroups: StateFlow<Map<String, Boolean>> = _expandedGroups.asStateFlow()

    private val _isSelectionMode = MutableStateFlow(false)
    val isSelectionMode: StateFlow<Boolean> = _isSelectionMode.asStateFlow()

    private val _selectedAnnotationIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedAnnotationIds: StateFlow<Set<Long>> = _selectedAnnotationIds.asStateFlow()

    fun toggleSelectionMode() {
        val newMode = !_isSelectionMode.value
        _isSelectionMode.value = newMode
        if (!newMode) {
            _selectedAnnotationIds.value = emptySet()
        }
    }

    fun toggleAnnotationSelection(annotationId: Long) {
        val current = _selectedAnnotationIds.value.toMutableSet()
        if (current.contains(annotationId)) {
            current.remove(annotationId)
        } else {
            current.add(annotationId)
        }
        _selectedAnnotationIds.value = current
    }

    fun toggleGroupSelection(group: AnnotationGroup) {
        val current = _selectedAnnotationIds.value.toMutableSet()
        val groupAnnIds = group.annotations.mapNotNull { it.id }
        val allSelected = groupAnnIds.isNotEmpty() && groupAnnIds.all { current.contains(it) }
        if (allSelected) {
            current.removeAll(groupAnnIds.toSet())
        } else {
            current.addAll(groupAnnIds)
        }
        _selectedAnnotationIds.value = current
    }

    fun clearSelection() {
        _selectedAnnotationIds.value = emptySet()
        _isSelectionMode.value = false
    }

    fun toggleGroupExpanded(key: String) {
        val current = _expandedGroups.value.toMutableMap()
        val isExpanded = current[key] ?: false
        current[key] = !isExpanded
        _expandedGroups.value = current
        
        if (::sharedPrefs.isInitialized) {
            val expandedKeys = current.filterValues { it }.keys
            sharedPrefs.edit().putStringSet("expandedGroups", expandedKeys).apply()
        }
    }

    fun setGroupingMode(mode: AnnotationGroupingMode) {
        _groupingMode.value = mode
    }

    fun setSortField(field: AnnotationSortField) {
        _sortField.value = field
    }

    fun setSortAscending(ascending: Boolean) {
        _sortAscending.value = ascending
    }

    fun setBookIdFilter(filter: Int?) {
        _bookIdFilter.value = filter
    }

    fun setSearchScope(scope: AnnotationSearchScope) {
        _searchScope.value = scope
    }

    private lateinit var dataManager: LibraryDataManager
    private lateinit var sharedPrefs: android.content.SharedPreferences
    private var isInitialized = false

    fun initialize(
        context: android.content.Context,
        annotationManager: AnnotationManager,
        libraryDataManager: LibraryDataManager,
    ) {
        if (isInitialized) return
        isInitialized = true
        this.dataManager = libraryDataManager
        this.sharedPrefs = context.getSharedPreferences("AnnotationsPrefs", android.content.Context.MODE_PRIVATE)

        val expandedKeys = sharedPrefs.getStringSet("expandedGroups", emptySet()) ?: emptySet()
        val initialExpanded = expandedKeys.associateWith { true }
        _expandedGroups.value = initialExpanded

        loadAnnotations(annotationManager, isInitial = true)
        observeAnnotationUpdates(annotationManager)
    }

    private fun observeAnnotationUpdates(annotationManager: AnnotationManager) {
        viewModelScope.launch(Dispatchers.IO) {
            AnnotationManager.updates.collect { change ->
                handleAnnotationChange(change, annotationManager)
            }
        }
    }

    private fun handleAnnotationChange(
        change: AnnotationChange,
        annotationManager: AnnotationManager,
    ) {
        when (change) {
            is AnnotationChange.Upsert -> handleUpsert(change.annotation)
            is AnnotationChange.Delete -> handleDelete(change.id)
            is AnnotationChange.ReloadAll -> loadAnnotations(annotationManager)
        }
    }

    private fun handleUpsert(annotation: Annotation) {
        val currentList = _annotations.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == annotation.id }
        if (index != -1) {
            currentList[index] = annotation
        } else {
            currentList.add(0, annotation)
            currentList.sortByDescending { it.createdAt }
        }
        _annotations.value = currentList
    }

    private fun handleDelete(annotationId: Long) {
        val currentList = _annotations.value.toMutableList()
        currentList.removeAll { it.id == annotationId }
        _annotations.value = currentList
    }

    private fun loadAnnotations(annotationManager: AnnotationManager, isInitial: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (isInitial) {
                _isLoading.value = true
            }
            _annotations.value = annotationManager.getAllAnnotations()
            if (isInitial) {
                _isLoading.value = false
            }
        }
    }

    fun forceReload(annotationManager: AnnotationManager) {
        loadAnnotations(annotationManager)
    }

    private val sortState =
        combine(_sortField, _sortAscending) { field, ascending ->
            field to ascending
        }

    private val filterAndSortParams: Flow<FilterAndSortParams> =
        combine(
            searchQuery,
            _searchScope,
            _groupingMode,
            sortState,
            _bookIdFilter
        ) { query, scope, grouping, sort, bookFilter ->
            FilterAndSortParams(query, scope, grouping, sort, bookFilter)
        }.debounce { params ->
            if (params.query.isEmpty()) 0L else 500L
        }

    val groupedAnnotations: StateFlow<List<AnnotationGroup>> =
        combine(_annotations, filterAndSortParams) { annotationsList, params ->
            val query = params.query
            val scope = params.scope
            val grouping = params.grouping
            val (field, ascending) = params.sort
            val bookFilter = params.bookFilter
            var filtered =
                if (bookFilter != null) {
                    annotationsList.filter { it.bkId == bookFilter }
                } else {
                    annotationsList
                }

            if (query.isNotBlank()) {
                val normalizedQuery = query.normalizeArabic()
                filtered =
                    filtered.filter { ann ->
                        val bookName = dataManager.booksById[ann.bkId]?.name ?: ""

                        val matchesBook = (scope == AnnotationSearchScope.ALL || scope == AnnotationSearchScope.BOOK) &&
                            bookName.normalizeArabic().contains(normalizedQuery, ignoreCase = true)

                        val matchesContext = (scope == AnnotationSearchScope.ALL || scope == AnnotationSearchScope.CONTEXT) &&
                            ann.context.normalizeArabic().contains(normalizedQuery, ignoreCase = true)

                        val matchesNote = (scope == AnnotationSearchScope.ALL || scope == AnnotationSearchScope.NOTE) &&
                            ann.note?.normalizeArabic()?.contains(normalizedQuery, ignoreCase = true) == true

                        val matchesTag = (scope == AnnotationSearchScope.ALL || scope == AnnotationSearchScope.TAG) &&
                            ann.tags.normalizeArabic().contains(normalizedQuery, ignoreCase = true)

                        matchesBook || matchesContext || matchesNote || matchesTag
                    }
            }

            fun getCreatedAtMillis(ann: Annotation): Long {
                return if (ann.createdAt in 1L..9999999999L) ann.createdAt * 1000L else ann.createdAt
            }

            val sortedAnnotations =
                filtered.sortedWith { left, right ->
                    val result =
                        when (field) {
                            AnnotationSortField.CREATED_AT -> {
                                val cmp = getCreatedAtMillis(left).compareTo(getCreatedAtMillis(right))
                                if (cmp != 0) cmp else left.context.compareTo(
                                    right.context,
                                    ignoreCase = true
                                )
                            }

                            AnnotationSortField.CONTEXT -> {
                                val cmp = left.context.compareTo(right.context, ignoreCase = true)
                                if (cmp != 0) cmp else getCreatedAtMillis(left).compareTo(getCreatedAtMillis(right))
                            }

                            AnnotationSortField.PAGE -> {
                                val cmp = left.page.compareTo(right.page)
                                if (cmp != 0) cmp else getCreatedAtMillis(left).compareTo(getCreatedAtMillis(right))
                            }

                            AnnotationSortField.PART -> {
                                val cmp = left.part.compareTo(right.part)
                                if (cmp != 0) {
                                    cmp
                                } else {
                                    val pageCmp = left.page.compareTo(right.page)
                                    if (pageCmp != 0) pageCmp else getCreatedAtMillis(left).compareTo(getCreatedAtMillis(right))
                                }
                            }
                        }
                    if (ascending) result else -result
                }

            when (grouping) {
                AnnotationGroupingMode.BOOK -> {
                    val groupedMap = sortedAnnotations.groupBy { it.bkId }
                    val sortedBookIds =
                        if (field == AnnotationSortField.CREATED_AT) {
                            groupedMap.keys.sortedWith { id1, id2 ->
                                val max1 = groupedMap[id1]?.maxOfOrNull { getCreatedAtMillis(it) } ?: 0L
                                val max2 = groupedMap[id2]?.maxOfOrNull { getCreatedAtMillis(it) } ?: 0L
                                val cmp = max1.compareTo(max2)
                                if (ascending) cmp else -cmp
                            }
                        } else {
                            groupedMap.keys.sortedBy { id ->
                                dataManager.booksById[id]?.name ?: ""
                            }
                        }

                    sortedBookIds.map { bkId ->
                        val bookName = dataManager.booksById[bkId]?.name ?: "ID: $bkId"
                        AnnotationGroup.BookGroup(
                            bkId = bkId,
                            title = bookName,
                            annotations = groupedMap[bkId] ?: emptyList(),
                        )
                    }
                }

                AnnotationGroupingMode.TAG -> {
                    val tagsMap = mutableMapOf<String, MutableList<Annotation>>()
                    val untaggedList = mutableListOf<Annotation>()

                    for (ann in sortedAnnotations) {
                        val tagsList =
                            ann.tags
                                .split(",")
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }

                        if (tagsList.isEmpty()) {
                            untaggedList.add(ann)
                        } else {
                            for (tag in tagsList) {
                                tagsMap.getOrPut(tag) { mutableListOf() }.add(ann)
                            }
                        }
                    }

                    val sortedTags = tagsMap.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)

                    val tagGroups =
                        sortedTags
                            .map { tag ->
                                AnnotationGroup.TagGroup(
                                    tagName = tag,
                                    title = tag,
                                    annotations = tagsMap[tag] ?: emptyList(),
                                )
                            }.toMutableList()

                    if (untaggedList.isNotEmpty()) {
                        tagGroups.add(
                            AnnotationGroup.TagGroup(
                                tagName = "Untagged",
                                title = "Untagged",
                                annotations = untaggedList,
                            ),
                        )
                    }
                    tagGroups
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )
}

private data class FilterAndSortParams(
    val query: String,
    val scope: AnnotationSearchScope,
    val grouping: AnnotationGroupingMode,
    val sort: Pair<AnnotationSortField, Boolean>,
    val bookFilter: Int?
)
