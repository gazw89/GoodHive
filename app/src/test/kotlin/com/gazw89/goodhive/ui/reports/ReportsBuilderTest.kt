package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.model.reports.*
import com.gazw89.goodhive.util.GoodHiveTime
import java.time.LocalDate
import org.junit.Test
import kotlin.test.*

class ReportsBuilderTest {
    private val today = LocalDate.of(2026, 10, 15)
    private val window = periodWindow(ReportPeriodKind.WEEK, 0, today)
    private fun ms(date: LocalDate, hour: Int = 0): Long = date.atTime(hour, 0).atZone(GoodHiveTime.londonZone).toInstant().toEpochMilli()
    private val now = ms(today, 12)
    private val schedule = WeekSchedule(WeekSchedule.DAY_KEYS.associateWith { listOf(ScheduleSlot(0, 20.0)) })
    private fun days(from: LocalDate, count: Int, temp: Double) = (0 until count).flatMap { i ->
        (0..23).map { h -> TemperatureReading(ms(from.plusDays(i.toLong()), h), temp) }
    }
    private fun room(id: String = "room", readings: List<TemperatureReading>, mode: String = "SCHEDULE") = RoomInputs(
        id, "Room $id", true, mode, true, 80, schedule, readings)
    private fun stat(date: LocalDate, temp: Double, partial: Boolean = false) = DailyStats(date, temp, temp, temp, 24, partial)

    @Test fun `zero difference has grammatical comparison copy`() {
        assertEquals("About the same as previous week", comparisonPhrase(0.0, "previous week"))
        assertEquals("About the same as previous week", comparisonPhrase(0.04, "previous week"))
        assertEquals("1.0° warmer than previous week", comparisonPhrase(1.0, "previous week"))
    }
    @Test fun `all observed elapsed days use concise today exclusion copy`() {
        val state = buildReportsState(listOf(room(readings = days(window.from, window.dayCount - 1, 20.0))), window, now)
        assertEquals("3 completed days; today is excluded.", state.hero!!.coverageNote)
    }
    @Test fun `empty household finishes loading without invented values`() {
        val state = buildReportsState(emptyList(), window, now)
        assertEquals(ReportsStatus.NO_DATA, state.status)
        assertNull(state.hero)
    }

    @Test fun `rooms remain reachable without any readings`() {
        val state = buildReportsState(listOf(room(readings = emptyList())), window, now)
        assertEquals(ReportsStatus.NO_DATA, state.status)
        assertEquals(listOf("room" to "Room room"), state.roomLinks)
        assertNull(state.dailyChart)
    }

    @Test fun `today is displayed partial and excluded from differences`() {
        val rs = days(window.from, window.dayCount - 1, 20.0) + days(window.prevFrom, 7, 18.0) + days(today, 1, 40.0)
        val state = buildReportsState(listOf(room(readings = rs)), window, now)
        assertEquals(20.0, state.hero!!.avgC)
        assertEquals(2.0, state.hero!!.deltaC)
        assertEquals(window.dayCount - 1, state.hero!!.daysCounted)
        assertTrue(state.dailyChart!!.tableRows.last().first().contains("partial"))
    }

    @Test fun `missing rooms are partial and never counted as zero`() {
        val state = buildReportsState(listOf(room("a", days(window.from, window.dayCount - 1, 20.0)), room("b", emptyList())), window, now)
        assertEquals(20.0, state.hero!!.avgC)
        assertEquals(0, state.hero!!.daysCounted)
        assertNull(state.hero!!.deltaC)
        assertTrue(state.hero!!.coverageNote!!.contains("0 of ${window.dayCount - 1}"))
    }

    @Test fun `comparison excludes disjoint availability rather than comparing different dates`() {
        val cmp = matchedComparison(listOf(stat(window.from, 20.0)),
            listOf(stat(window.prevFrom.plusDays(1), 10.0)), window)
        assertNull(cmp.avgDifferenceC)
        assertEquals(0, cmp.current.days)
    }

    @Test fun `year comparison uses same calendar date around leap years`() {
        val w = periodWindow(ReportPeriodKind.YEAR, 0, LocalDate.of(2024, 3, 2))
        val current = listOf(stat(LocalDate.of(2024, 2, 28), 20.0), stat(LocalDate.of(2024, 2, 29), 40.0), stat(LocalDate.of(2024, 3, 1), 22.0))
        val previous = listOf(stat(LocalDate.of(2023, 2, 28), 19.0), stat(LocalDate.of(2023, 3, 1), 21.0))
        val cmp = matchedComparison(current, previous, w)
        assertEquals(2, cmp.current.days)
        assertEquals(1.0, cmp.avgDifferenceC)
    }

