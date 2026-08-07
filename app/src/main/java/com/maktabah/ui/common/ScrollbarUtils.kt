package com.maktabah.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
 * Extension modifier to draw a vertical scrollbar on a generic component.
 */
@Composable
fun Modifier.drawGenericVerticalScrollbar(
    isScrollInProgress: Boolean,
    scrollOffsetProvider: () -> Float,
    scrollRangeProvider: () -> Float,
    scrollExtentProvider: () -> Float,
    width: Dp = 2.5.dp,
    color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    val targetAlpha = if (isScrollInProgress) 1f else 0f
    val duration = if (isScrollInProgress) 150 else 300
    
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = duration),
        label = "ScrollbarAlpha"
    )

    return drawWithContent {
        drawContent()

        val currentScrollRange = scrollRangeProvider()
        val currentScrollExtent = scrollExtentProvider()

        if (currentScrollRange > currentScrollExtent && alpha > 0f) {
            drawScrollbarInternal(
                alpha = alpha,
                scrollOffset = scrollOffsetProvider(),
                totalHeight = currentScrollRange,
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

    // Digambar tepat di tepi terluar layar (0f untuk RTL, tepi kanan untuk LTR)
    val xOffset = if (layoutDirection == LayoutDirection.Rtl) {
        0f
    } else {
        size.width - widthPx
    }

    drawRoundRect(
        color = color.copy(alpha = color.alpha * alpha),
        topLeft = Offset(xOffset, scrollbarY),
        size = Size(widthPx, scrollbarHeight),
        cornerRadius = CornerRadius(widthPx / 2, widthPx / 2)
    )
}
