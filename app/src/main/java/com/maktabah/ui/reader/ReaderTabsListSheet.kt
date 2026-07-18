package com.maktabah.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.maktabah.R
import com.maktabah.models.ReaderTab
import com.maktabah.ui.common.SwipeDeleteBackground
import com.maktabah.ui.common.isSwipeTargetReached
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.maktabah.ui.common.rememberBottomSheetNestedScrollConnection

/**
 * Sheet daftar semua tab — mengacu iOSReaderTabsPopoverView.swift.
 * Swipe untuk menutup tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTabsListSheet(
    tabs: List<ReaderTab>,
    activeTabId: String?,
    onSwitch: (String) -> Unit,
    onClose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = { WindowInsets(0.dp) },
    ) {
        val initialIndex = remember {
            val idx = tabs.indexOfFirst { it.id == activeTabId }
            if (idx != -1) idx else 0
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState(
            initialFirstVisibleItemIndex = initialIndex
        )
        val nestedScrollConnection = rememberBottomSheetNestedScrollConnection(listState)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides
                    LayoutDirection.Rtl
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .nestedScroll(nestedScrollConnection)
                        .fillMaxSize()
                        .padding(bottom = 32.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp, 
                        end = 16.dp, 
                        top = 8.dp, 
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                    val isActive = tab.id == activeTabId
                    var itemWidth by remember { mutableIntStateOf(0) }
                    var dismissStateRef by remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
                    val dismissState =
                        rememberSwipeToDismissBoxState(
                            positionalThreshold = { it * 0.5f },
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    if (dismissStateRef?.isSwipeTargetReached(itemWidth) != true) {
                                        return@rememberSwipeToDismissBoxState false
                                    }
                                    onClose(tab.id)
                                    true
                                } else {
                                    true
                                }
                            },
                        )
                    dismissStateRef = dismissState

                    SwipeToDismissBox(
                        modifier = Modifier.onSizeChanged { itemWidth = it.width }.animateItem(),
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            SwipeDeleteBackground(
                                dismissState = dismissState,
                                icon = Icons.Default.Close,
                                shape = RoundedCornerShape(30.dp),
                                contentDescription = stringResource(R.string.reader_tabs_close)
                            )
                        },
                    ) {
                        Surface(
                            shape = RoundedCornerShape(30.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSwitch(tab.id)
                                    onDismiss()
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                            ) {
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(
                                                color = Color(
                                                    0xFF34C759
                                                ),
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                                    50
                                                ),
                                            ),
                                    )
                                    Spacer(modifier = Modifier.size(8.dp))
                                }
                                Text(
                                    text = tab.bookName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                                    color = if (isActive)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}}
