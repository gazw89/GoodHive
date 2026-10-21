package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyStatsTest {
    @Test
    fun basicDay() {
        val rs = series(oct(24), oct(25), 15) { 18.0 + (it - oct(24)) / 3_600_000.0 * 0.1 }
        val day = dailyStats(rs).single()
        assertEquals(LocalDate.of(2026, 10, 24), day.date)
        assertEquals(96, day.count)
        assertEquals(18.0, day.lowC, 1e-9)
        assertEquals(20.375, day.highC, 1e-9)
        assertEquals(19.1875, day.avgC, 1e-9)
        assertFalse(day.partial)
    }

    @Test
    fun autumnChangeDayIs25HoursAndStaysOneDay() {
        val rs = series(oct(24), oct(27), 15) { 19.0 }
        val days = dailyStats(rs).associateBy { it.date }
        assertEquals(96, days.getValue(LocalDate.of(2026, 10, 24)).count)
        val change = days.getValue(LocalDate.of(2026, 10, 25))
        assertEquals(100, change.count)
        assertFalse(change.partial)
        assertEquals(96, days.getValue(LocalDate.of(2026, 10, 26)).count)
    }

    @Test
    fun springChangeDayIs23Hours() {
        val rs = series(ms(2026, 3, 28), ms(2026, 3, 31), 15) { 19.0 }
        val days = dailyStats(rs).associateBy { it.date }
        val change = days.getValue(LocalDate.of(2026, 3, 29))
        assertEquals(92, change.count)
        assertFalse(change.partial)
        assertEquals(3, days.size)
    }

    @Test
    fun lateUtcEveningIsNextLondonDayInSummer() {
        val r = TemperatureReading(Instant.parse("2026-06-30T23:30:00Z").toEpochMilli(), 20.0)
        assertEquals(LocalDate.of(2026, 7, 1), dailyStats(listOf(r)).single().date)
    }

    @Test
    fun partialWhenFewHoursCovered() {
        val rs = series(oct(20, 12), oct(20, 15), 10) { 19.0 }
        assertTrue(dailyStats(rs).single().partial)
    }

    @Test
    fun todayIsPartialWhenNowGiven() {
        val rs = series(oct(20), oct(21), 15) { 19.0 }
        assertFalse(dailyStats(rs).single().partial)
        assertTrue(dailyStats(rs, nowMs = oct(20, 14)).single().partial)
    }

    @Test
    fun emptyAndNonFiniteInput() {
        assertTrue(dailyStats(emptyList()).isEmpty())
        assertTrue(dailyStats(listOf(TemperatureReading(oct(1), Double.NaN))).isEmpty())
    }

    @Test
    fun compareWindowsUsesMeanOfDailyAverages() {
        fun day(d: Int, avg: Double, partial: Boolean = false) =
            DailyStats(LocalDate.of(2026, 10, d), avg - 1, avg, avg + 2, 96, partial)
        val cmp = compareWindows(
            current = listOf(day(1, 18.0), day(2, 20.0), day(3, 5.0, partial = true)),
            previous = listOf(day(1, 17.0), day(2, 17.0)),
        )
        assertEquals(2, cmp.current.days)
        assertEquals(19.0, cmp.current.avgC!!, 1e-9)
        assertEquals(17.0, cmp.previous.avgC!!, 1e-9)
        assertEquals(2.0, cmp.avgDifferenceC!!, 1e-9)
        assertEquals(2.0, cmp.lowDifferenceC!!, 1e-9)
        assertEquals(2.0, cmp.highDifferenceC!!, 1e-9)
        val withPartial = compareWindows(listOf(day(1, 18.0), day(3, 6.0, true)), listOf(day(1, 18.0)), includePartialDays = true)
        assertEquals(12.0, withPartial.current.avgC!!, 1e-9)
    }

    @Test
    fun compareWindowsWithEmptySideHasNullDifference() {
        val cmp = compareWindows(emptyList(), emptyList())
        assertNull(cmp.avgDifferenceC)
        assertNull(cmp.current.avgC)
        assertEquals(0, cmp.current.days)
    }

    @Test
    fun wholeHomeIsMeanOfRoomAverages() {
        fun day(d: Int, avg: Double, partial: Boolean = false) =
            DailyStats(LocalDate.of(2026, 10, d), avg - 1, avg, avg + 1, 10, partial)
        // A chatty room must not outweigh a quiet one: mean of averages, not of readings.
        val home = wholeHomeDaily(
            listOf(
                listOf(day(1, 18.0), day(2, 18.0)),
                listOf(day(1, 20.0, partial = true)),
                listOf(day(1, 22.0), day(2, 20.0)),
            ),
        )
        assertEquals(2, home.size)
        assertEquals(20.0, home[0].avgC, 1e-9)
        assertEquals(19.0, home[0].lowC, 1e-9)
        assertEquals(30, home[0].count)
        assertTrue(home[0].partial)
        assertEquals(19.0, home[1].avgC, 1e-9)
        assertTrue(home[1].partial) // A completely missing room-day is incomplete too.
    }

    @Test
    fun gridCellApiReturnsNullWhenEmpty() {
        val grid = hourOfWeekGrid(emptyList(), oct(26), 3)
        assertNull(grid.mean(DayOfWeek.MONDAY, 7))
        assertNotNull(grid.sampleCount)
    }
}
