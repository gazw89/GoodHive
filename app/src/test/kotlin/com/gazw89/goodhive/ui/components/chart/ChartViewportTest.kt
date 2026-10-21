package com.gazw89.goodhive.ui.components.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartViewportTest {
    private val full = ChartViewport.FullDay
    private val tol = 1e-6

    private fun assertWindow(v: ChartViewport, start: Double, end: Double, tolerance: Double = tol) {
        assertEquals(start, v.start, tolerance, "start of $v")
        assertEquals(end, v.end, tolerance, "end of $v")
    }

    // Pixel inputs are Float, so compare windows with a tolerance of a few thousandths of a minute.
    private fun assertSameWindow(expected: ChartViewport, actual: ChartViewport) =
        assertWindow(actual, expected.start, expected.end, 1e-3)

    private fun assertInsideDay(v: ChartViewport) {
        assertTrue(v.start >= 0.0 && v.end <= 1440.0 && v.start < v.end, "outside the day: $v")
        assertTrue(v.span >= MinZoomSpanMinutes - tol || v.isFullDay, "below min span: $v")
    }

    @Test fun defaultIsExactlyFullDay() {
        assertWindow(full, 0.0, 1440.0)
        assertTrue(full.isFullDay)
        assertEquals(ChartRange(0.0, 1440.0), full.visibleRange)
    }

    @Test fun zoomKeepsFocalValueFixed() {
        for (focal in listOf(0.25, 0.5, 0.8)) {
            val before = full.valueAt(focal)
            val zoomed = full.zoomed(4.0, focal)
            assertEquals(360.0, zoomed.span, tol)
            assertEquals(before, zoomed.valueAt(focal), tol, "focal $focal")
        }
    }

    @Test fun zoomingAgainAroundAnotherFocalKeepsThatValueFixed() {
        val v = full.zoomed(3.0, 0.5)
        val before = v.valueAt(0.2)
        val z = v.zoomed(2.0, 0.2)
        assertEquals(before, z.valueAt(0.2), tol)
        assertEquals(v.span / 2.0, z.span, tol)
    }

    @Test fun zoomAtEdgesClampsToDay() {
        assertWindow(full.zoomed(4.0, 0.0), 0.0, 360.0)
        assertWindow(full.zoomed(4.0, 1.0), 1080.0, 1440.0)
    }

    @Test fun minimumSpanIsEnforced() {
        val v = full.zoomed(1000.0, 0.5)
        assertEquals(MinZoomSpanMinutes, v.span, tol)
        assertEquals(720.0, v.valueAt(0.5), tol)
        // Zooming further in does nothing.
        assertEquals(v.span, v.zoomed(10.0, 0.3).span, tol)
    }

    @Test fun maximumSpanIsTheWholeDayAndNeverExtendsPastMidnight() {
        val v = full.zoomed(4.0, 0.5)
        val out = v.zoomed(0.01, 0.5)
        assertWindow(out, 0.0, 1440.0)
        // Pinch out near an edge: still inside the day.
        val nearEdge = full.zoomed(4.0, 0.95).zoomed(2.0, 0.95)
        assertInsideDay(nearEdge)
        assertFalse(nearEdge.isFullDay)
    }

    @Test fun zoomOutSnapsToExactlyFullDay() {
        val out = full.zoomed(2.0, 0.3).zoomed(0.5, 0.3)
        assertEquals(full, out)
        assertEquals(0.0, out.start)
        assertEquals(1440.0, out.end)
    }

    @Test fun settledSnapsNearlyFullWindows() {
        val almost = ChartViewport(5.0, 1435.0)
        assertFalse(almost.isFullDay)
        assertEquals(full, almost.settled())
        val clearlyZoomed = ChartViewport(100.0, 1000.0)
        assertEquals(clearlyZoomed, clearlyZoomed.settled())
    }

    @Test fun panMovesContentAndClampsAtBothMidnights() {
        val v = ChartViewport(600.0, 840.0) // 4h window
        // Drag right by a quarter of the window: earlier times come into view.
        assertWindow(v.panned(0.25), 540.0, 780.0)
        assertWindow(v.panned(-0.25), 660.0, 900.0)
        // Far beyond midnight either way: stops at the day edge and keeps its span.
        assertWindow(v.panned(100.0), 0.0, 240.0)
        assertWindow(v.panned(-100.0), 1200.0, 1440.0)
        assertEquals(240.0, v.panned(100.0).span, tol)
    }

    @Test fun panAtFullDayDoesNothing() {
        assertEquals(full, full.panned(0.5))
        assertEquals(full, full.panned(-0.5))
    }

    @Test fun resetReturnsToFullDay() {
        assertEquals(full, ChartViewport(100.0, 200.0).reset())
    }

    @Test fun invalidInputsAreIgnored() {
        val v = ChartViewport(600.0, 840.0)
        assertEquals(v, v.zoomed(0.0, 0.5))
        assertEquals(v, v.zoomed(-1.0, 0.5))
        assertEquals(v, v.zoomed(Double.NaN, 0.5))
        assertEquals(v, v.transformed(2.0, Double.NaN))
    }

    @Test fun randomisedGesturesNeverEscapeTheDay() {
        val rnd = java.util.Random(42)
        var v = full
        repeat(5000) {
            v = when (rnd.nextInt(3)) {
                0 -> v.zoomed(Math.exp(rnd.nextGaussian()), rnd.nextDouble())
                1 -> v.panned(rnd.nextGaussian() * 0.3)
                else -> v.transformed(Math.exp(rnd.nextGaussian() * 0.2), rnd.nextDouble(), rnd.nextGaussian() * 0.1)
            }
            assertInsideDay(v)
        }
    }

    // ---- gesture maths -----------------------------------------------------------------------

    @Test fun pinchKeepsDataUnderFingers() {
        val width = 400f
        val v = ChartViewport(300.0, 900.0)
        // Fingers at x=100 and x=300 (centroid 200) move apart symmetrically to 50 and 350.
        val valueUnderCentroidBefore = v.valueAt(200.0 / width)
        val next = v.pinched(prevCentroidX = 200f, prevSpread = 200f, centroidX = 200f, spread = 300f, widthPx = width)
        assertEquals(valueUnderCentroidBefore, next.valueAt(200.0 / width), tol)
        assertEquals(600.0 / 1.5, next.span, tol)
    }

    @Test fun pinchWithMovingCentroidPansToo() {
        val width = 400f
        val v = ChartViewport(300.0, 900.0)
        val before = v.valueAt(200.0 / width)
        // Centroid moves 40px right while spreading: the same value is now under x=240.
        val next = v.pinched(200f, 200f, 240f, 240f, width)
        assertEquals(before, next.valueAt(240.0 / width), tol)
    }

    @Test fun pinchWithEqualSpreadIsAPurePan() {
        val width = 400f
        val v = ChartViewport(600.0, 840.0)
        val next = v.pinched(200f, 150f, 240f, 150f, width)
        assertEquals(v.span, next.span, tol)
        assertSameWindow(v.panned(40.0 / width), next)
    }

    @Test fun pinchCumulativeDeltasMatchOneBigPinch() {
        val width = 500f
        var v = full
        // 10 small steps of 1.1x around a stationary centroid.
        repeat(10) { v = v.pinched(250f, 100f, 250f, 110f, width) }
        // Each step is a 1.1x scale about the centre of the plot.
        var expected = full
        repeat(10) { expected = expected.zoomed(1.1, 0.5) }
        assertSameWindow(expected, v)
    }

    @Test fun degenerateSpreadsAreIgnored() {
        val v = ChartViewport(600.0, 840.0)
        assertEquals(v, v.pinched(100f, 0f, 100f, 50f, 400f))
        assertEquals(v, v.pinched(100f, 50f, 100f, 0f, 400f))
        assertEquals(v, v.pinched(100f, 50f, 100f, 60f, 0f))
    }

    @Test fun draggedByIsPanInPixels() {
        val v = ChartViewport(600.0, 840.0)
        assertSameWindow(v.panned(0.1), v.draggedBy(40f, 400f))
        assertEquals(v, v.draggedBy(40f, 0f))
    }

    @Test fun dragClassification() {
        val slop = 8f
        assertEquals(DragIntent.Undecided, classifyDrag(3f, 3f, slop))
        assertEquals(DragIntent.Horizontal, classifyDrag(12f, 4f, slop))
        assertEquals(DragIntent.Horizontal, classifyDrag(-12f, 4f, slop))
        assertEquals(DragIntent.Vertical, classifyDrag(4f, 12f, slop))
        assertEquals(DragIntent.Vertical, classifyDrag(10f, 10f, slop))
    }

    @Test fun doubleTapDetection() {
        assertTrue(isDoubleTap(1000, 10f, 10f, 1200, 14f, 12f, timeoutMillis = 300, slopPx = 40f))
        assertFalse(isDoubleTap(1000, 10f, 10f, 1500, 10f, 10f, timeoutMillis = 300, slopPx = 40f))
        assertFalse(isDoubleTap(1000, 10f, 10f, 1100, 200f, 10f, timeoutMillis = 300, slopPx = 40f))
        assertFalse(isDoubleTap(1000, 10f, 10f, 900, 10f, 10f, timeoutMillis = 300, slopPx = 40f))
    }

    // ---- now marker / shared x scale ---------------------------------------------------------

    @Test fun nowMarkerOnlyWhenInsideWindow() {
        val w = ChartRange(600.0, 840.0)
        assertEquals(700.0, nowMarkerX(700.0, w))
        assertEquals(600.0, nowMarkerX(600.0, w))
        assertEquals(840.0, nowMarkerX(840.0, w))
        assertNull(nowMarkerX(599.0, w))
        assertNull(nowMarkerX(841.0, w))
        assertNull(nowMarkerX(null, w))
        assertEquals(1000.0, nowMarkerX(1000.0, FullDayDomain))
    }

    @Test fun scheduleAndRoomMapToSameXForSameMinute() {
        val window = ChartRange(600.0, 840.0)
        val schedule = ChartPoint(720.0, 19.0)
        val room = ChartPoint(720.0, 20.1)
        assertEquals(mapX(schedule.x, window, 300f), mapX(room.x, window, 300f))
        assertEquals(150f, mapX(720.0, window, 300f))
        // And the marker for the same minute lands on the same pixel.
        assertEquals(mapX(720.0, window, 300f), mapX(nowMarkerX(720.0, window)!!, window, 300f))
    }
}
