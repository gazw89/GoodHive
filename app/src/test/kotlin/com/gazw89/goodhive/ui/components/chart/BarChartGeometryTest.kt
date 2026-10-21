package com.gazw89.goodhive.ui.components.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BarChartGeometryTest {
    @Test fun barSlotsAreGapApartAndInsidePlot() {
        val a = barSlot(0, 10, 100f, 2f)
        val b = barSlot(1, 10, 100f, 2f)
        assertEquals(8f, a.width, 1e-4f)
        assertEquals(1f, a.left, 1e-4f)
        assertEquals(2f, b.left - (a.left + a.width), 1e-4f)
        val last = barSlot(9, 10, 100f, 2f)
        assertEquals(99f, last.left + last.width, 1e-4f)
    }

    @Test fun barSlotEmptyAndTiny() {
        assertEquals(BarSlot(0f, 0f), barSlot(0, 0, 100f, 2f))
        assertTrue(barSlot(0, 500, 100f, 2f).width >= 1f)
    }

    @Test fun niceAxisExpandsToTickMultiples() {
        val a = niceAxis(15.0, 21.0, 5)
        assertEquals(14.0, a.range.min, 1e-9)
        assertEquals(22.0, a.range.max, 1e-9)
        assertEquals(listOf(14.0, 16.0, 18.0, 20.0, 22.0), a.ticks)
    }

    @Test fun niceAxisZeroSpan() {
        val a = niceAxis(19.0, 19.0, 5)
        assertTrue(a.range.min < 19.0 && a.range.max > 19.0)
        assertTrue(a.ticks.size <= 5)
    }

    @Test fun niceAxisRespectsMaxTicks() {
        for (max in listOf(3, 4, 5, 6)) {
            val a = niceAxis(0.3, 87.0, max)
            assertTrue(a.ticks.size <= max)
            assertTrue(a.range.min <= 0.3 && a.range.max >= 87.0)
        }
    }

    @Test fun rangeBarAxisIncludesReference() {
        val a = rangeBarAxis(listOf(15.2, 16.0), listOf(19.4, 20.0), reference = 23.0)
        assertTrue(a.range.max >= 23.0)
        assertTrue(a.range.min <= 15.2)
    }

    @Test fun rangeBarAxisEmptyUsesDefault() {
        val a = rangeBarAxis(emptyList(), emptyList())
        assertTrue(a.range.min <= DefaultYRange.min && a.range.max >= DefaultYRange.max)
    }

    @Test fun zeroBasedAxisStartsAtZeroAndCoversMax() {
        val a = zeroBasedAxis(listOf(32.0, 55.0, 41.0), reference = 40.0)
        assertEquals(0.0, a.range.min, 1e-9)
        assertTrue(a.range.max >= 55.0)
        assertEquals(0.0, a.ticks.first(), 1e-9)
    }

    @Test fun zeroBasedAxisAllZero() {
        val a = zeroBasedAxis(listOf(0.0, 0.0))
        assertEquals(0.0, a.range.min, 1e-9)
        assertTrue(a.range.max > 0.0)
    }

    @Test fun heatmapCellsTileWithGap() {
        val c00 = heatmapCell(0, 0, 7, 24, 240f, 70f, 2f)
        val c01 = heatmapCell(0, 1, 7, 24, 240f, 70f, 2f)
        val c10 = heatmapCell(1, 0, 7, 24, 240f, 70f, 2f)
        assertEquals(8f, c00.width, 1e-4f)
        assertEquals(8f, c00.height, 1e-4f)
        assertEquals(2f, c01.left - (c00.left + c00.width), 1e-4f)
        assertEquals(2f, c10.top - (c00.top + c00.height), 1e-4f)
        val last = heatmapCell(6, 23, 7, 24, 240f, 70f, 2f)
        assertEquals(239f, last.left + last.width, 1e-4f)
        assertEquals(69f, last.top + last.height, 1e-4f)
    }
}
