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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.database.AnnotationManager
import com.maktabah.models.Annotation
import com.maktabah.models.FlashTarget
import com.maktabah.ui.annotation.AnnotationItem
import com.maktabah.ui.common.fadingEdge
import com.maktabah.ui.search.SearchTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookAnnotationsSheet(
    bookAnnotations: List<Annotation>,
    annotationManager: AnnotationManager,
    viewModel: ReaderViewModel,
    onDismissRequest: () -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var annotationSearchQuery by remember { mutableStateOf("") }
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
            val topPadding = 54.dp
            val filteredAnnotations =
                remember(bookAnnotations, annotationSearchQuery) {
                    if (annotationSearchQuery.isBlank()) {
                        bookAnnotations
                    } else {
                        bookAnnotations.filter { ann ->
                            ann.context.contains(annotationSearchQuery, ignoreCase = true) ||
                                (ann.note?.contains(
                                    annotationSearchQuery,
                                    ignoreCase = true
                                ) == true)
                        }
                    }
                }

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
                                    null
                                },
                                onDeleteComplete = {
                                    viewModel.refreshAnnotations()
                                },
                                dividerStartPadding = Dp.Hairline
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp)) {
                SearchTextField(
                    value = annotationSearchQuery,
                    onValueChange = { annotationSearchQuery = it },
                    placeholder = stringResource(R.string.reader_annotations_search_placeholder),
                    onClearClick = { annotationSearchQuery = "" }
                )
            }
        }
    }
}
