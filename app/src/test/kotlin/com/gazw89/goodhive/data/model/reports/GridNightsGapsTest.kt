package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridNightsGapsTest {
    private fun gridTemp(ms: Long): Double {
        val t = ms.toLondon()
        return 15.0 + (t.dayOfWeek.value - 1) + t.hour * 0.1
    }

    @Test
    fun multiWeekGridAcrossAutumnChange() {
        // Mon 5 Oct 00:00 BST to Mon 26 Oct 00:00 GMT: three calendar weeks.
        val rs = series(oct(5), oct(26), 30, ::gridTemp) +
            TemperatureReading(oct(5) - 1000, 99.0) // outside the window
        val grid = hourOfWeekGrid(rs, oct(26), 3)
        assertEquals(15.7, grid.mean(DayOfWeek.MONDAY, 7)!!, 1e-9)
        assertEquals(23.3, grid.mean(DayOfWeek.SUNDAY, 23)!!, 1e-9)
        // The repeated 01:00 hour on Sunday 25 Oct is one bucket, so three weeks still give 3.
        assertEquals(3, grid.sampleCount[6][1])
        assertEquals(21.1, grid.mean(DayOfWeek.SUNDAY, 1)!!, 1e-9)
        assertTrue((0 until 7).all { d -> (0 until 24).all { h -> grid.sampleCount[d][h] == 3 } })
    }

    @Test
    fun gridMissingHourOnSpringChangeHasOneFewerSample() {
        val end = ms(2026, 4, 6)
        val grid = hourOfWeekGrid(series(ms(2026, 3, 16), end, 30, ::gridTemp), end, 3)
        assertEquals(2, grid.sampleCount[6][1]) // 29 Mar 01:00 does not exist
        assertEquals(3, grid.sampleCount[6][3])
        assertEquals(3, grid.sampleCount[6][0])
    }

    @Test
    fun gridNullWhereNoData() {
        val rs = series(oct(19), oct(20), 30, ::gridTemp) // Monday only
        val grid = hourOfWeekGrid(rs, oct(26), 2)
        assertEquals(15.7, grid.mean(DayOfWeek.MONDAY, 7)!!, 1e-9)
        assertNull(grid.mean(DayOfWeek.TUESDAY, 7))
        assertEquals(0, grid.sampleCount[1][7])
    }

    @Test
    fun gridWeeksEachCountOnceDespiteDensity() {
        // Week 1 has one reading in the hour, week 2 has six. Equal weight each.
        val rs = listOf(TemperatureReading(oct(12, 8, 0), 10.0)) +
            series(oct(19, 8, 0), oct(19, 9, 0), 10) { 20.0 }
        assertEquals(15.0, hourOfWeekGrid(rs, oct(26), 2).mean(DayOfWeek.MONDAY, 8)!!, 1e-9)
    }

    private fun night(day: Int, minC: Double) =
        series(oct(day, 22), oct(day + 1, 7), 20) { minC + abs(it - oct(day + 1, 3)) / 3_600_000.0 * 0.1 }

    @Test
    fun coldNightsThresholdIsStrictAndReportsMinTime() {
        val rs = night(10, 15.5) + night(11, 16.0) + night(12, 12.0) + night(13, 18.0)
        val cold = coldNights(rs)
        assertEquals(listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12)), cold.map { it.nightOf })
        assertEquals(15.5, cold[0].minC, 1e-9)
        assertEquals(oct(11, 3), cold[0].minAtMs)
        assertEquals(1, coldNights(rs, thresholdC = 12.5).size)
        assertEquals(4, nightMinima(rs).size)
    }

    @Test
    fun nightBoundariesAre2200InclusiveAnd0700Exclusive() {
        val rs = listOf(
            TemperatureReading(oct(10, 21, 59), 1.0), // evening: not night
            TemperatureReading(oct(10, 22, 0), 14.0),
            TemperatureReading(oct(11, 6, 59), 15.0),
            TemperatureReading(oct(11, 7, 0), 2.0), // morning: not night
        )
        val n = nightMinima(rs, minReadings = 1).single()
        assertEquals(14.0, n.minC, 1e-9)
        assertEquals(2, n.readings)
    }

    @Test
    fun sparseNightIsIgnored() {
        assertTrue(coldNights(listOf(TemperatureReading(oct(11, 6, 55), 3.0))).isEmpty())
        assertTrue(coldNights(emptyList()).isEmpty())
    }

    @Test
    fun nightsFollowTheClockAtDstChanges() {
        val autumn = series(oct(24, 22), oct(25, 7), 30) { 17.0 } // 10 real hours
        assertEquals(20, nightMinima(autumn).single().readings)
        val spring = series(ms(2026, 3, 28, 22), ms(2026, 3, 29, 7), 30) { 17.0 } // 8 real hours
        val n = nightMinima(spring).single()
        assertEquals(16, n.readings)
        assertEquals(LocalDate.of(2026, 3, 28), n.nightOf)
    }

    @Test
    fun gapsDetectedOnlyAboveThreshold() {
        val rs = series(oct(1), oct(2), 10) { 19.0 }.filter { it.epochMillis !in oct(1, 6)..oct(1, 9) }
        // Removed 06:00..09:00 inclusive, so neighbours are 05:50 and 09:10: 3h20m.
        val gaps = readingGaps(rs)
        assertEquals(1, gaps.size)
        assertEquals(oct(1, 5, 50), gaps[0].startMs)
        assertEquals(oct(1, 9, 10), gaps[0].endMs)
        assertEquals(200 * 60_000L, gaps[0].durationMs)
    }

    @Test
    fun exactlyTwoHoursIsNotAGap() {
        val rs = listOf(TemperatureReading(oct(1, 0), 19.0), TemperatureReading(oct(1, 2), 19.0), TemperatureReading(oct(1, 4, 1), 19.0))
        val gaps = readingGaps(rs)
        assertEquals(1, gaps.size)
        assertEquals(oct(1, 2), gaps[0].startMs)
        assertEquals(2, readingGaps(rs, minGapMs = 3_600_000L).size)
    }

    @Test
    fun gapsAtWindowEdgesAndEmpty() {
        val rs = series(oct(1, 6), oct(1, 7), 10) { 19.0 }
        val gaps = readingGaps(rs, windowFromMs = oct(1), windowToMs = oct(2))
        assertEquals(2, gaps.size)
        assertEquals(oct(1), gaps[0].startMs)
        assertEquals(oct(1, 6), gaps[0].endMs)
        assertEquals(oct(1, 6, 50), gaps[1].startMs)
        assertEquals(oct(2), gaps[1].endMs)

        assertTrue(readingGaps(emptyList()).isEmpty())
        val whole = readingGaps(emptyList(), windowFromMs = oct(1), windowToMs = oct(2)).single()
        assertEquals(24 * 3_600_000L, whole.durationMs)
    }
}
