package com.gazw89.goodhive.ui.components.chart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartAxesTest {
    // ---- adaptive x ticks --------------------------------------------------------------------

    private fun ticks(start: Double, end: Double, max: Int = 8) = adaptiveXTicks(ChartRange(start, end), max)

    @Test fun fullDayIsTheClassicAxis() {
        val t = ticks(0.0, 1440.0)
        assertEquals(listOf(0.0, 360.0, 720.0, 1080.0, 1440.0), t.map { it.value })
        assertEquals(listOf("00", "06", "12", "18", "24"), t.map { it.label })
        // Independent of how much width there is.
        assertEquals(t, ticks(0.0, 1440.0, max = 2))
        assertEquals(t, ticks(0.0, 1440.0, max = 8))
    }

    @Test fun intervalsForTypicalSpans() {
        fun interval(start: Double, end: Double): Double {
            val v = ticks(start, end).map { it.value }
            return v[1] - v[0]
        }
        assertEquals(120.0, interval(0.0, 720.0))
        assertEquals(60.0, interval(360.0, 720.0))
        assertEquals(30.0, interval(600.0, 780.0)) // 3h
        assertEquals(30.0, interval(600.0, 720.0)) // 2h
        assertEquals(10.0, interval(600.0, 660.0)) // 1h
        assertEquals(5.0, interval(600.0, 630.0)) // 30m
    }

    @Test fun ticksAreAlignedToTheIntervalAndInsideTheWindow() {
        for ((s, e) in listOf(7.0 to 400.0, 613.0 to 700.0, 1000.0 to 1440.0, 0.0 to 300.0, 421.0 to 439.0)) {
            val t = ticks(s, e)
            assertTrue(t.isNotEmpty(), "no ticks for $s..$e")
            val interval = t.getOrNull(1)?.let { it.value - t[0].value }
            t.forEach {
                assertTrue(it.value >= s && it.value <= e, "${it.value} outside $s..$e")
                if (interval != null) assertEquals(0.0, it.value % interval, 1e-9)
            }
        }
    }

    @Test fun zoomedLabelsAreHourMinuteAndMidnightIs2400() {
        val t = ticks(1260.0, 1440.0)
        assertEquals("24:00", t.last().label)
        assertEquals("21:00", t.first().label)
        assertEquals(listOf("10:00", "10:05", "10:10", "10:15", "10:20", "10:25", "10:30"), ticks(600.0, 630.0).map { it.label })
    }

    @Test fun tickCountStaysWithinCapAcrossSpansAndWidths() {
        for (span in listOf(15.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0, 180.0, 240.0, 360.0, 720.0, 1000.0, 1300.0, 1439.0)) {
            for (cap in 3..MaxDayTicks) {
                for (start in listOf(0.0, 13.0, 77.0, 1440.0 - span)) {
                    val s = start.coerceAtMost(1440.0 - span)
                    val t = ticks(s, s + span, cap)
                    // The largest interval is the fallback, so allow what it naturally yields.
                    val fallback = span / 360.0 + 1
                    assertTrue(t.size <= maxOf(cap.toDouble(), fallback), "span=$span cap=$cap start=$s -> ${t.size}")
                }
            }
        }
    }

    @Test fun narrowWidthsUseCoarserTicksThanWideWidths() {
        val labelWidth = 40f
        val narrow = maxXTickCount(widthPx = 240f, labelWidthPx = labelWidth)
        val wide = maxXTickCount(widthPx = 900f, labelWidthPx = labelWidth)
        assertTrue(narrow < wide, "narrow=$narrow wide=$wide")
        assertTrue(wide <= MaxDayTicks && narrow >= 2)
        val window = ChartRange(600.0, 780.0) // 3h
        val narrowTicks = adaptiveXTicks(window, narrow)
        val wideTicks = adaptiveXTicks(window, wide)
        assertTrue(narrowTicks.size <= narrow)
        assertTrue(narrowTicks.size <= wideTicks.size)
        // No overlap: neighbouring labels are at least one label width apart.
        listOf(narrow to 240f, wide to 900f).forEach { (cap, width) ->
            val t = adaptiveXTicks(window, cap)
            t.zipWithNext().forEach { (a, b) ->
                val gapPx = (b.value - a.value) / window.span * width
                assertTrue(gapPx >= labelWidth, "labels overlap at width $width: gap $gapPx")
            }
        }
    }

    @Test fun degenerateWidthStillGivesAUsableCap() {
        assertEquals(2, maxXTickCount(0f, 40f))
        assertEquals(2, maxXTickCount(100f, 0f))
    }

    // ---- y axis ------------------------------------------------------------------------------

    private fun step(vararg pts: Pair<Double, Double>) = ChartSeries.Step(pts.map { ChartPoint(it.first, it.second) })
    private fun line(vararg pts: Pair<Double, Double>) = ChartSeries.Line(pts.map { ChartPoint(it.first, it.second) })

    private val schedule = step(0.0 to 13.0, 390.0 to 19.0, 1320.0 to 13.0, 1440.0 to 13.0)

    @Test fun windowRangeIncludesStepInForceAtLeftEdge() {
        // Window 600..700 sits wholly inside the 19 degree step that began at 390.
        val r = yRangeForWindow(listOf(schedule), ChartRange(600.0, 700.0))
        assertEquals(19.0, (r.min + r.max) / 2, 1e-9)
        assertEquals(ZoomedYMinSpan, r.span, 1e-9)
    }

    @Test fun windowRangeIncludesStepChangesInsideTheWindowOnly() {
        val across = yRangeForWindow(listOf(schedule), ChartRange(300.0, 500.0)) // 13 then 19
        assertEquals(13.0 - ZoomedYPadding, across.min, 1e-9)
        assertEquals(19.0 + ZoomedYPadding, across.max, 1e-9)
        // A change that starts after the window is not included.
        val before = yRangeForWindow(listOf(schedule), ChartRange(100.0, 200.0))
        assertEquals(13.0, (before.min + before.max) / 2, 1e-9)
    }

    @Test fun stepClosingPointIsNotAValue() {
        val s = step(0.0 to 10.0, 1440.0 to 30.0)
        val r = yRangeForWindow(listOf(s), ChartRange(1000.0, 1440.0))
        assertEquals(10.0, (r.min + r.max) / 2, 1e-9)
    }

    @Test fun lineRangeUsesReadingsInsideAndEdgeCrossings() {
        val room = line(0.0 to 18.0, 100.0 to 20.0, 200.0 to 24.0, 300.0 to 22.0)
        // Only the 100..200 segment region is visible: window 150..250 sees the crossing values.
        val r = yRangeForWindow(listOf(room), ChartRange(150.0, 250.0))
        // At x=150 the drawn line is at 22, at 200 it is 24, at 250 it is 23.
        assertEquals(22.0 - ZoomedYPadding, r.min, 1e-9)
        assertEquals(24.0 + ZoomedYPadding, r.max, 1e-9)
    }

    @Test fun lineWithSparseReadingsUsesTheSegmentAcrossTheWindow() {
        val room = line(0.0 to 18.0, 1000.0 to 20.0) // no reading inside 400..600
        val r = yRangeForWindow(listOf(room), ChartRange(400.0, 600.0))
        // The drawn segment runs 18.8..19.2 across the window; that is tiny, so the min span applies
        // and the result is centred on the data rather than exaggerating the 0.4 degree change.
        assertEquals(19.0, (r.min + r.max) / 2, 1e-9)
        assertEquals(ZoomedYMinSpan, r.span, 1e-9)
    }

    @Test fun noDataInWindowFallsBackToWholeSeriesRange() {
        val room = line(0.0 to 18.0, 100.0 to 20.0)
        assertEquals(yRangeFor(listOf(room)), yRangeForWindow(listOf(room), ChartRange(900.0, 1000.0)))
    }

    @Test fun emptySeriesFallBackToDefaultRange() {
        assertEquals(DefaultYRange, yRangeForWindow(emptyList(), ChartRange(0.0, 100.0)))
        assertEquals(DefaultYRange, yRangeForWindow(listOf(line(), step()), ChartRange(0.0, 100.0)))
    }

    @Test fun tinyFluctuationsAreNotExaggerated() {
        val room = line(600.0 to 20.00, 610.0 to 20.05, 620.0 to 20.02)
        val r = yRangeForWindow(listOf(room), ChartRange(600.0, 620.0))
        assertTrue(r.span >= ZoomedYMinSpan - 1e-9)
    }

    @Test fun fullDayRangeIsTheOriginalYRangeOverEverything() {
        // The chart branches on the full-day viewport and uses yRangeFor over all series, unchanged.
        val room = line(0.0 to 19.7, 200.0 to 19.9, 400.0 to 20.2, 680.0 to 19.9)
        val all = listOf(schedule, room)
        val expected = yRangeFor(all)
        // Schedule 13..19 and room up to 20.2, padded by 1 degree.
        assertEquals(12.0, expected.min, 1e-9)
        assertEquals(21.2, expected.max, 1e-9)
        assertEquals(DefaultYRange, yRangeFor(emptyList()))
    }

    @Test fun defaultNiceTicksUnchangedByFineFlag() {
        val ranges = listOf(ChartRange(12.0, 21.2), ChartRange(9.0, 21.0), ChartRange(10.0, 25.0), ChartRange(17.0, 21.0))
        for (r in ranges) for (max in 1..8) {
            assertEquals(niceTicks(r, max), niceTicks(r, max, fine = false))
        }
    }

    @Test fun fineTicksUseHalfDegreesWhenTheRangeIsSmall() {
        val t = niceTicks(ChartRange(19.0, 21.2), 5, fine = true)
        assertTrue(t.all { Math.rint(it * 2) == it * 2 }, "$t")
        assertTrue(t.size in 2..5, "$t")
        assertEquals(listOf(19.0, 19.5, 20.0, 20.5, 21.0), t)
        // Wide ranges still fall back to coarse steps within the cap.
        assertTrue(niceTicks(ChartRange(10.0, 25.0), 5, fine = true).size <= 5)
        // Whole-degree behaviour is untouched without the flag.
        assertEquals(listOf(19.0, 20.0, 21.0), niceTicks(ChartRange(19.0, 21.2), 5))
    }

    @Test fun yTickLabels() {
        assertEquals("20", yTickLabel(20.0))
        assertEquals("20.5", yTickLabel(20.5))
        assertEquals("-1", yTickLabel(-1.0))
    }

    @Test fun minuteLabels() {
        assertEquals("00:00", minuteOfDayLabel(0.0))
        assertEquals("09:15", minuteOfDayLabel(555.2))
        assertEquals("24:00", minuteOfDayLabel(1440.0))
    }
}
