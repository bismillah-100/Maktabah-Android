package com.maktabah.ui.history

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyHorizontalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
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
    var historyExpanded by remember { mutableStateOf(true) }
    var favoritesExpanded by remember { mutableStateOf(true) }
    var isSyncing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddFavoriteSheet by remember { mutableStateOf(false) }
    var selectedHistoryItem by remember { mutableStateOf<Int?>(null) }
    var selectedFavoriteItem by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    val entriesByBookId by historyViewModel.entriesByBookId.collectAsState()
    val historyOrder by historyViewModel.historyOrder.collectAsState()

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
                    onSearchQueryChange = { searchQuery = it },
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
            val filteredHistory = remember(historyOrder, searchQuery) {
                if (searchQuery.isBlank()) historyOrder
                else historyOrder.filter {
                    libraryViewModel.dataManager.booksById[it]?.name?.contains(
                        searchQuery,
                        ignoreCase = true
                    ) == true
                }
            }
            val favoriteIds = historyViewModel.getFavoriteBookIds()
            val filteredFavorites = remember(favoriteIds, searchQuery) {
                if (searchQuery.isBlank()) favoriteIds
                else favoriteIds.filter {
                    libraryViewModel.dataManager.booksById[it]?.name?.contains(
                        searchQuery,
                        ignoreCase = true
                    ) == true
                }
            }

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
                    onToggleExpand = { historyExpanded = !historyExpanded },
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
                    onToggleExpand = { favoritesExpanded = !favoritesExpanded },
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
                    historyViewModel.toggleFavorite(it)
                    val entry = historyViewModel.entriesByBookId.value[it]
                    if (entry != null) scope.launch {
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
                    historyViewModel.toggleFavorite(it)
                    val entry = historyViewModel.entriesByBookId.value[it]
                    if (entry != null) scope.launch {
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
                    historyViewModel.toggleFavorite(bookId)
                    val entry = historyViewModel.entriesByBookId.value[bookId]
                    if (entry != null) scope.launch {
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
                val gridHeight = (rowCount * 46 + (rowCount - 1) * 8).dp

                LazyHorizontalStaggeredGrid(
                    rows = StaggeredGridCells.Fixed(rowCount),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalItemSpacing = 8.dp,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(gridHeight)
                        .animateItem(),
                ) {
                    items(items = historyItems, key = { bookId -> "hist_book_$bookId" }) { bookId ->
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
    var searchFavQuery by remember { mutableStateOf("") }
    val filteredBooks = remember(searchFavQuery) {
        val cleanQuery = searchFavQuery.normalizeArabic()
        if (cleanQuery.isBlank()) books
        else books.filter { it.name.normalizeArabic().contains(cleanQuery, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.history_add_favorite_title),
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.reader_tabs_close)
                    )
                }
            }
            SearchTextField(
                value = searchFavQuery,
                onValueChange = { searchFavQuery = it },
                placeholder = stringResource(R.string.library_search_books_placeholder),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    count = filteredBooks.size,
                    key = { index -> filteredBooks[index].id }
                ) { index ->
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        val book = filteredBooks[index]
                        val isFav = entriesByBookId[book.id]?.isFavorite == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleFavorite(book.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = book.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (isFav) Icon(
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
