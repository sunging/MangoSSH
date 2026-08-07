package website.sung.mangossh.presentation

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Long-press drag-to-reorder for items inside a [LazyListState]-driven list.
 *
 * Only items whose key satisfies [isReorderableKey] may be dragged, and a drag only swaps with
 * other items that also satisfy it — a heading or an unrelated row (a security banner, an active
 * session card) blocks the drag rather than being displaced by it. [onMove] is called with the
 * pre-swap indices every time the dragged item crosses another reorderable item's midpoint; the
 * caller is expected to update its backing list synchronously so the next layout pass reflects
 * the swap. [onDragEnd] fires once, when the finger lifts, so the caller can commit the final
 * order.
 */
@Composable
fun rememberReorderableListState(
    listState: LazyListState,
    isReorderableKey: (Any) -> Boolean,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragEnd: () -> Unit,
): ReorderableListState {
    val scope = rememberCoroutineScope()
    return remember(listState) {
        ReorderableListState(listState, scope, isReorderableKey, onMove, onDragEnd)
    }.also {
        it.isReorderableKey = isReorderableKey
        it.onMove = onMove
        it.onDragEnd = onDragEnd
    }
}

class ReorderableListState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    isReorderableKey: (Any) -> Boolean,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onDragEnd: () -> Unit,
) {
    internal var isReorderableKey: (Any) -> Boolean = isReorderableKey
    internal var onMove: (fromIndex: Int, toIndex: Int) -> Unit = onMove
    internal var onDragEnd: () -> Unit = onDragEnd

    var draggingKey by mutableStateOf<Any?>(null)
        private set
    private var dragOffset by mutableFloatStateOf(0f)

    /** Vertical pixel offset to apply to the item with this key while it is being dragged. */
    fun offsetForKey(key: Any): Float = if (key == draggingKey) dragOffset else 0f

    internal fun onDragStart(key: Any) {
        if (!isReorderableKey(key)) return
        draggingKey = key
        dragOffset = 0f
    }

    internal fun onDrag(deltaY: Float) {
        val key = draggingKey ?: return
        dragOffset += deltaY

        val visible = listState.layoutInfo.visibleItemsInfo
        val draggedInfo = visible.firstOrNull { it.key == key } ?: return
        val draggedCenter = draggedInfo.offset + draggedInfo.size / 2f + dragOffset

        val target = visible
            .filter { it.key != key && isReorderableKey(it.key) }
            .firstOrNull { candidate ->
                draggedCenter.toInt() in candidate.offset..(candidate.offset + candidate.size)
            }
        if (target != null) {
            onMove(draggedInfo.index, target.index)
            // Keep the finger's visual position continuous across the swap: the dragged
            // row's resting offset becomes the target's pre-swap offset, so the remaining
            // drag delta must shrink by exactly how far that resting offset just moved.
            dragOffset += draggedInfo.offset - target.offset
        }

        autoScrollNearEdges(draggedCenter)
    }

    internal fun onDragEndOrCancel() {
        draggingKey = null
        dragOffset = 0f
        onDragEnd()
    }

    private fun autoScrollNearEdges(draggedCenter: Float) {
        val viewport = listState.layoutInfo.viewportEndOffset
        val edge = EDGE_SCROLL_THRESHOLD_PX
        val scrollAmount = when {
            draggedCenter < edge -> -AUTO_SCROLL_STEP_PX
            draggedCenter > viewport - edge -> AUTO_SCROLL_STEP_PX
            else -> return
        }
        scope.launch { listState.scrollBy(scrollAmount) }
    }

    private companion object {
        const val EDGE_SCROLL_THRESHOLD_PX = 120f
        const val AUTO_SCROLL_STEP_PX = 24f
    }
}

/** Attaches long-press drag-to-reorder to a single lazy list item identified by [key]. */
fun Modifier.dragReorderable(state: ReorderableListState, key: Any): Modifier = this.pointerInput(key) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.onDragStart(key) },
        onDrag = { change, dragAmount ->
            change.consume()
            state.onDrag(dragAmount.y)
        },
        onDragEnd = { state.onDragEndOrCancel() },
        onDragCancel = { state.onDragEndOrCancel() },
    )
}
