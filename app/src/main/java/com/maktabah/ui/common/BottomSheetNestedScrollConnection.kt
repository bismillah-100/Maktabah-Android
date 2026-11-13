package com.maktabah.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity

/**
 * Prevents inner scrollable content inside a ModalBottomSheet from dragging or dismissing the
 * sheet within the same gesture/fling after hitting the top edge, while keeping sheet drag
 * gestures (such as dragging from the drag handle or header) enabled at all times.
 */
@Composable
fun Modifier.consumeBottomSheetNestedScroll(): Modifier {
    val controller = remember { BottomSheetChildNestedScrollConnection() }
    return this
        .pointerInput(controller) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val isFirstPointerDown =
                        event.changes.any { it.changedToDownIgnoreConsumed() } &&
                            event.changes.none { it.previousPressed }
                    if (isFirstPointerDown) {
                        controller.reset()
                    }
                }
            }
        }
        .nestedScroll(controller)
}

private class BottomSheetChildNestedScrollConnection : NestedScrollConnection {
    private var childScrolledInGesture = false

    fun reset() {
        childScrolledInGesture = false
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (consumed.y != 0f) {
            childScrolledInGesture = true
        }
        if (available.y > 0f && childScrolledInGesture) {
            return Offset(0f, available.y)
        }
        return Offset.Zero
    }

    override suspend fun onPostFling(
        consumed: Velocity,
        available: Velocity,
    ): Velocity {
        val result = if (childScrolledInGesture && available.y > 0f) {
            Velocity(0f, available.y)
        } else {
            Velocity.Zero
        }
        childScrolledInGesture = false
        return result
    }
}
