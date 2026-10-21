package com.gazw89.goodhive.ui.components.chart

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastForEach

/**
 * Horizontal pinch-zoom / pan / double-tap-reset for a chart, as one pointer-input loop.
 *
 * Event ownership (so the chart can live inside a vertically scrolling page):
 * - A single finger is never consumed until it is a clearly horizontal drag *and* the chart is zoomed;
 *   vertical drags (and any drag while un-zoomed) are left untouched for the parent scroll container.
 * - Two or more pressed fingers are a pinch: position changes are consumed from then on.
 * - Once a pinch has begun, the remaining finger keeps panning (and keeps being consumed) so the page
 *   does not suddenly start scrolling mid-gesture.
 * - Taps are never consumed; two quick taps call `onViewportChange(viewport.reset())`.
 *
 * All maths lives in the pure functions of ChartViewport.kt; this only translates pointer events.
 * [currentViewport] is read once per gesture (then tracked locally) so rapid events cannot act on a
 * stale viewport before recomposition.
 */
internal suspend fun PointerInputScope.detectChartZoomGestures(
    currentViewport: () -> ChartViewport,
    onViewportChange: (ChartViewport) -> Unit,
) {
    var lastTapUp = -1L
    var lastTapPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
        var viewport = currentViewport()
        var total = Offset.Zero
        var intent = DragIntent.Undecided
        var pinched = false
        var tapCandidate = true
        var lastUpMillis = down.uptimeMillis
        var lastUpPos = down.position
        var touched = false

        do {
            val event = awaitPointerEvent()
            // Someone else (e.g. the parent's scroll) took the gesture: stand down.
            if (!pinched && intent != DragIntent.Horizontal && event.changes.fastAny { it.isConsumed }) {
                tapCandidate = false
                break
            }
            val width = size.width.toFloat()
            var pressedCount = 0
            var first: PointerInputChange? = null
            var second: PointerInputChange? = null
            event.changes.fastForEach { c ->
                if (c.pressed) {
                    pressedCount++
                    if (first == null) first = c else if (second == null) second = c
                }
                lastUpMillis = c.uptimeMillis
                lastUpPos = c.position
            }

            val a = first
            val b = second
            if (pressedCount >= 2 && a != null && b != null) {
                pinched = true
                tapCandidate = false
                val prevCentroid = (a.previousPosition.x + b.previousPosition.x) / 2f
                val centroid = (a.position.x + b.position.x) / 2f
                val prevSpread = (a.previousPosition - b.previousPosition).getDistance()
                val spread = (a.position - b.position).getDistance()
                viewport = viewport.pinched(prevCentroid, prevSpread, centroid, spread, width)
                onViewportChange(viewport)
                touched = true
                event.changes.fastForEach { if (it.positionChange() != Offset.Zero) it.consume() }
            } else if (pressedCount == 1 && a != null) {
                val delta = a.positionChange()
                if (pinched || intent == DragIntent.Horizontal) {
                    viewport = viewport.draggedBy(delta.x, width)
                    onViewportChange(viewport)
                    touched = true
                    if (delta != Offset.Zero) a.consume()
                } else {
                    total += delta
                    if (intent == DragIntent.Undecided) {
                        intent = classifyDrag(total.x, total.y, slop)
                        if (intent != DragIntent.Undecided) tapCandidate = false
                        if (intent == DragIntent.Horizontal) {
                            if (viewport.isFullDay) {
                                // Nothing to pan: leave it to the page (which only scrolls vertically anyway).
                                intent = DragIntent.Vertical
                            } else {
                                viewport = viewport.draggedBy(total.x, width)
                                onViewportChange(viewport)
                                touched = true
                                a.consume()
                            }
                        }
                    }
                }
            }
        } while (event.changes.fastAny { it.pressed })

        if (touched) {
            // Gesture finished: a window within a hair of the whole day snaps to exactly the whole day.
            val settled = viewport.settled()
            if (settled != viewport) onViewportChange(settled)
        }
        if (tapCandidate && !pinched) {
            if (lastTapUp >= 0 && isDoubleTap(lastTapUp, lastTapPos.x, lastTapPos.y, lastUpMillis, lastUpPos.x, lastUpPos.y, doubleTapTimeout, slop * 4f)) {
                onViewportChange(currentViewport().reset())
                lastTapUp = -1L
            } else {
                lastTapUp = lastUpMillis
                lastTapPos = lastUpPos
            }
        } else {
            lastTapUp = -1L
        }
    }
}
