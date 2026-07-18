package com.maktabah.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.maktabah.ui.common.rememberBottomSheetNestedScrollConnection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.models.FlashTarget
import com.maktabah.models.SearchMode
import com.maktabah.ui.common.InsetGroupedItem
import com.maktabah.ui.common.fadingEdge
import com.maktabah.ui.library.LibraryViewModel
import com.maktabah.ui.search.BookSearchViewModel
import com.maktabah.ui.search.QueryInputBar
import com.maktabah.ui.search.SearchHelpDialog
import com.maktabah.ui.search.SearchHistoryOverlay
import com.maktabah.ui.search.buildHighlightedText
import com.maktabah.utils.convertToArabicDigits
import com.maktabah.utils.normalizeArabic
import com.maktabah.utils.snippetAround
import com.maktabah.utils.stripSpanTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSearchSheet(
    bookId: Int,
    libraryViewModel: LibraryViewModel,
    viewModel: ReaderViewModel,
    onDismissRequest: () -> Unit,
) {
    val bookSearchViewModel: BookSearchViewModel =
        androidx.lifecycle.viewmodel.compose
            .viewModel()
    val query by bookSearchViewModel.query.collectAsState()
    val lastSearchQuery by bookSearchViewModel.lastSearchQuery.collectAsState()
    val results by bookSearchViewModel.results.collectAsState()
    val isSearching by bookSearchViewModel.isSearching.collectAsState()
    val searchMode by bookSearchViewModel.searchMode.collectAsState()
    val searchProgress by bookSearchViewModel.searchProgress.collectAsState()
    val searchHistory by bookSearchViewModel.searchHistory.collectAsState()

    val book = libraryViewModel.dataManager.booksById[bookId]
    var isFocused by remember { mutableStateOf(false) }
    var isHistoryVisible by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        bookSearchViewModel.loadHistory(context)
    }

    LaunchedEffect(isFocused) {
        if (isFocused) {
            isHistoryVisible = true
        }
    }

    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        val focusManager = LocalFocusManager.current
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val nestedScrollConnection = rememberBottomSheetNestedScrollConnection(listState)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .background(androidx.compose.ui.graphics.Color.Transparent)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        isHistoryVisible = false
                        focusManager.clearFocus()
                    })
                }
        ) {
            val topPadding = 68.dp

            androidx.compose.runtime.CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .nestedScroll(nestedScrollConnection)
                        .fillMaxSize()
                        .fadingEdge(listState, 48.dp),
                    state = listState,
                    contentPadding = PaddingValues(top = topPadding, bottom = 32.dp),
                ) {
                        items(
                            count = results.size,
                            key = { results[it].id },
                        ) { index ->
                            val contentItem = results[index]
                            InsetGroupedItem(
                                index = index,
                                lastIndex = results.lastIndex,
                                onClick = {
                                    if (viewModel.currentContent.value?.id != contentItem.id) {
                                        viewModel.loadContentById(contentItem.id)
                                    }
                                    viewModel.setSearchQuery(lastSearchQuery)
                                    viewModel.setFlashTarget(
                                        FlashTarget(
                                            query = lastSearchQuery,
                                        ),
                                    )
                                    onDismissRequest()
                                },
                                color = MaterialTheme.colorScheme.surface,
                                contentPadding = PaddingValues(
                                    horizontal = 16.dp,
                                    vertical = 12.dp
                                ),
                                dividerStartPadding = Dp.Hairline,
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "ج${contentItem.part} ص${contentItem.page}".convertToArabicDigits(),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f),
                                            textAlign = TextAlign.Start,
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val searchKeywords = remember(lastSearchQuery, searchMode) {
                                        val normalized = lastSearchQuery.normalizeArabic()
                                        if (normalized.isBlank()) emptyList() else {
                                            when (searchMode) {
                                                SearchMode.PHRASE -> listOf(normalized)
                                                else -> normalized.split(" ")
                                                    .filter { it.isNotBlank() }
                                            }.map { it.convertToArabicDigits() }
                                        }
                                    }
                                    val displayText = remember(contentItem.nass, searchKeywords) {
                                        val stripped = contentItem.nass.stripSpanTags()
                                        val normalized = stripped.convertToArabicDigits()
                                        val cleanNash = normalized.normalizeArabic()
                                        cleanNash.snippetAround(searchKeywords, contextLength = 60)
                                    }
                                    val highlightedText = buildHighlightedText(
                                        text = displayText,
                                        query = lastSearchQuery,
                                        mode = searchMode,
                                    )
                                    Text(
                                        text = highlightedText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }

            if (isSearching) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = topPadding, bottom = 16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val progress = searchProgress
                        if (progress != null && progress.second > 0) {
                            val current = progress.first
                            val total = progress.second
                            val progressFraction = current.toFloat() / total.toFloat()
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 32.dp),
                            )
                            Text(
                                text = stringResource(
                                    R.string.reader_search_reading_lines,
                                    current,
                                    total
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
            }

            QueryInputBar(
                query = query,
                onQueryChange = {
                    bookSearchViewModel.updateQuery(it)
                    if (it.isEmpty()) {
                        isHistoryVisible = true
                    }
                },
                onSearch = {
                    isHistoryVisible = false
                    focusManager.clearFocus()
                    if (book != null) {
                        val archivePath =
                            java.io.File(
                                context.filesDir,
                                "${book.archive}.sqlite"
                            ).absolutePath
                        bookSearchViewModel.search(context, book.id, archivePath)
                    }
                },
                canSearch = query.isNotBlank(),
                placeholder = stringResource(R.string.reader_search_book_placeholder),
                modifier = Modifier.fillMaxWidth(),
                onFocusChanged = {
                    isFocused = it
                    if (!it) {
                        isHistoryVisible = false
                    }
                }
            )

            if (isHistoryVisible) {
                SearchHistoryOverlay(
                    searchHistory = searchHistory,
                    onClearAll = { bookSearchViewModel.clearHistory(context) },
                    onHistoryClick = { historyQuery ->
                        isHistoryVisible = false
                        bookSearchViewModel.updateQuery(historyQuery)
                        focusManager.clearFocus()
                        if (book != null) {
                            val archivePath =
                                java.io.File(
                                    context.filesDir,
                                    "${book.archive}.sqlite"
                                ).absolutePath
                            bookSearchViewModel.search(context, book.id, archivePath)
                        }
                    },
                    onRemoveHistory = { historyQuery ->
                        bookSearchViewModel.removeFromHistory(context, historyQuery)
                    },
                    activeMode = searchMode,
                    onModeSelect = { bookSearchViewModel.updateSearchMode(it) },
                    onHelpClick = { showHelpDialog = true },
                    modifier = Modifier
                        .padding(top = 68.dp)
                )
            }

            if (showHelpDialog) {
                SearchHelpDialog(onDismiss = { showHelpDialog = false })
            }
        }
    }
}
