package com.maktabah.ui.history

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.maktabah.ui.common.rememberBottomSheetNestedScrollConnection
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.cloudKit.CloudKitSyncManager
import com.maktabah.database.AnnotationManager
import com.maktabah.models.ReadingEntry
import com.maktabah.utils.normalizeArabic
import com.maktabah.ui.common.DonationCard
import com.maktabah.ui.common.DonationIconButton
import com.maktabah.ui.common.InsetGroupedItem
import com.maktabah.ui.common.fadingEdge
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.ui.search.SearchTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("HistoryPrefs", android.content.Context.MODE_PRIVATE) }
    var historyExpanded by remember { mutableStateOf(sharedPrefs.getBoolean("historyExpanded", true)) }
    var favoritesExpanded by remember { mutableStateOf(sharedPrefs.getBoolean("favoritesExpanded", true)) }
    var isSyncing by remember { mutableStateOf(false) }
    val searchQuery by historyViewModel.searchQuery.collectAsState()
    val entriesByBookId by historyViewModel.entriesByBookId.collectAsState()

    val filteredHistory by remember(historyViewModel, libraryViewModel.dataManager) {
        historyViewModel.getFilteredHistory(libraryViewModel.dataManager)
    }.collectAsState()

    val filteredFavorites by remember(historyViewModel, libraryViewModel.dataManager) {
        historyViewModel.getFilteredFavorites(libraryViewModel.dataManager)
    }.collectAsState()

    var showAddFavoriteSheet by remember { mutableStateOf(false) }
    var selectedHistoryItem by remember { mutableStateOf<Int?>(null) }
    var selectedFavoriteItem by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            },
            topBar = {
                HistoryTopBar(
                    searchQuery = searchQuery.normalizeArabic(),
                    onSearchQueryChange = { historyViewModel.updateSearchQuery(it) },
                    isSyncing = isSyncing,
                    hasDonated = hasDonated,
                    onAddFavoriteClick = { showAddFavoriteSheet = true },
                    onSyncClick = {
                        scope.launch {
                            isSyncing = true
                            val resultMsg = withContext(Dispatchers.IO) {
                                cloudKitSyncManager.fetchChanges(
                                    context,
                                    annotationManager,
                                    historyViewModel
                                )
                            }
                            withContext(Dispatchers.Main) {
                                if (resultMsg != null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        resultMsg,
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                            isSyncing = false
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
                        onToggleExpand = { 
                            historyExpanded = !historyExpanded
                            sharedPrefs.edit().putBoolean("historyExpanded", historyExpanded).apply()
                        },
                        historyItems = filteredHistory,
                        entriesByBookId = entriesByBookId,
                        bookById = { libraryViewModel.dataManager.booksById[it] },
                        onNavigateToReader = onNavigateToReader,
                        onLongClick = { selectedHistoryItem = it }
                    )

                    item(key = "history_favorites_spacer") {
                        Spacer(
                            modifier = Modifier
                                .height(16.dp)
                                .animateItem()
                        )
                    }

                    favoritesSection(
                        isExpanded = favoritesExpanded,
                        onToggleExpand = { 
                            favoritesExpanded = !favoritesExpanded
                            sharedPrefs.edit().putBoolean("favoritesExpanded", favoritesExpanded).apply()
                        },
                        favoriteItems = filteredFavorites,
                        entriesByBookId = entriesByBookId,
                        bookById = { libraryViewModel.dataManager.booksById[it] },
                        onNavigateToReader = onNavigateToReader,
                        onLongClick = { selectedFavoriteItem = it }
                    )

                    if (!hasDonated) {
                        item(key = "donation_card") {
                            DonationCard(modifier = Modifier.animateItem())
                        }
                    }
                }
            }
        }

        if (selectedHistoryItem != null) {
            HistoryOptionsDialog(
                bookId = selectedHistoryItem!!,
                isFavorite = entriesByBookId[selectedHistoryItem]?.isFavorite == true,
                onDismiss = { selectedHistoryItem = null },
                onRemoveHistory = {
                    val entry = historyViewModel.removeFromHistory(it)
                    if (entry != null) scope.launch {
                        cloudKitSyncManager.syncHistoryAndFavorites(
                            context,
                            listOf(entry)
                        )
                    }
                    selectedHistoryItem = null
                },
                onToggleFavorite = {
                    val entry = historyViewModel.toggleFavorite(it)
                    scope.launch {
                        cloudKitSyncManager.syncHistoryAndFavorites(
                            context,
                            listOf(entry)
                        )
                    }
                    selectedHistoryItem = null
                }
            )
        }

        if (selectedFavoriteItem != null) {
            FavoriteOptionsDialog(
                bookId = selectedFavoriteItem!!,
                onDismiss = { selectedFavoriteItem = null },
                onRemoveFavorite = {
                    val entry = historyViewModel.toggleFavorite(it)
                    scope.launch {
                        cloudKitSyncManager.syncHistoryAndFavorites(
                            context,
                            listOf(entry)
                        )
                    }
                    selectedFavoriteItem = null
                }
            )
        }

        if (showAddFavoriteSheet) {
            AddFavoriteSheet(
                books = libraryViewModel.dataManager.booksById.values.toList(),
                entriesByBookId = entriesByBookId,
                onDismiss = { showAddFavoriteSheet = false },
                onToggleFavorite = { bookId ->
                    val entry = historyViewModel.toggleFavorite(bookId)
                    scope.launch {
                        cloudKitSyncManager.syncHistoryAndFavorites(
                            context,
                            listOf(entry)
                        )
                    }
                }
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
    TopAppBar(
        navigationIcon = { if (!hasDonated) DonationIconButton() },
        title = {
            SearchTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(R.string.history_search_placeholder),
                modifier = Modifier.padding(end = 12.dp),
                onClearClick = { onSearchQueryChange("") }
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        actions = {
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
        },
    )
}

private fun LazyListScope.historySection(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    historyItems: List<Int>,
    entriesByBookId: Map<Int, ReadingEntry>,
    bookById: (Int) -> com.maktabah.models.BooksData?,
    onNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit,
    onLongClick: (Int) -> Unit
) {
    item(key = "history_header") {
        SectionHeader(
            title = stringResource(R.string.history_title),
            isExpanded = isExpanded,
            onToggle = onToggleExpand,
            modifier = Modifier.animateItem()
        )
    }
    if (isExpanded) {
        if (historyItems.isEmpty()) {
            item(key = "history_empty") {
                Text(
                    stringResource(R.string.history_empty),
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 32.dp, vertical = 8.dp)
                )
            }
        } else {
            item(key = "history_horizontal_grid") {
                val rowCount = when (historyItems.size) {
                    in 0..4 -> 1
                    in 5..8 -> 2
                    else -> 3
                }
                val scrollState = rememberScrollState()

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scrollState)
                            .animateItem()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (rowIndex in 0 until rowCount) {
                                val rowItems = historyItems.filterIndexed { index, _ -> index % rowCount == rowIndex }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    for (bookId in rowItems) {
                                        val entry = entriesByBookId[bookId]
                                        val bookName = bookById(bookId)?.name ?: "Unknown"

                                        InsetGroupedItem(
                                            index = 0,
                                            lastIndex = 0,
                                            onClick = {
                                                onNavigateToReader(
                                                    bookId,
                                                    entry?.lastContentId,
                                                    null,
                                                    null,
                                                    null
                                                )
                                            },
                                            onLongClick = { onLongClick(bookId) },
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
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.favoritesSection(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    favoriteItems: List<Int>,
    entriesByBookId: Map<Int, ReadingEntry>,
    bookById: (Int) -> com.maktabah.models.BooksData?,
    onNavigateToReader: (Int, Int?, Int?, Int?, String?) -> Unit,
    onLongClick: (Int) -> Unit
) {
    item(key = "favorites_header") {
        SectionHeader(
            title = stringResource(R.string.history_favorites_title),
            isExpanded = isExpanded,
            onToggle = onToggleExpand,
            modifier = Modifier.animateItem()
        )
    }
    if (isExpanded) {
        if (favoriteItems.isEmpty()) {
            item(key = "favorites_empty") {
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
                key = { index -> "fav_${favoriteItems[index]}" }) { index ->
                val bookId = favoriteItems[index]
                val entry = entriesByBookId[bookId]
                val bookName = bookById(bookId)?.name ?: "Unknown"

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    InsetGroupedItem(
                        index = 0,
                        lastIndex = 0,
                        onClick = {
                            onNavigateToReader(
                                bookId,
                                entry?.lastContentId,
                                null,
                                null,
                                null
                            )
                        },
                        onLongClick = { onLongClick(bookId) },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        outerPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.animateItem(),
                    ) {
                        Text(
                            bookName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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

@Composable
private fun HistoryOptionsDialog(
    bookId: Int,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onRemoveHistory: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_action_more_options)) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            Column {
                TextButton(onClick = { onRemoveHistory(bookId) }) { Text(stringResource(R.string.history_menu_remove_history)) }
                TextButton(onClick = { onToggleFavorite(bookId) }) {
                    Text(
                        if (isFavorite) stringResource(R.string.history_menu_remove_favorite) else stringResource(
                            R.string.history_menu_add_favorite
                        )
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_tabs_close)) } },
    )
}

@Composable
private fun FavoriteOptionsDialog(
    bookId: Int,
    onDismiss: () -> Unit,
    onRemoveFavorite: (Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_action_more_options)) },
        containerColor = MaterialTheme.colorScheme.surface,
        text = {
            TextButton(onClick = { onRemoveFavorite(bookId) }) { Text(stringResource(R.string.history_menu_remove_favorite)) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_tabs_close)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFavoriteSheet(
    books: List<com.maktabah.models.BooksData>,
    entriesByBookId: Map<Int, ReadingEntry>,
    onDismiss: () -> Unit,
    onToggleFavorite: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val nestedScrollConnection = rememberBottomSheetNestedScrollConnection(listState)
    var searchFavQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf(searchFavQuery) }

    androidx.compose.runtime.LaunchedEffect(searchFavQuery) {
        if (searchFavQuery.isNotBlank()) {
            delay(300.milliseconds)
        }
        debouncedQuery = searchFavQuery
    }

    var filteredBooks by remember { mutableStateOf(books) }

    androidx.compose.runtime.LaunchedEffect(books, debouncedQuery) {
        if (debouncedQuery.isBlank()) {
            filteredBooks = books
        } else {
            val result = withContext(Dispatchers.Default) {
                val cleanQuery = debouncedQuery.normalizeArabic()
                books.filter { it.name.normalizeArabic().contains(cleanQuery, ignoreCase = true) }
            }
            filteredBooks = result
        }
    }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

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
                            .nestedScroll(nestedScrollConnection)
                            .fillMaxSize()
                            .fadingEdge(listState, 48.dp),
                        state = listState,
                        contentPadding = PaddingValues(top = topPadding, bottom = 32.dp)
                    ) {
                        itemsIndexed(
                            items = filteredBooks,
                            key = { _, book -> book.id }
                        ) { index, book ->
                            val isFav = entriesByBookId[book.id]?.isFavorite == true
                            InsetGroupedItem(
                                index = index,
                                lastIndex = filteredBooks.lastIndex,
                                onClick = { onToggleFavorite(book.id) },
                                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = book.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isFav) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = "Favorite",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
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
