package com.maktabah.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

@Composable
fun rememberBottomSheetNestedScrollConnection(
    listState: LazyListState
): NestedScrollConnection {
    return remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Return Offset.Zero so that LazyColumn can handle scrolling itself
                // (This avoids freezing the list)
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                return Velocity.Zero
            }

            // To prevent the bottom sheet from dragging when not at top,
            // we intercept the events *after* the LazyColumn has scrolled.
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                // If there's unconsumed drag remaining (meaning LazyColumn can't scroll anymore),
                // we consume it ONLY IF we're not at the top, to prevent the sheet from dragging.
                if (available.y > 0f) {
                    val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                    if (!isAtTop) {
                        return Offset(0f, available.y)
                    }
                }
                return Offset.Zero
            }
        }
    }
}
