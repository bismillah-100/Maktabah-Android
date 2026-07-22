package com.maktabah.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Posisi popover: di tengah area tap (anchor), digeser hanya jika akan
 * menabrak tepi layar. Ada margin aman dari tepi layar (screenMargin).
 */
private class TapCenterPositionProvider(
    private val screenMarginPx: Int,
    private val gapPx: Int,
    private val onArrowOffsetXCalculated: (Dp, Boolean) -> Unit,
    private val density: androidx.compose.ui.unit.Density,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchorCenterX = anchorBounds.left + anchorBounds.width / 2

        // Target: popover center-nya pas di tengah anchor
        var x = anchorCenterX - popupContentSize.width / 2
        x = x.coerceIn(screenMarginPx, windowSize.width - popupContentSize.width - screenMarginPx)

        // Arrow menunjuk ke titik tengah anchor, posisinya relatif terhadap body popover
        val arrowCenterXPx = (anchorCenterX - x).coerceIn(0, popupContentSize.width)
        val arrowOffsetDp = with(density) { arrowCenterXPx.toDp() }

        // Coba taruh di bawah anchor dulu; kalau kepotong bawah layar, taruh di atas
        val spaceBelow = windowSize.height - anchorBounds.bottom
        val fitsBelow = spaceBelow >= popupContentSize.height + gapPx + screenMarginPx
        val arrowAtTop: Boolean
        val y: Int
        if (fitsBelow) {
            y = anchorBounds.bottom + gapPx
            arrowAtTop = true
        } else {
            y = anchorBounds.top - gapPx - popupContentSize.height
            arrowAtTop = false
        }

        onArrowOffsetXCalculated(arrowOffsetDp, arrowAtTop)
        return IntOffset(x, y.coerceAtLeast(screenMarginPx))
    }
}

data class PopoverMenuAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * Popover ala NSPopover: muncul di tengah area tap, arrow menunjuk ke situ,
 * otomatis geser (bukan mengikuti anchor mentah-mentah) kalau mepet tepi layar.
 */
@Composable
fun TapCenteredPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actions: List<PopoverMenuAction>,
    screenMargin: Dp = 12.dp,
    gap: Dp = 2.dp,
) {
    if (!expanded) return

    val density = androidx.compose.ui.platform.LocalDensity.current
    var arrowOffsetX by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0.dp) }
    var arrowAtTop by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

    val screenMarginPx = with(density) { screenMargin.roundToPx() }
    val gapPx = with(density) { gap.roundToPx() }

    Popup(
        popupPositionProvider = TapCenterPositionProvider(
            screenMarginPx = screenMarginPx,
            gapPx = gapPx,
            onArrowOffsetXCalculated = { offset, atTop ->
                arrowOffsetX = offset
                arrowAtTop = atTop
            },
            density = density,
        ),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Surface(
                shape = PopoverArrowShape(
                    cornerRadius = 30.dp,
                    arrowWidth = 14.dp,
                    arrowHeight = 7.dp,
                    arrowOffsetX = arrowOffsetX,
                    arrowAtTop = arrowAtTop,
                ),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                shadowElevation = 0.5.dp,
                tonalElevation = 0.dp,
                modifier = Modifier.widthIn(min = 180.dp, max = 250.dp),
            ) {
                Column(
                    modifier = Modifier.padding(
                        top = if (arrowAtTop) 16.dp else 10.dp,
                        bottom = if (arrowAtTop) 10.dp else 16.dp,
                    )
                ) {
                    actions.forEach { action ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    onDismiss()
                                    action.onClick()
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                            Text(
                                text = action.label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}
