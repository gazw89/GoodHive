package com.gazw89.goodhive.ui.components.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartGeometryTest {
    private fun line(vararg ys: Double) =
        ChartSeries.Line(ys.mapIndexed { i, y -> ChartPoint(i.toDouble(), y) })

    @Test fun yRangePadsData() {
        val r = yRangeFor(listOf(line(10.0, 20.0)))
        assertEquals(9.0, r.min, 1e-9)
        assertEquals(21.0, r.max, 1e-9)
    }

    @Test fun flatSeriesGetsMinSpanCentred() {
        val r = yRangeFor(listOf(line(19.0, 19.0)))
        assertEquals(4.0, r.span, 1e-9)
        assertEquals(19.0, (r.min + r.max) / 2, 1e-9)
    }

    @Test fun emptyDefaults() {
        assertEquals(ChartRange(10.0, 25.0), yRangeFor(emptyList()))
        assertEquals(ChartRange(10.0, 25.0), yRangeFor(listOf(ChartSeries.Step(emptyList()))))
    }

    @Test fun mapXEndpoints() {
        val d = ChartRange(0.0, 1440.0)
        assertEquals(0f, mapX(0.0, d, 200f))
        assertEquals(200f, mapX(1440.0, d, 200f))
        assertEquals(100f, mapX(720.0, d, 200f))
    }

    @Test fun mapYIsInverted() {
        val r = ChartRange(10.0, 20.0)
        assertEquals(0f, mapY(20.0, r, 100f))
        assertEquals(100f, mapY(10.0, r, 100f))
        assertEquals(50f, mapY(15.0, r, 100f))
    }

    @Test fun niceTicksAreWholeInRangeAndBounded() {
        val ranges = listOf(
            ChartRange(12.3, 21.7), ChartRange(9.0, 21.0), ChartRange(0.2, 0.9),
            ChartRange(-5.0, 35.0), ChartRange(10.0, 25.0), ChartRange(17.0, 21.0),
        )
        for (r in ranges) for (max in 1..8) {
            val t = niceTicks(r, max)
            assertTrue(t.size <= max, "$r max=$max got $t")
            t.forEach {
                assertEquals(Math.rint(it), it, 1e-9)
                assertTrue(it >= r.min - 1e-9 && it <= r.max + 1e-9, "$it outside $r")
            }
        }
        assertTrue(niceTicks(ChartRange(9.0, 21.0), 5).size >= 3)
    }
}
