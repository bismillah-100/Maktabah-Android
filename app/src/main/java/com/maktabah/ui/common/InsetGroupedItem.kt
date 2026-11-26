package com.maktabah.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.insetGroupedBorder(
    borderColor: Color,
    borderWidth: Dp = Dp.Hairline,
    cornerRadius: Dp = 30.dp,
    isFirst: Boolean,
    isLast: Boolean,
) = this.drawWithCache {
    // Menghitung pixel offset untuk pixel-snapping agar stroke tajam (tidak anti-aliasing bleed).
    // Dp.Hairline menghasilkan 0f dari toPx(), kita anggap 1 physical pixel untuk offset.
    val actualStrokeWidth = if (borderWidth == Dp.Hairline) 1f else borderWidth.toPx()
    val halfStroke = actualStrokeWidth / 2f

    val w = size.width
    val h = size.height
    val radiusPx = cornerRadius.toPx()

    val right = w - halfStroke
    val bottom = h - halfStroke

    // Tetap gunakan 0f untuk Dp.Hairline agar Compose menggambar 1 physical pixel
    val strokeStyle = Stroke(width = borderWidth.toPx())

    val cachedPath = when {
        isFirst && isLast -> null
        isFirst -> Path().apply {
            moveTo(halfStroke, bottom)
            lineTo(halfStroke, halfStroke + radiusPx)
            arcTo(
                rect = Rect(
                    halfStroke,
                    halfStroke, halfStroke + 2 * radiusPx, halfStroke + 2 * radiusPx
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            lineTo(right - radiusPx, halfStroke)
            arcTo(
                rect = Rect(right - 2 * radiusPx, halfStroke, right, halfStroke + 2 * radiusPx),
                startAngleDegrees = 270f,
                sweepAngleDegrees = 90f,
                forceMoveTo = false,
            )
            lineTo(right, bottom)
        }
        isLast -> Path().apply {
            moveTo(halfStroke, halfStroke)
            lineTo(halfStroke, bottom - radiusPx)
            arcTo(
                rect = Rect(
                    halfStroke,
                    bottom - 2 * radiusPx,
                    halfStroke + 2 * radiusPx,
                    bottom
                ),
                startAngleDegrees = 180f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false,
            )
            lineTo(right - radiusPx, bottom)
            arcTo(
                rect = Rect(right - 2 * radiusPx, bottom - 2 * radiusPx, right, bottom),
                startAngleDegrees = 90f,
                sweepAngleDegrees = -90f,
                forceMoveTo = false,
            )
            lineTo(right, halfStroke)
        }
        else -> null
    }

    val cornerRadiusObj = CornerRadius(radiusPx, radiusPx)
    val rectTopLeft = Offset(halfStroke, halfStroke)
    val rectSize = Size(right - halfStroke, bottom - halfStroke)

    onDrawWithContent {
        drawContent()

        when {
            // Case 1: Item Tunggal (First & Last) -> Full Rounded Rect
            isFirst && isLast -> {
                drawRoundRect(
                    color = borderColor,
                    topLeft = rectTopLeft,
                    size = rectSize,
                    cornerRadius = cornerRadiusObj,
                    style = strokeStyle,
                )
            }

            // Case 2 & 3: First atau Last -> Cached Path
            cachedPath != null -> {
                drawPath(path = cachedPath, color = borderColor, style = strokeStyle)
            }

            // Case 4: Item Tengah -> Garis Lurus Kiri & Kanan
            else -> {
                drawLine(
                    color = borderColor,
                    start = Offset(halfStroke, 0f),
                    end = Offset(halfStroke, h),
                    strokeWidth = actualStrokeWidth,
                )
                drawLine(
                    color = borderColor,
                    start = Offset(right, 0f),
                    end = Offset(right, h),
                    strokeWidth = actualStrokeWidth,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InsetGroupedItem(
    modifier: Modifier = Modifier,
    index: Int,
    lastIndex: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    outerPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    dividerStartPadding: Dp = 16.dp,
    fillMaxWidth: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isFirst = index == 0
    val isLast = index == lastIndex

    val shape = remember(isFirst, isLast) {
        val topRadius = if (isFirst) 30.dp else 0.dp
        val bottomRadius = if (isLast) 30.dp else 0.dp
        RoundedCornerShape(
            topStart = topRadius,
            topEnd = topRadius,
            bottomStart = bottomRadius,
            bottomEnd = bottomRadius
        )
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val borderColor = remember(onSurface) { onSurface.copy(alpha = 0.38f) }
    val dividerColor = remember(onSurface) { onSurface.copy(alpha = 0.75f) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = modifier.let { if (fillMaxWidth) it.fillMaxWidth() else it }
            .padding(outerPadding).background(color, shape).insetGroupedBorder(
                borderColor = borderColor,
                isFirst = isFirst,
                isLast = isLast,
            ).clip(shape).combinedClickable(
                onClick = {
                    focusManager.clearFocus()
                    onClick()
                },
                onLongClick = onLongClick?.let {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
            ),
    ) {
        Box(
            modifier = Modifier.defaultMinSize(minHeight = 24.dp)
                .let { if (fillMaxWidth) it.fillMaxWidth() else it }.padding(contentPadding),
            contentAlignment = Alignment.CenterStart,
        ) { content() }

        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = dividerStartPadding, end = 0.5.dp),
                thickness = Dp.Hairline,
                color = dividerColor,
            )
        }
    }
}
