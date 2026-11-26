package com.maktabah.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.cloudKit.CloudKitSyncManager
import com.maktabah.database.AnnotationManager
import com.maktabah.models.BooksData
import com.maktabah.models.ReadingEntry
import com.maktabah.ui.common.DonationCard
import com.maktabah.ui.common.DonationIconButton
import com.maktabah.ui.common.InsetGroupedItem
import com.maktabah.ui.common.PopoverMenuAction
import com.maktabah.ui.common.TapCenteredPopover
import com.maktabah.ui.common.consumeBottomSheetNestedScroll
import com.maktabah.ui.common.fadingEdge
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.ui.search.SearchTextField
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyViewModel: HistoryViewModel,
    libraryViewModel: LibraryViewModel,
    annotationManager: AnnotationManager,
    cloudKitSyncManager: CloudKitSyncManager,
    bottomPadding: Dp,
    onNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit,
    hasDonated: Boolean,
) {
    val isDataLoaded by libraryViewModel.isDataLoaded.collectAsState()
    val catalogRevision by libraryViewModel.catalogRevision.collectAsState()
    val booksById = remember(catalogRevision, isDataLoaded) { libraryViewModel.dataManager.booksById }
    val normalizedNamesById = remember(catalogRevision, isDataLoaded) {
        libraryViewModel.dataManager.normalizedNamesById
    }
    val context = LocalContext.current
    val historyExpanded by historyViewModel.historyExpanded.collectAsState()
    val favoritesExpanded by historyViewModel.favoritesExpanded.collectAsState()
    var isSyncing by remember { mutableStateOf(false) }
    val searchQuery by historyViewModel.searchQuery.collectAsState()
    val entriesByBookId by historyViewModel.entriesByBookId.collectAsState()

    val filteredHistory by remember(historyViewModel, libraryViewModel.dataManager, libraryViewModel.catalogRevision) {
        historyViewModel.getFilteredHistory(libraryViewModel.dataManager, libraryViewModel.catalogRevision)
    }.collectAsState()

    val filteredFavorites by remember(historyViewModel, libraryViewModel.dataManager, libraryViewModel.catalogRevision) {
        historyViewModel.getFilteredFavorites(libraryViewModel.dataManager, libraryViewModel.catalogRevision)
    }.collectAsState()

    var showAddFavoriteSheet by remember { mutableStateOf(false) }
    var selectedBookInfoId by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    val onNavigateToBook = remember(onNavigateToReader) {
        { bookId: Int, contentId: Int? ->
            onNavigateToReader(bookId, contentId, null, null, null)
        }
    }
    val onRemoveHistory = remember(historyViewModel, cloudKitSyncManager, context) {
        { id: Int ->
            val entry = historyViewModel.removeFromHistory(id)
            cloudKitSyncManager.uploadHistory(context, listOfNotNull(entry))
        }
    }
    val onToggleFavorite = remember(historyViewModel, cloudKitSyncManager, context) {
        { id: Int ->
            val entry = historyViewModel.toggleFavorite(id)
            cloudKitSyncManager.uploadHistory(context, listOfNotNull(entry))
        }
    }
    val onShowBookInfo = remember {
        { id: Int -> selectedBookInfoId = id }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            },
            topBar = {
                HistoryTopBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { historyViewModel.updateSearchQuery(it) },
                    isSyncing = isSyncing,
                    hasDonated = hasDonated,
                    onAddFavoriteClick = { showAddFavoriteSheet = true },
                    onSyncClick = {
                        scope.launch {
                            isSyncing = true
                            try {
                                val resultMsg = withContext(Dispatchers.IO) {
                                    cloudKitSyncManager.fetchChanges(
                                        context,
                                        annotationManager,
                                        historyViewModel
                                    )
                                }
                                if (resultMsg != null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        resultMsg,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } finally {
                                isSyncing = false
                            }
                        }
                    }
                )
            },
        ) { padding ->
            if (!isDataLoaded) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .fadingEdge(listState, padding.calculateTopPadding()),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 16.dp,
                        bottom = bottomPadding + 16.dp,
                    ),
                ) {
                    historySection(
                        isExpanded = historyExpanded,
                        onToggleExpand = historyViewModel::toggleHistoryExpanded,
                        historyItems = filteredHistory,
                        entriesByBookId = entriesByBookId,
                        booksById = booksById,
                        onNavigateToBook = onNavigateToBook,
                        onRemoveHistory = onRemoveHistory,
                        onToggleFavorite = onToggleFavorite,
                        onShowBookInfo = onShowBookInfo,
                    )

                    item(
                        key = "history_favorites_spacer",
                        contentType = "spacer",
                    ) {
                        Spacer(
                            modifier = Modifier
                                .height(16.dp)
                                .animateItem()
                        )
                    }

                    favoritesSection(
                        isExpanded = favoritesExpanded,
                        onToggleExpand = historyViewModel::toggleFavoritesExpanded,
                        favoriteItems = filteredFavorites,
                        entriesByBookId = entriesByBookId,
                        booksById = booksById,
                        onNavigateToBook = onNavigateToBook,
                        onRemoveFavorite = onToggleFavorite,
                        onShowBookInfo = onShowBookInfo,
                    )

                    if (!hasDonated) {
                        item(
                            key = "donation_card",
                            contentType = "donation_card",
                        ) {
                            DonationCard(modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }

        if (showAddFavoriteSheet) {
            val favoriteBookIds = remember(entriesByBookId) {
                entriesByBookId.values
                    .asSequence()
                    .filter { it.isFavorite }
                    .map { it.bookId }
                    .toSet()
            }
            AddFavoriteSheet(
                books = remember(booksById) { booksById.values.toList() },
                normalizedNamesById = normalizedNamesById,
                favoriteBookIds = favoriteBookIds,
                onDismiss = { showAddFavoriteSheet = false },
                onToggleFavorite = onToggleFavorite,
            )
        }

        if (selectedBookInfoId != null) {
            val bookName = booksById[selectedBookInfoId]?.name ?: "Unknown"
            com.maktabah.ui.reader.BookInfoSheet(
                bookId = selectedBookInfoId!!,
                defaultTitle = bookName,
                libraryViewModel = libraryViewModel,
                onDismissRequest = { selectedBookInfoId = null }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryTopBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSyncing: Boolean,
    hasDonated: Boolean,
    onAddFavoriteClick: () -> Unit,
    onSyncClick: () -> Unit
) {
    var isSearchFocused by remember { mutableStateOf(false) }

    val searchPaddingEnd by animateDpAsState(
        targetValue = if (isSearchFocused) 16.dp else 12.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "searchPaddingEnd"
    )

    TopAppBar(
        navigationIcon = {
            AnimatedVisibility(
                visible = !isSearchFocused,
                enter = expandHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    expandFrom = Alignment.Start,
                ) + fadeIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
                exit = shrinkHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    shrinkTowards = Alignment.Start,
                ) + fadeOut(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
            ) {
                if (!hasDonated) DonationIconButton()
            }
        },
        title = {
            SearchTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(R.string.history_search_placeholder),
                modifier = Modifier
                    .padding(end = searchPaddingEnd)
                    .fillMaxWidth(),
                onClearClick = { onSearchQueryChange("") },
                onFocusChanged = { isSearchFocused = it }
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        actions = {
            AnimatedVisibility(
                visible = !isSearchFocused,
                enter = expandHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    expandFrom = Alignment.End,
                ) + fadeIn(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
                exit = shrinkHorizontally(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    shrinkTowards = Alignment.End,
                ) + fadeOut(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ),
            ) {
                Row {
                    IconButton(onClick = onAddFavoriteClick) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.history_action_add_favorite)
                        )
                    }
                    IconButton(onClick = onSyncClick, enabled = !isSyncing) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.history_settings_cloudkit)
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun LazyListScope.historySection(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    historyItems: List<Int>,
    entriesByBookId: Map<Int, ReadingEntry>,
    booksById: Map<Int, BooksData>,
    onNavigateToBook: (Int, Int?) -> Unit,
    onRemoveHistory: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit,
    onShowBookInfo: (Int) -> Unit,
) {
    item(
        key = "history_header",
        contentType = "section_header",
    ) {
        SectionHeader(
            title = stringResource(R.string.history_title),
            isExpanded = isExpanded,
            onToggle = onToggleExpand,
            modifier = Modifier.animateItem()
        )
    }
    if (isExpanded) {
        if (historyItems.isEmpty()) {
            item(
                key = "history_empty",
                contentType = "empty_message",
            ) {
                Text(
                    stringResource(R.string.history_empty),
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }
        } else {
            item(
                key = "history_horizontal_grid",
                contentType = "history_grid",
            ) {
                val rowCount = when (historyItems.size) {
                    in 0..4 -> 1
                    in 5..8 -> 2
                    else -> 3
                }
                val textMeasurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val titleStyle = MaterialTheme.typography.titleMedium
                val chipHeight = remember(titleStyle, density) {
                    val lineHeightPx = textMeasurer.measure(
                        text = "Agا",
                        style = titleStyle,
                        maxLines = 1,
                    ).size.height
                    with(density) { maxOf(24.dp, lineHeightPx.toDp() + 24.dp) }
                }
                val gridHeight = chipHeight * rowCount + 8.dp * (rowCount - 1)

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LazyHorizontalStaggeredGrid(
                        rows = StaggeredGridCells.Fixed(rowCount),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(gridHeight)
                            .animateItem(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalItemSpacing = 8.dp,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = historyItems,
                            key = { bookId -> bookId },
                            contentType = { "history_chip" },
                        ) { bookId ->
                            val entry = entriesByBookId[bookId]
                            val bookName = booksById[bookId]?.name ?: "Unknown"
                            HistoryBookChip(
                                bookId = bookId,
                                bookName = bookName,
                                lastContentId = entry?.lastContentId,
                                isFavorite = entry?.isFavorite == true,
                                onNavigateToBook = onNavigateToBook,
                                onRemoveHistory = onRemoveHistory,
                                onToggleFavorite = onToggleFavorite,
                                onShowBookInfo = onShowBookInfo,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryBookChip(
    bookId: Int,
    bookName: String,
    lastContentId: Int?,
    isFavorite: Boolean,
    onNavigateToBook: (Int, Int?) -> Unit,
    onRemoveHistory: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit,
    onShowBookInfo: (Int) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        InsetGroupedItem(
            index = 0,
            lastIndex = 0,
            onClick = { onNavigateToBook(bookId, lastContentId) },
            onLongClick = { showMenu = true },
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = 12.dp
            ),
            outerPadding = PaddingValues(0.dp),
            fillMaxWidth = false,
            modifier = Modifier.widthIn(max = 200.dp),
        ) {
            Text(
                bookName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (showMenu) {
            TapCenteredPopover(
                expanded = true,
                onDismiss = { showMenu = false },
                actions = listOf(
                    PopoverMenuAction(
                        label = stringResource(R.string.history_menu_remove_history),
                        icon = Icons.Default.Delete,
                        onClick = { onRemoveHistory(bookId) },
                    ),
                    PopoverMenuAction(
                        label = if (isFavorite)
                            stringResource(R.string.history_menu_remove_favorite)
                        else
                            stringResource(R.string.history_menu_add_favorite),
                        icon = if (isFavorite) Icons.Default.StarBorder else Icons.Default.Star,
                        onClick = { onToggleFavorite(bookId) },
                    ),
                    PopoverMenuAction(
                        label = stringResource(R.string.reader_menu_book_info),
                        icon = Icons.Default.Info,
                        onClick = { onShowBookInfo(bookId) },
                    ),
                ),
            )
        }
    }
}

private fun LazyListScope.favoritesSection(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    favoriteItems: List<Int>,
    entriesByBookId: Map<Int, ReadingEntry>,
    booksById: Map<Int, BooksData>,
    onNavigateToBook: (Int, Int?) -> Unit,
    onRemoveFavorite: (Int) -> Unit,
    onShowBookInfo: (Int) -> Unit,
) {
    item(
        key = "favorites_header",
        contentType = "section_header",
    ) {
        SectionHeader(
            title = stringResource(R.string.history_favorites_title),
            isExpanded = isExpanded,
            onToggle = onToggleExpand,
            modifier = Modifier.animateItem()
        )
    }
    if (isExpanded) {
        if (favoriteItems.isEmpty()) {
            item(
                key = "favorites_empty",
                contentType = "empty_message",
            ) {
                Text(
                    stringResource(R.string.history_favorites_empty),
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }
        } else {
            items(
                count = favoriteItems.size,
                key = { index -> "fav_${favoriteItems[index]}" },
                contentType = { "favorite_row" },
            ) { index ->
                val bookId = favoriteItems[index]
                val entry = entriesByBookId[bookId]
                val bookName = booksById[bookId]?.name ?: "Unknown"

                FavoriteBookRow(
                    bookId = bookId,
                    bookName = bookName,
                    lastContentId = entry?.lastContentId,
                    onNavigateToBook = onNavigateToBook,
                    onRemoveFavorite = onRemoveFavorite,
                    onShowBookInfo = onShowBookInfo,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun FavoriteBookRow(
    bookId: Int,
    bookName: String,
    lastContentId: Int?,
    onNavigateToBook: (Int, Int?) -> Unit,
    onRemoveFavorite: (Int) -> Unit,
    onShowBookInfo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            InsetGroupedItem(
                index = 0,
                lastIndex = 0,
                onClick = { onNavigateToBook(bookId, lastContentId) },
                onLongClick = { showMenu = true },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                outerPadding = PaddingValues(0.dp),
            ) {
                Text(
                    bookName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showMenu) {
                TapCenteredPopover(
                    expanded = true,
                    onDismiss = { showMenu = false },
                    actions = listOf(
                        PopoverMenuAction(
                            label = stringResource(R.string.history_menu_remove_favorite),
                            icon = Icons.Default.Delete,
                            onClick = { onRemoveFavorite(bookId) },
                        ),
                        PopoverMenuAction(
                            label = stringResource(R.string.reader_menu_book_info),
                            icon = Icons.Default.Info,
                            onClick = { onShowBookInfo(bookId) },
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "ArrowRotation",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.rotate(rotationAngle),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFavoriteSheet(
    books: List<BooksData>,
    normalizedNamesById: Map<Int, String>,
    favoriteBookIds: Set<Int>,
    onDismiss: () -> Unit,
    onToggleFavorite: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    var searchFavQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf(searchFavQuery) }

    LaunchedEffect(searchFavQuery) {
        if (searchFavQuery.isNotBlank()) {
            delay(300.milliseconds)
        }
        debouncedQuery = searchFavQuery
    }

    var filteredBooks by remember(books) { mutableStateOf(books) }

    LaunchedEffect(books, normalizedNamesById, debouncedQuery) {
        if (debouncedQuery.isBlank()) {
            filteredBooks = books
        } else {
            val result = withContext(Dispatchers.Default) {
                val cleanQuery = debouncedQuery.normalizeArabic()
                books.filter { book ->
                    val normalizedName = normalizedNamesById[book.id] ?: book.name.normalizeArabic()
                    normalizedName.contains(cleanQuery, ignoreCase = true)
                }
            }
            filteredBooks = result
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            val topPadding = 54.dp

            if (filteredBooks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.history_empty),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .consumeBottomSheetNestedScroll()
                            .fillMaxSize()
                            .fadingEdge(listState, 48.dp),
                        state = listState,
                        contentPadding = PaddingValues(top = topPadding, bottom = 32.dp)
                    ) {
                        itemsIndexed(
                            items = filteredBooks,
                            key = { _, book -> book.id },
                            contentType = { _, _ -> "add_favorite_book" },
                        ) { index, book ->
                            AddFavoriteBookRow(
                                index = index,
                                lastIndex = filteredBooks.lastIndex,
                                bookId = book.id,
                                bookName = book.name,
                                isFavorite = book.id in favoriteBookIds,
                                onToggleFavorite = onToggleFavorite,
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                SearchTextField(
                    value = searchFavQuery,
                    onValueChange = { searchFavQuery = it },
                    placeholder = stringResource(R.string.library_search_books_placeholder),
                    onClearClick = { searchFavQuery = "" }
                )
            }
        }
    }
}

@Composable
private fun AddFavoriteBookRow(
    index: Int,
    lastIndex: Int,
    bookId: Int,
    bookName: String,
    isFavorite: Boolean,
    onToggleFavorite: (Int) -> Unit,
) {
    InsetGroupedItem(
        index = index,
        lastIndex = lastIndex,
        onClick = { onToggleFavorite(bookId) },
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = bookName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            if (isFavorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
