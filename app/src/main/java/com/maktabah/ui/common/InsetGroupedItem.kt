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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun Modifier.insetGroupedBorder(
    borderColor: Color,
    borderWidth: Dp = Dp.Hairline,
    cornerRadius: Dp = 30.dp,
    topExtension: Dp,
    bottomExtension: Dp,
) = this.drawWithContent {
    drawContent()
    val widthPx = borderWidth.toPx()
    val halfWidth = widthPx / 2f
    val radiusPx = cornerRadius.toPx()
    val topExtPx = topExtension.toPx()
    val bottomExtPx = bottomExtension.toPx()
    val w = size.width
    val h = size.height

    drawRoundRect(
        color = borderColor,
        topLeft = Offset(halfWidth, halfWidth - topExtPx),
        size = Size(w - widthPx, h - widthPx + topExtPx + bottomExtPx),
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(width = widthPx),
    )
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
    dividerEndPadding: Dp = 16.dp,
    fillMaxWidth: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isFirst = index == 0
    val isLast = index == lastIndex

    val topStartRadius = if (isFirst) 30.dp else 0.dp
    val topEndRadius = if (isFirst) 30.dp else 0.dp
    val bottomStartRadius = if (isLast) 30.dp else 0.dp
    val bottomEndRadius = if (isLast) 30.dp else 0.dp

    val shape = RoundedCornerShape(
        topStart = topStartRadius,
        topEnd = topEndRadius,
        bottomStart = bottomStartRadius,
        bottomEnd = bottomEndRadius
    )

    val topExtension = if (isFirst) 0.dp else 30.dp
    val bottomExtension = if (isLast) 0.dp else 30.dp

    val borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    val haptic = LocalHapticFeedback.current

    Column(
        modifier =
            modifier
                .let { if (fillMaxWidth) it.fillMaxWidth() else it }
                .padding(outerPadding)
                .clip(shape)
                .background(color)
                .insetGroupedBorder(
                    borderColor = borderColor,
                    topExtension = topExtension,
                    bottomExtension = bottomExtension,
                ).combinedClickable(
                    onClick = {
                        focusManager.clearFocus()
                        onClick()
                    },
                    onLongClick =
                        onLongClick?.let {
                            {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                it()
                            }
                        },
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .defaultMinSize(minHeight = 24.dp)
                    .let { if (fillMaxWidth) it.fillMaxWidth() else it }
                    .padding(contentPadding),
            contentAlignment = Alignment.CenterStart,
        ) { content() }
        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = dividerStartPadding, end = dividerEndPadding),
                thickness = Dp.Hairline,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        }
    }
}
