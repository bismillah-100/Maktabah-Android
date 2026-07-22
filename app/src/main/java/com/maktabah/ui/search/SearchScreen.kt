package com.maktabah.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.models.CategoryData
import com.maktabah.models.FlatLibraryItem
import com.maktabah.models.SearchMode
import com.maktabah.models.SearchResult
import com.maktabah.ui.common.DonationIconButton
import com.maktabah.ui.common.GroupedRecyclerView
import com.maktabah.ui.common.InsetGroupedItem
import com.maktabah.ui.common.fadingEdge
import com.maktabah.ui.common.rememberGroupedListColors
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.ui.search.savedresults.ResultWriterSheet
import com.maktabah.ui.search.savedresults.SavedResultsScreen
import com.maktabah.utils.GroupedCardDecoration
import com.maktabah.utils.convertToArabicDigits
import com.maktabah.utils.normalizeArabic

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    libraryViewModel: LibraryViewModel,
    bottomPadding: Dp,
    onNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit,
    hasDonated: Boolean,
    onClearGlobalQuery: () -> Unit = {},
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val results by viewModel.searchResults.collectAsState()
    val lastSearchQuery by viewModel.lastSearchQuery.collectAsState()
    val lastSearchMode by viewModel.lastSearchMode.collectAsState()
    val searchHistory by viewModel.searchHistory.collectAsState()

    var query by remember(lastSearchQuery) { mutableStateOf(lastSearchQuery) }
    var bookFilter by remember { mutableStateOf("") }
    var activeSearchMode by remember(lastSearchMode) { mutableStateOf(lastSearchMode) }
    var isFocused by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }

    val resultsViewModel: ResultsViewModel = viewModel()
    val showSavedResults by viewModel.showSavedResults.collectAsState()
    var showResultWriter by remember { mutableStateOf(false) }

    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    val isSearching by viewModel.isSearching.collectAsState()
    val selectedBookIds by viewModel.selectedBookIds.collectAsState()
    val flatVisibleItems by viewModel.flatVisibleItems.collectAsState()
    val isDataLoaded by libraryViewModel.isDataLoaded.collectAsState()
    val isTreeLoaded by viewModel.isTreeLoaded.collectAsState()
    val completedBooks by viewModel.completedBooks.collectAsState()
    val totalBooks by viewModel.totalBooks.collectAsState()
    val currentBookProgress by viewModel.currentBookProgress.collectAsState()
    val currentBookName by viewModel.currentBookName.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.initialize(context, libraryViewModel.dataManager, libraryViewModel.downloadedBookIds)
        resultsViewModel.initialize(context, libraryViewModel.dataManager)
    }

    LaunchedEffect(isDataLoaded) {
        if (isDataLoaded) {
            viewModel.refreshData(libraryViewModel.dataManager)
        }
    }

    LaunchedEffect(results) {
        if (results.isEmpty()) bookFilter = ""
    }

    val pagerState = rememberPagerState(pageCount = { 2 })
    var userScrollEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(showSavedResults) {
        if (showSavedResults) {
            if (pagerState.currentPage != 1) {
                pagerState.animateScrollToPage(1)
            }
        } else {
            if (pagerState.currentPage != 0) {
                pagerState.animateScrollToPage(0)
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        val showSaved = pagerState.currentPage == 1
        if (showSavedResults != showSaved) {
            viewModel.setShowSavedResults(showSaved)
        }
    }

    BackHandler(enabled = results.isNotEmpty() && !showSavedResults) {
        viewModel.clearResults()
        query = ""
        onClearGlobalQuery()
    }

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = userScrollEnabled,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var isDirectionDetermined = false

                    do {
                        val event = awaitPointerEvent()
                        val currentPointer = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!isDirectionDetermined) {
                            val dx = kotlin.math.abs(currentPointer.position.x - down.position.x)
                            val dy = kotlin.math.abs(currentPointer.position.y - down.position.y)

                            if (dx > touchSlop || dy > touchSlop) {
                                isDirectionDetermined = true
                                userScrollEnabled = dx > dy
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    userScrollEnabled = true
                }
            },
    ) { page ->
        val pageOffset = page - (pagerState.currentPage + pagerState.currentPageOffsetFraction)

        val pageModifier = Modifier
            .fillMaxSize()
            .zIndex(if (page == 1) 1f else 0f)
            .graphicsLayer {
                if (page == 0) {
                    if (pageOffset < 0f) {
                        translationX = -pageOffset * size.width * 0.7f
                        scaleX = 1f + (pageOffset * 0.05f)
                        scaleY = 1f + (pageOffset * 0.05f)
                        alpha = 1f + (pageOffset * 0.5f)
                    } else {
                        translationX = 0f
                        scaleX = 1f
                        scaleY = 1f
                        alpha = 1f
                    }
                } else if (page == 1) {
                    translationX = 0f
                    scaleX = 1f
                    scaleY = 1f
                    alpha = 1f
                }
            }

        Box(modifier = pageModifier) {
            when (page) {
                0 -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .pointerInput(Unit) {
                                    detectTapGestures(onTap = {
                                        focusManager.clearFocus()
                                    })
                                },
                    ) {
                        val searchQuery by viewModel.searchQuery.collectAsState()
                        val expandedCategories by viewModel.expandedCategories.collectAsState()

                        FilterAndCategoryContent(
                            viewModel = viewModel,
                            libraryViewModel = libraryViewModel,
                            flatVisibleItems = flatVisibleItems,
                            selectedBookIds = selectedBookIds,
                            expandedCategories = expandedCategories,
                            searchQuery = searchQuery,
                            bottomContentPadding = bottomPadding + 88.dp,
                            hasDonated = hasDonated,
                            isDataLoaded = isDataLoaded && isTreeLoaded,
                            onOpenSavedResults = { viewModel.setShowSavedResults(true) }
                        )

                        QueryInputBar(
                            query = query,
                            onQueryChange = { newQuery -> 
                                query = newQuery 
                                if (newQuery.isEmpty()) {
                                    viewModel.clearResults()
                                    onClearGlobalQuery()
                                }
                            },
                            onSearch = {
                                focusManager.clearFocus()
                                viewModel.performSearch(
                                    context,
                                    query,
                                    activeSearchMode,
                                    libraryViewModel.dataManager
                                )
                            },
                            canSearch = query.isNotBlank() && selectedBookIds.isNotEmpty(),
                            placeholder = stringResource(R.string.search_query_placeholder),
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = androidx.compose.ui.unit.max(bottomPadding, imeBottom) + 8.dp),
                            onFocusChanged = { isFocused = it }
                        )

                        if (isFocused) {
                            SearchHistoryOverlay(
                                searchHistory = searchHistory,
                                onClearAll = { viewModel.clearHistory(context) },
                                onHistoryClick = { historyQuery ->
                                    query = historyQuery
                                    focusManager.clearFocus()
                                    viewModel.performSearch(
                                        context,
                                        historyQuery,
                                        activeSearchMode,
                                        libraryViewModel.dataManager
                                    )
                                },
                                onRemoveHistory = { historyQuery ->
                                    viewModel.removeFromHistory(context, historyQuery)
                                },
                                activeMode = activeSearchMode,
                                onModeSelect = { activeSearchMode = it },
                                onHelpClick = { showHelpDialog = true },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = androidx.compose.ui.unit.max(bottomPadding, imeBottom) + 88.dp)
                            )
                        }

                        if (showHelpDialog) {
                            SearchHelpDialog(onDismiss = { showHelpDialog = false })
                        }

                        AnimatedVisibility(
                            visible = results.isNotEmpty(),
                            enter =
                                slideInVertically(
                                    initialOffsetY = { fullHeight -> fullHeight },
                                    animationSpec = tween(durationMillis = 400),
                                ) + fadeIn(animationSpec = tween(300)),
                            exit =
                                slideOutVertically(
                                    targetOffsetY = { fullHeight -> fullHeight },
                                    animationSpec = tween(durationMillis = 300),
                                ) + fadeOut(animationSpec = tween(200)),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            SearchResultsOverlay(
                                results = results,
                                query = lastSearchQuery,
                                searchMode = activeSearchMode,
                                bookFilter = bookFilter,
                                onBookFilterChange = { bookFilter = it },
                                onClearResults = {
                                    viewModel.clearResults()
                                    query = ""
                                    onClearGlobalQuery()
                                },
                                onSelect = onNavigateToReader,
                                bottomPadding = bottomPadding,
                                libraryViewModel = libraryViewModel,
                                onOpenSavedResults = { viewModel.setShowSavedResults(true) },
                                onSaveResults = { showResultWriter = true }
                            )
                        }

                        SearchProgressBars(
                            isSearching = isSearching,
                            completedBooks = completedBooks,
                            totalBooks = totalBooks,
                            currentBookProgress = currentBookProgress,
                            currentBookName = currentBookName,
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = androidx.compose.ui.unit.max(bottomPadding, imeBottom)),
                        )
                    }
                }
                1 -> {
                    SavedResultsScreen(
                        resultsViewModel = resultsViewModel,
                        onSelectResult = { items ->
                            viewModel.setShowSavedResults(false)
                            viewModel.loadSavedResults(items, context, libraryViewModel.dataManager)
                        },
                        onRefresh = { resultsViewModel.reloadFromSync() },
                        onDismiss = { viewModel.setShowSavedResults(false) },
                        bottomPadding = bottomPadding,
                        backHandlerEnabled = pagerState.currentPage == 1
                    )
                }
            }
        }
    }

    if (showResultWriter) {
        ResultWriterSheet(
            results = results,
            query = lastSearchQuery,
            resultsViewModel = resultsViewModel,
            dataManager = libraryViewModel.dataManager,
            onDismiss = { showResultWriter = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterAndCategoryContent(
    viewModel: SearchViewModel,
    libraryViewModel: LibraryViewModel,
    flatVisibleItems: List<FlatLibraryItem>,
    selectedBookIds: Set<Int>,
    expandedCategories: Set<Int>,
    searchQuery: String,
    bottomContentPadding: Dp,
    hasDonated: Boolean,
    isDataLoaded: Boolean,
    onOpenSavedResults: () -> Unit
) {
    Scaffold(
        topBar = {
            SearchFilterTopBar(
                searchQuery = searchQuery,
                onQueryChange = { viewModel.updateSearchQuery(it, libraryViewModel.dataManager) },
                onClearSelection = { viewModel.clearSelection() },
                hasDonated = hasDonated,
                onOpenSavedResults = onOpenSavedResults
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        if (!isDataLoaded) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (flatVisibleItems.isEmpty() && searchQuery.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.search_empty_library_hint),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        } else {
            SearchFilterList(
                flatVisibleItems = flatVisibleItems,
                selectedBookIds = selectedBookIds,
                isBookDownloadedById = { libraryViewModel.isBookDownloadedById(it) },
                expandedCategories = expandedCategories,

                onToggleCategory = { viewModel.toggleCategory(it, libraryViewModel.dataManager) },
                onToggleBook = { viewModel.toggleBookSelection(it) },
                onLoadMore = { viewModel.loadMore(it, libraryViewModel.dataManager) },
                onToggleCategorySelection = { viewModel.toggleCategorySelection(it) },
                padding = padding,
                bottomContentPadding = bottomContentPadding
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFilterTopBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onClearSelection: () -> Unit,
    hasDonated: Boolean,
    onOpenSavedResults: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            if (!hasDonated) {
                DonationIconButton()
            }
        },
        title = {
            SearchTextField(
                value = searchQuery,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.library_search_books_placeholder),
                modifier = Modifier.padding(end = 12.dp),
                onClearClick = { onQueryChange("") }
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        actions = {
            IconButton(onClick = onClearSelection) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = stringResource(R.string.search_action_deselect_all)
                )
            }
            IconButton(onClick = onOpenSavedResults) {
                Icon(
                    Icons.Default.Bookmarks,
                    contentDescription = stringResource(R.string.saved_results_title)
                )
            }
        },
    )
}

@Composable
private fun SearchFilterList(
    flatVisibleItems: List<FlatLibraryItem>,
    selectedBookIds: Set<Int>,
    isBookDownloadedById: (Int) -> Boolean,
    expandedCategories: Set<Int>,
    onToggleCategory: (Int) -> Unit,
    onToggleBook: (Int) -> Unit,
    onLoadMore: (Int) -> Unit,
    onToggleCategorySelection: (CategoryData) -> Unit,
    padding: PaddingValues,
    bottomContentPadding: Dp,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val colors = rememberGroupedListColors()

        val adapter = remember {
            com.maktabah.ui.library.LibraryAdapter(
                isBookDownloadedById = isBookDownloadedById,

                onCategoryToggle = onToggleCategory,
                onBookClick = { }, // Not used in selection mode
                onBookSelectionToggle = onToggleBook,
                onLoadMore = onLoadMore,
                onLoadMoreAuthors = { }, // Not used in search filter
                onCategorySelectionToggle = onToggleCategorySelection
            ).apply {
                this.primaryColor = colors.primaryColor
                this.secondaryColor = colors.secondaryColor
                this.onSurfaceVariantColor = colors.onSurfaceVariantColor
                this.isSelectionMode = true
                this.onlySelectDownloaded = true
                this.expandedCategories = expandedCategories
                this.stateRestorationPolicy = androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
                submitList(flatVisibleItems)
            }
        }


        LaunchedEffect(
            selectedBookIds, expandedCategories, flatVisibleItems,
            colors
        ) {
            adapter.isSelectionMode = true
            adapter.onlySelectDownloaded = true
            adapter.selectedBookIds = selectedBookIds
            adapter.expandedCategories = expandedCategories
            adapter.primaryColor = colors.primaryColor
            adapter.secondaryColor = colors.secondaryColor
            adapter.onSurfaceVariantColor = colors.onSurfaceVariantColor
            adapter.submitList(flatVisibleItems)
        }

        GroupedRecyclerView(
            recyclerViewId = R.id.search_recycler_view,
            adapter = adapter,
            padding = padding,
            bottomContentPadding = bottomContentPadding,
            colors = colors,
            decorationFactory = { rv ->
                val ctx = rv.context
                val cornerRadius = 30 * ctx.resources.displayMetrics.density
                val marginH = 16 * ctx.resources.displayMetrics.density

                GroupedCardDecoration(
                    cornerRadius = cornerRadius,
                    strokeWidth = 0f,
                    marginHorizontal = marginH
                ) { position ->
                    val adapterCount = adapter.itemCount
                    if (position !in 0..<adapterCount) return@GroupedCardDecoration null
                    GroupedCardDecoration.GroupInfo(
                        isFirst = position == 0,
                        isLast = position == adapterCount - 1
                    )
                }
            }
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultsOverlay(
    results: List<SearchResult>,
    query: String,
    searchMode: SearchMode,
    bookFilter: String,
    onBookFilterChange: (String) -> Unit,
    onClearResults: () -> Unit,
    onSelect: (Int, Int?, Int?, Int?, String?) -> Unit,
    bottomPadding: Dp,
    libraryViewModel: LibraryViewModel,
    onOpenSavedResults: () -> Unit,
    onSaveResults: () -> Unit
) {
    var debouncedBookFilter by remember { mutableStateOf(bookFilter) }
    LaunchedEffect(bookFilter) {
        if (bookFilter.isEmpty()) {
            debouncedBookFilter = bookFilter
        } else {
            kotlinx.coroutines.delay(500)
            debouncedBookFilter = bookFilter
        }
    }

    val filteredResults = remember(results, debouncedBookFilter) {
        if (debouncedBookFilter.isEmpty()) {
            results
        } else {
            val cleanQuery = debouncedBookFilter.normalizeArabic()
            results.filter { result ->
                val name = libraryViewModel.dataManager.booksById[result.bookId]?.name ?: ""
                name.normalizeArabic().contains(cleanQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClearResults) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.search_result_close)
                        )
                    }
                },
                title = {
                    val focusRequester = remember { FocusRequester() }
                    BasicTextField(
                        value = bookFilter,
                        onValueChange = onBookFilterChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .padding(end = 14.dp)
                            .height(40.dp)
                            .border(width = 1.dp, color = Color.Gray, shape = CircleShape),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            TextFieldDefaults.DecorationBox(
                                value = bookFilter,
                                innerTextField = innerTextField,
                                enabled = true,
                                singleLine = true,
                                visualTransformation = VisualTransformation.None,
                                interactionSource = remember { MutableInteractionSource() },
                                placeholder = { Text(stringResource(R.string.search_filter_placeholder)) },
                                trailingIcon = {
                                    if (bookFilter.isNotEmpty()) {
                                        IconButton(onClick = { onBookFilterChange("") }) {
                                            Icon(
                                                Icons.Default.Close,
                                                modifier = Modifier.size(32.dp),
                                                contentDescription = stringResource(R.string.search_filter_clear)
                                            )
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                container = {}
                            )
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    IconButton(onClick = onOpenSavedResults) {
                        Icon(
                            Icons.Default.Bookmarks,
                            contentDescription = stringResource(R.string.saved_results_title)
                        )
                    }
                    IconButton(onClick = onSaveResults) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(R.string.save_results_title)
                        )
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fadingEdge(listState, padding.calculateTopPadding()),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = bottomPadding + 16.dp
            ),
        ) {
            items(
                count = filteredResults.size,
                key = { "${filteredResults[it].bookId}_${filteredResults[it].contentId}" },
            ) { index ->
                val result = filteredResults[index]
                val bookName =
                    libraryViewModel.dataManager.booksById[result.bookId]?.name
                        ?: stringResource(R.string.library_fallback_book_name)

                InsetGroupedItem(
                    index = index,
                    lastIndex = filteredResults.lastIndex,
                    onClick = { onSelect(result.bookId, result.contentId, null, null, query) },
                    color = MaterialTheme.colorScheme.surface,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    dividerStartPadding = Dp.Hairline,
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalLayoutDirection provides LayoutDirection.Rtl,
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = bookName,
                                    style = MaterialTheme.typography.titleMedium,
                                    textAlign = TextAlign.Start,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Text(
                                    text = "ج${result.part} ص${result.page}".convertToArabicDigits(),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Start,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val highlightedText = buildHighlightedText(
                                text = result.text,
                                query = query,
                                mode = searchMode
                            )
                            Text(
                                text = highlightedText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchProgressBars(
    isSearching: Boolean,
    completedBooks: Int,
    totalBooks: Int,
    currentBookProgress: Pair<Int, Int>?,
    currentBookName: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isSearching,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            tonalElevation = 4.dp,
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (totalBooks > 0) {
                    Text(
                        text = if (currentBookName.isNotEmpty()) stringResource(
                            R.string.search_progress_searching,
                            currentBookName
                        ) else stringResource(R.string.search_progress_preparing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1
                    )
                    LinearProgressIndicator(progress = {
                        completedBooks.toFloat() / totalBooks.coerceAtLeast(
                            1
                        )
                    }, modifier = Modifier.fillMaxWidth())
                }
                if (currentBookProgress != null && currentBookProgress.second > 0) {
                    LinearProgressIndicator(
                        progress = { currentBookProgress.first.toFloat() / currentBookProgress.second },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
