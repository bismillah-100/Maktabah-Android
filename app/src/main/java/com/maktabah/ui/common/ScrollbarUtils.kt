package com.maktabah.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Extension modifier to draw a vertical scrollbar on a LazyColumn that fades in/out automatically.
 */
@Composable
fun Modifier.drawVerticalScrollbar(
    state: LazyListState,
    width: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val targetAlpha = if (state.isScrollInProgress) 1f else 0f
    val duration = if (state.isScrollInProgress) 150 else 500
    
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration),
        label = "ScrollbarAlpha"
    )

    return drawWithContent {
        drawContent()

        val layoutInfo = state.layoutInfo
        val visibleItemsInfo = layoutInfo.visibleItemsInfo
        if (visibleItemsInfo.isNotEmpty() && alpha > 0f) {
            val totalItemsCount = layoutInfo.totalItemsCount
            val firstVisibleItem = visibleItemsInfo.first()
            val lastVisibleItem = visibleItemsInfo.last()
            
            val totalHeight = (lastVisibleItem.offset + lastVisibleItem.size) - firstVisibleItem.offset
            val averageItemSize = totalHeight.toFloat() / visibleItemsInfo.size
            
            val estimatedTotalHeight = averageItemSize * totalItemsCount
            val viewportHeightFull = layoutInfo.viewportSize.height.toFloat()
            val topPaddingPx = topPadding.toPx()
            val bottomPaddingPx = bottomPadding.toPx()
            val viewportHeight = viewportHeightFull - topPaddingPx - bottomPaddingPx
            
            // Only draw if content is actually scrollable within the padded viewport
            if (estimatedTotalHeight <= viewportHeight && !state.canScrollForward && !state.canScrollBackward) {
                return@drawWithContent
            }

            val scrollOffset = firstVisibleItem.index * averageItemSize + state.firstVisibleItemScrollOffset
            
            drawScrollbarInternal(
                alpha = alpha,
                scrollOffset = scrollOffset,
                totalHeight = estimatedTotalHeight,
                viewportHeightFull = viewportHeightFull,
                topPaddingPx = topPaddingPx,
                bottomPaddingPx = bottomPaddingPx,
                widthPx = width.toPx(),
                color = color,
                layoutDirection = layoutDirection
            )
        }
    }
}

/**
 * Extension modifier to draw a vertical scrollbar on a generic component.
 */
@Composable
fun Modifier.drawGenericVerticalScrollbar(
    isScrollInProgress: Boolean,
    scrollOffset: Float,
    scrollRange: Float,
    scrollExtent: Float,
    width: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val targetAlpha = if (isScrollInProgress) 1f else 0f
    val duration = if (isScrollInProgress) 150 else 500
    
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration),
        label = "ScrollbarAlpha"
    )

    return drawWithContent {
        drawContent()

        if (scrollRange > scrollExtent && alpha > 0f) {
            drawScrollbarInternal(
                alpha = alpha,
                scrollOffset = scrollOffset,
                totalHeight = scrollRange,
                viewportHeightFull = size.height,
                topPaddingPx = topPadding.toPx(),
                bottomPaddingPx = bottomPadding.toPx(),
                widthPx = width.toPx(),
                color = color,
                layoutDirection = layoutDirection
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawScrollbarInternal(
    alpha: Float,
    scrollOffset: Float,
    totalHeight: Float,
    viewportHeightFull: Float,
    topPaddingPx: Float,
    bottomPaddingPx: Float,
    widthPx: Float,
    color: Color,
    layoutDirection: LayoutDirection
) {
    val viewportHeight = viewportHeightFull - topPaddingPx - bottomPaddingPx
    if (viewportHeight <= 0f) return

    val scrollbarHeight = ((viewportHeight / totalHeight) * viewportHeight).coerceAtLeast(20.dp.toPx())
    val scrollableContentHeight = (totalHeight - viewportHeightFull).coerceAtLeast(1f)
    val scrollRatio = (scrollOffset / scrollableContentHeight).coerceIn(0f, 1f)
    val scrollbarY = scrollRatio * (viewportHeight - scrollbarHeight) + topPaddingPx

    // Always draw on the right side as requested by the user
    val xOffset = size.width - widthPx - 2.dp.toPx()

    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha),
        topLeft = Offset(xOffset, scrollbarY),
        size = Size(widthPx, scrollbarHeight),
        cornerRadius = CornerRadius(widthPx / 2, widthPx / 2)
    )
}
