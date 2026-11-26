package com.maktabah.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Swipe-to-delete container supporting EndToStart horizontal swipe with strict vertical scroll
 * prioritization so fast vertical scrolls in a LazyColumn are never hijacked.
 */
@Composable
fun SwipeToDeleteBox(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    canStartSwipe: () -> Boolean = { true },
    dismissThreshold: Float = 0.6f,
    backgroundModifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Delete,
    contentDescription: String? = null,
    shape: Shape = RoundedCornerShape(0.dp),
    backgroundColor: Color = Color(0xFFFF3B30),
    iconSize: Dp = 24.dp,
    revealedWidthOffset: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val coroutineScope = rememberCoroutineScope()
    val animatable = remember { Animatable(0f) }
    var itemWidth by remember { mutableIntStateOf(0) }
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dismissed by remember { mutableStateOf(false) }

    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentCanStartSwipe by rememberUpdatedState(canStartSwipe)
    val currentOffset = if (isDragging) dragOffset else animatable.value
    val isTargetDismiss = itemWidth > 0 && abs(currentOffset) >= itemWidth * dismissThreshold

    Box(
        modifier = modifier
            .onSizeChanged { itemWidth = it.width }
            .pointerInput(enabled, isRtl, dismissThreshold) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width
                    if (dismissed || width <= 0 || !currentCanStartSwipe()) {
                        return@awaitEachGesture
                    }

                    val slop = viewConfiguration.touchSlop
                    var totalDx = 0f
                    var totalDy = 0f
                    var dragging = false
                    var completed = false

                    try {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed || change.changedToUpIgnoreConsumed()) {
                                break
                            }
                            if (change.isConsumed) {
                                return@awaitEachGesture
                            }

                            val delta = change.positionChange()
                            if (!dragging) {
                                totalDx += delta.x
                                totalDy += delta.y
                                val absDx = abs(totalDx)
                                val absDy = abs(totalDy)

                                // Yield to vertical scroll whenever vertical motion is non-trivial
                                // and horizontal motion is not clearly dominant.
                                if (absDy >= slop && absDx <= absDy * 1.5f) {
                                    return@awaitEachGesture
                                }

                                val directedTotalDx = if (isRtl) -totalDx else totalDx
                                val startOffset = animatable.value
                                val isEndToStart = directedTotalDx < 0f || startOffset < 0f

                                if (absDx >= slop * 1.5f && absDx > absDy * 1.5f) {
                                    if (!isEndToStart) {
                                        return@awaitEachGesture
                                    }
                                    dragging = true
                                    dragOffset = startOffset
                                    isDragging = true
                                    coroutineScope.launch { animatable.stop() }
                                    change.consume()
                                }
                            } else {
                                change.consume()
                                val directedDelta = if (isRtl) -delta.x else delta.x
                                dragOffset = (dragOffset + directedDelta)
                                    .coerceIn(-width.toFloat(), 0f)
                            }
                        }
                        completed = true
                    } finally {
                        if (dragging && !completed) {
                            val residual = dragOffset
                            isDragging = false
                            coroutineScope.launch {
                                animatable.snapTo(residual)
                                animatable.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                )
                            }
                        }
                    }

                    if (dragging) {
                        val finalOffset = dragOffset
                        val shouldDismiss = abs(finalOffset) >= width * dismissThreshold
                        if (shouldDismiss) {
                            dismissed = true
                        }
                        coroutineScope.launch {
                            animatable.snapTo(finalOffset)
                            isDragging = false
                            if (shouldDismiss) {
                                animatable.animateTo(
                                    targetValue = -width.toFloat(),
                                    animationSpec = tween(durationMillis = 180),
                                )
                                currentOnDismiss()
                            } else {
                                animatable.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                )
                            }
                        }
                    }
                }
            },
    ) {
        if (abs(currentOffset) > 0.5f) {
            SwipeDeleteBackground(
                offset = currentOffset,
                isTargetDismiss = isTargetDismiss,
                modifier = Modifier
                    .matchParentSize()
                    .then(backgroundModifier),
                icon = icon,
                contentDescription = contentDescription,
                shape = shape,
                backgroundColor = backgroundColor,
                iconSize = iconSize,
                revealedWidthOffset = revealedWidthOffset,
            )
        }
        Box(
            modifier = Modifier.offset { IntOffset(currentOffset.roundToInt(), 0) },
        ) {
            content()
        }
    }
}

@Composable
fun SwipeDeleteBackground(
    offset: Float,
    isTargetDismiss: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Delete,
    contentDescription: String? = null,
    shape: Shape = RoundedCornerShape(0.dp),
    backgroundColor: Color = Color(0xFFFF3B30),
    iconSize: Dp = 24.dp,
    revealedWidthOffset: Dp = 0.dp
) {
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
