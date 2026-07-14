package com.maktabah.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeDeleteBackground(
    dismissState: SwipeToDismissBoxState,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Delete,
    contentDescription: String? = null,
    shape: Shape = RoundedCornerShape(0.dp),
    backgroundColor: Color = Color(0xFFFF3B30),
    iconSize: Dp = 24.dp,
    revealedWidthOffset: Dp = 0.dp
) {
    val isTargetDismiss = dismissState.targetValue == SwipeToDismissBoxValue.EndToStart
    val offset = try { dismissState.requireOffset() } catch (_: Exception) { 0f }

    val alignmentBias by animateFloatAsState(
        targetValue = if (isTargetDismiss) -1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "alignmentBias",
    )

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(isTargetDismiss) {
        if (isTargetDismiss) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor, shape),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val density = LocalDensity.current
        val revealedWidth = with(density) {
            (abs(offset) - revealedWidthOffset.toPx()).coerceAtLeast(0f).toDp()
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(revealedWidth),
            contentAlignment = BiasAlignment(alignmentBias, 0f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Utility to check if swipe target is reached based on offset and item width.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun SwipeToDismissBoxState.isSwipeTargetReached(itemWidth: Int, threshold: Float = 0.6f): Boolean {
    if (itemWidth <= 0) return false
    val offset = try { requireOffset() } catch (_: Exception) { 0f }
    return abs(offset) >= (itemWidth * threshold)
}