    @Test fun `year chart averages match observed dates on both sides`() {
        val w = periodWindow(ReportPeriodKind.YEAR, 0, today)
        val rs = days(LocalDate.of(2026, 1, 1), 1, 20.0) + days(LocalDate.of(2026, 1, 2), 1, 40.0) +
            days(LocalDate.of(2025, 1, 1), 1, 19.0)
        val state = buildReportsState(listOf(room(readings = rs)), w, now)
        assertEquals(20.0, state.year!!.monthly!!.current.single().y)
        assertEquals(19.0, state.year!!.monthly!!.previous.single().y)
        assertEquals(1.0, state.year!!.rooms.single().deltaC)
        assertTrue(state.year!!.rooms.single().partial)
    }

    @Test fun `off and manual rooms have no scheduled target score`() {
        listOf("OFF", "MANUAL", "BOOST", "HOLIDAY").forEach { mode ->
            val r = room(readings = days(window.from, window.dayCount - 1, 20.0), mode = mode)
            val overview = buildReportsState(listOf(r), window, now)
            assertNull(overview.timeOnTarget.single().share)
            val detail = buildRoomReportState(r.zoneId, listOf(r), window, now)
            val tile = detail.tiles.single { it.title == "Readings near scheduled target" }
            assertEquals("Not scored in current mode", tile.detail)
        }
    }

    @Test fun `above target readings do not cause poor performance or warmup advice`() {
        val r = room(readings = days(window.from, window.dayCount - 1, 25.0))
        val state = buildReportsState(listOf(r), window, now)
        assertEquals(0.0, state.timeOnTarget.single().share)
        assertTrue(state.timeOnTarget.single().isEstimate)
        assertFalse(state.insights.any { it.type in setOf(InsightType.LOW_TIME_ON_TARGET, InsightType.SLOW_WARM_UP, InsightType.VALVE_COOLS_FASTER) })
        val detail = buildRoomReportState(r.zoneId, listOf(r), window, now)
        assertTrue(detail.warmUps.isEmpty())
        assertFalse(detail.tiles.any { it.title.contains("warm-up") || it.title.contains("cool-down") })
    }

    @Test fun `missing reading evidence stops at now instead of tomorrow`() {
        val r = room(readings = days(window.from, window.dayCount - 1, 20.0) + (0..11).map { TemperatureReading(ms(today, it), 20.0) })
        val state = buildReportsState(listOf(r), window, now)
        assertFalse(state.insights.any { it.type == InsightType.MISSING_READINGS })
        val detail = buildRoomReportState(r.zoneId, listOf(r), window, now)
        assertNull(detail.missing)
    }

    @Test fun `cold night threshold comes from preference config`() {
        val w = periodWindow(ReportPeriodKind.WEEK, 1, today)
        val r = room(readings = days(w.from, 7, 15.0))
        val warmThreshold = buildReportsState(listOf(r), w, now, config = InsightConfig(coldNightThresholdC = 16.0))
        val lowThreshold = buildReportsState(listOf(r), w, now, config = InsightConfig(coldNightThresholdC = 14.0))
        assertTrue(warmThreshold.insights.any { it.type == InsightType.COLD_NIGHTS })
        assertFalse(lowThreshold.insights.any { it.type == InsightType.COLD_NIGHTS })
    }

    @Test fun `night coverage excludes six clustered samples and unfinished night`() {
        val night = today.minusDays(1)
        val clustered = (0..5).map { TemperatureReading(ms(night, 22) + it * 60_000L, 10.0) }
        assertTrue(observedNights(clustered, ms(night), now).isEmpty())
        val rs = (22..30).map { TemperatureReading(ms(night, 22) + (it - 22) * 3_600_000L, 10.0) }
        assertTrue(observedNights(rs, ms(night), ms(today, 6)).isEmpty())
        assertEquals(1, observedNights(rs, ms(night), now).size)
    }

    @Test fun `night starts at wall 22 on both DST change dates`() {
        listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25)).forEach { date ->
            val start = ms(date, 22)
            // Exactly seven distinct hours, starting at 22. An elapsed +22h start drops one in spring.
            val rs = (0..6).map { TemperatureReading(start + it * 3_600_000L, 15.0) }
            assertEquals(1, observedNights(rs, ms(date), ms(date.plusDays(1), 8)).size, date.toString())
        }
    }

    @Test fun `missing days have explicit table gaps`() {
        val r = room(readings = days(window.from, 1, 20.0) + days(window.from.plusDays(2), 1, 22.0))
        val state = buildReportsState(listOf(r), window, now)
        assertEquals(window.dayCount, state.dailyChart!!.tableRows.size)
        assertEquals("No readings", state.dailyChart!!.tableRows[1][1])
    }

    @Test fun `unknown room is no data rather than infinite loading`() {
        val state = buildRoomReportState("deleted", emptyList(), window, now)
        assertEquals(ReportsStatus.NO_DATA, state.status)
    }
}
