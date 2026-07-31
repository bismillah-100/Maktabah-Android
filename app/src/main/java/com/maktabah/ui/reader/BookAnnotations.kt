package com.maktabah.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.maktabah.ui.common.rememberBottomSheetNestedScrollConnection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.models.Annotation
import com.maktabah.models.AnnotationSearchScope
import com.maktabah.models.FlashTarget
import com.maktabah.ui.annotation.AnnotationItem
import com.maktabah.ui.common.fadingEdge
import com.maktabah.ui.search.SearchWithScope
import com.maktabah.utils.normalizeArabic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookAnnotationsSheet(
    bookAnnotations: List<Annotation>,
    annotationManager: AnnotationManager,
    viewModel: ReaderViewModel,
    onDismissRequest: () -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.annotationListIndex.intValue,
        initialFirstVisibleItemScrollOffset = viewModel.annotationListOffset.intValue
    )

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewModel.annotationListIndex.intValue = index
            viewModel.annotationListOffset.intValue = offset
        }
    }

    val nestedScrollConnection = rememberBottomSheetNestedScrollConnection(listState)
    var annotationSearchQuery by viewModel.annotationSearchQuery
    var annotationSearchScope by viewModel.annotationSearchScope
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            var debouncedQuery by remember { mutableStateOf(annotationSearchQuery) }

            LaunchedEffect(annotationSearchQuery) {
                if (annotationSearchQuery.isNotBlank()) {
                    delay(200.milliseconds)
                }
                debouncedQuery = annotationSearchQuery
            }

            var filteredAnnotations by remember { mutableStateOf(bookAnnotations) }

            LaunchedEffect(bookAnnotations, debouncedQuery, annotationSearchScope) {
                if (debouncedQuery.isBlank()) {
                    filteredAnnotations = bookAnnotations
                } else {
                    val result = withContext(Dispatchers.Default) {
                        val normalizedQuery = debouncedQuery.normalizeArabic()
                        bookAnnotations.filter { ann ->
                            val matchesContext = (annotationSearchScope == AnnotationSearchScope.ALL || annotationSearchScope == AnnotationSearchScope.CONTEXT) &&
                                ann.context.normalizeArabic().contains(normalizedQuery, ignoreCase = true)

                            val matchesNote = (annotationSearchScope == AnnotationSearchScope.ALL || annotationSearchScope == AnnotationSearchScope.NOTE) &&
                                (ann.note?.normalizeArabic()?.contains(normalizedQuery, ignoreCase = true) == true)

                            val matchesTag = (annotationSearchScope == AnnotationSearchScope.ALL || annotationSearchScope == AnnotationSearchScope.TAG) &&
                                ann.tags.normalizeArabic().contains(normalizedQuery, ignoreCase = true)

                            matchesContext || matchesNote || matchesTag
                        }
                    }
                    filteredAnnotations = result
                }
            }

            val topPadding = if (annotationSearchQuery.isNotEmpty()) 100.dp else 54.dp

            if (filteredAnnotations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(top = topPadding), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.reader_annotations_no_results),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .nestedScroll(nestedScrollConnection)
                            .fillMaxSize()
                            .fadingEdge(listState, 48.dp),
                        state = listState,
                        contentPadding = PaddingValues(top = topPadding, bottom = 32.dp),
                    ) {
                        itemsIndexed(
                            items = filteredAnnotations,
                            key = { _, ann ->
                                ann.id ?: java.util.UUID
                                    .randomUUID()
                                    .toString()
                            },
                        ) { index, ann ->
                            AnnotationItem(
                                ann = ann,
                                index = index,
                                lastIndex = filteredAnnotations.size - 1,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                onClick = {
                                    if (viewModel.currentContent.value?.id != ann.contentId) {
                                        viewModel.loadContentById(ann.contentId)
                                    }
                                    viewModel.setFlashTarget(
                                        FlashTarget(
                                            loc = ann.rangeLocation,
                                            len = ann.rangeLength,
                                        ),
                                    )
                                    onDismissRequest()
                                },
                                onDelete = {
                                    withContext(Dispatchers.IO) {
                                        ann.id?.let {
                                            annotationManager.deleteAnnotation(it, ann.ckRecordId)
                                        }
                                    }
                                    viewModel.refreshAnnotations()
                                    null
                                },
                                onDeleteComplete = {
                                    filteredAnnotations = filteredAnnotations.filterNot { it.id == ann.id }
                                },
                                dividerStartPadding = Dp.Hairline
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                SearchWithScope(
                    searchQuery = annotationSearchQuery,
                    onSearchQueryChange = { annotationSearchQuery = it },
                    searchScope = annotationSearchScope,
                    onSearchScopeChange = { annotationSearchScope = it },
                    placeholder = stringResource(R.string.reader_annotations_search_placeholder)
                )
            }
        }
    }
}
