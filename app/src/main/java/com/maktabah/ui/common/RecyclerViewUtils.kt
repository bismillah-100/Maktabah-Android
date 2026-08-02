package com.maktabah.ui.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.maktabah.ui.library.TreeItemAnimator
import com.maktabah.utils.GroupedCardDecoration

/**
 * Common colors used by library and annotation adapters/decorations.
 */
@Immutable
data class GroupedListColors(
    val surfaceColor: Int,
    val onSurfaceColor: Int,
    val primaryColor: Int,
    val secondaryColor: Int,
    val onSurfaceVariantColor: Int,
    val onStrokeColor: Int
)

@Composable
fun rememberGroupedListColors(): GroupedListColors {
    val themeColors = MaterialTheme.colorScheme
    return remember(themeColors) {
        GroupedListColors(
            surfaceColor = themeColors.surface.toArgb(),
            onSurfaceColor = themeColors.onSurface.toArgb(),
            primaryColor = themeColors.primary.toArgb(),
            secondaryColor = themeColors.secondary.toArgb(),
            onSurfaceVariantColor = themeColors.onSurfaceVariant.toArgb(),
            onStrokeColor = themeColors.onSurface.copy(0.38f).toArgb(),
        )
    }
}

/**
 * Adds a fading edge effect to the top and bottom of a composable.
 *
 * @param canScrollForward Whether the content can be scrolled forward (shows bottom fade).
 * @param topPad The height of the top fade (typically the top padding of the list).
 * @param bottomFade The height of the bottom fade.
 */
fun Modifier.fadingEdge(
    canScrollForward: Boolean,
    topPad: Dp,
    bottomFade: Dp = 48.dp
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()

        val topPadPx = topPad.toPx()
        val bottomFadePx = bottomFade.toPx()

        if (topPadPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.8f to Color.Black.copy(alpha = 0.15f),
                    1.0f to Color.Black,
                    startY = 0f,
                    endY = topPadPx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

        if (canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - bottomFadePx,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Variant of [fadingEdge] for [LazyListState].
 */
fun Modifier.fadingEdge(
    listState: LazyListState,
    topPad: Dp,
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()

        val topPadPx = topPad.toPx()
        val bottomFadePx = 48.dp.toPx()

        if (topPadPx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.8f to Color.Black.copy(alpha = 0.15f),
                    1.0f to Color.Black,
                    startY = 0f,
                    endY = topPadPx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }

        if (listState.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - bottomFadePx,
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * A reusable Composable for grouped lists using Android RecyclerView.
 * Handles common setup like RTL, focus clearing, item animator, fading edges, and decorations.
 */
@Composable
fun GroupedRecyclerView(
    modifier: Modifier = Modifier,
    recyclerViewId: Int,
    adapter: RecyclerView.Adapter<*>,
    padding: PaddingValues,
    bottomContentPadding: Dp,
    colors: GroupedListColors = rememberGroupedListColors(),
    itemAnimator: RecyclerView.ItemAnimator? = TreeItemAnimator(),
    itemTouchHelper: ItemTouchHelper? = null,
    onScrollStateChanged: (RecyclerView, Int) -> Unit = { _, _ -> },
    onScrolled: (RecyclerView, Int, Int) -> Unit = { _, _, _ -> },
    decorationFactory: (RecyclerView) -> GroupedCardDecoration? = { null },
    update: (RecyclerView) -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    var canScrollForward by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .fadingEdge(canScrollForward, padding.calculateTopPadding()),
        factory = { ctx ->
            RecyclerView(ctx).apply {
                id = recyclerViewId
                layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                layoutManager = LinearLayoutManager(ctx)
                this.itemAnimator = itemAnimator
                clipToPadding = false

                setOnTouchListener { v, event ->
                    if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                        v.performClick()
                    }
                    focusManager.clearFocus()
                    false
                }

                addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                    override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                        if (e.action == android.view.MotionEvent.ACTION_DOWN) {
                            focusManager.clearFocus()
                        }
                        return false
                    }
                })

                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        canScrollForward = recyclerView.canScrollVertically(1)
                        onScrolled(recyclerView, dx, dy)
                    }
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                            focusManager.clearFocus()
                        }
                        onScrollStateChanged(recyclerView, newState)
                    }
                })
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    canScrollForward = v.canScrollVertically(1)
                }

                decorationFactory(this)?.let { addItemDecoration(it) }
                this.adapter = adapter
                itemTouchHelper?.attachToRecyclerView(this)
            }
        },
        update = { view ->
            val density = view.context.resources.displayMetrics.density
            view.setPadding(
                0,
                ((padding.calculateTopPadding().value + 16) * density).toInt(),
                0,
                ((bottomContentPadding.value + 16) * density).toInt()
            )
            canScrollForward = view.canScrollVertically(1)

            // Auto-update decoration colors
            val decoration = view.getItemDecorationAt(0) as? GroupedCardDecoration
            decoration?.let {
                val highlight = androidx.core.graphics.ColorUtils.setAlphaComponent(colors.onSurfaceColor, (255 * 0.1f).toInt())
                if (it.surfaceColor != colors.surfaceColor || it.strokeColor != colors.onStrokeColor || it.highlightColor != highlight) {
                    it.surfaceColor = colors.surfaceColor
                    it.strokeColor = colors.onStrokeColor
                    it.highlightColor = highlight
                    view.invalidate()
                }
            }

            update(view)
        }
    )
}
