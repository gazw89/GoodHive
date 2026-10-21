package com.gazw89.goodhive.ui.detail

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.ui.components.chart.ChartPoint
import com.gazw89.goodhive.ui.components.chart.ChartViewport
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.util.ScheduleStep
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TodayTemperatureCardTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun wallClockMinuteInBst() {
        // 2026-07-01T09:30Z is 10:30 BST.
        assertEquals(10 * 60 + 30, wallClockMinuteOf(ms("2026-07-01T09:30:00Z"), LocalDate.of(2026, 7, 1)))
    }

    @Test fun wallClockMinuteDropsOtherDays() {
        // 23:30Z on 30 Jun is 00:30 BST on 1 Jul, so it is not on 30 Jun.
        assertNull(wallClockMinuteOf(ms("2026-06-30T23:30:00Z"), LocalDate.of(2026, 6, 30)))
        assertEquals(30, wallClockMinuteOf(ms("2026-06-30T23:30:00Z"), LocalDate.of(2026, 7, 1)))
    }

    @Test fun dstFallBackDayStaysMonotonicAndWallClock() {
        val day = LocalDate.of(2026, 10, 25)
        val readings = listOf(
            TemperatureReading(ms("2026-10-25T00:30:00Z"), 18.0), // 01:30 BST
            TemperatureReading(ms("2026-10-25T01:30:00Z"), 18.5), // 01:30 GMT (repeated hour)
            TemperatureReading(ms("2026-10-25T02:30:00Z"), 19.0), // 02:30 GMT
            TemperatureReading(ms("2026-10-25T23:30:00Z"), 17.0), // 23:30 GMT
            TemperatureReading(ms("2026-10-24T22:00:00Z"), 16.0), // 23:00 BST on the 24th: dropped
        )
        val pts = roomChartPoints(readings, day, nowX = 1400.0, currentC = null)
        // 01:30 BST kept; 01:30 GMT repeat dropped (x not increasing); then 02:30 and 23:30.
        assertEquals(listOf(90.0, 150.0, 1410.0), pts.map { it.x })
        assertEquals(listOf(18.0, 19.0, 17.0), pts.map { it.y })
    }

    @Test fun liveTailAppendedOnlyWhenNotAhead() {
        val day = LocalDate.of(2026, 7, 1)
        val r = listOf(TemperatureReading(ms("2026-07-01T09:00:00Z"), 19.0)) // 10:00 BST = 600
        assertEquals(ChartPoint(700.0, 20.2), roomChartPoints(r, day, 700.0, 20.2).last())
        assertEquals(1, roomChartPoints(r, day, 500.0, 20.2).size)
        assertEquals(1, roomChartPoints(r, day, 700.0, null).size)
        assertEquals(0, roomChartPoints(emptyList(), day, 700.0, 20.2).size)
    }

    @Test fun scheduleClosingPoint() {
        val steps = listOf(ScheduleStep(0, 390, 13.0), ScheduleStep(390, 1320, 19.0), ScheduleStep(1320, 1440, 13.0))
        val pts = scheduleChartPoints(steps)
        assertEquals(listOf(0.0, 390.0, 1320.0, 1440.0), pts.map { it.x })
        assertEquals(13.0, pts.last().y)
        assertEquals(emptyList(), scheduleChartPoints(emptyList()))
    }

    @Test fun descriptionFull() {
        val steps = listOf(ScheduleStep(0, 390, 13.0), ScheduleStep(390, 1320, 19.0), ScheduleStep(1320, 1440, 13.0))
        assertEquals(
            "Today. Room between 19.7 and 20.2 degrees, now 20.2. Schedule: 13 degrees until 06:30, 19 until 22:00, then 13.",
            todayChartDescription(listOf(19.7, 20.0, 20.2), 20.2, steps),
        )
    }

    @Test fun descriptionWithoutRoomOrSchedule() {
        assertEquals("Today.", todayChartDescription(emptyList(), null, emptyList()))
        assertEquals(
            "Today. Schedule: 18 degrees all day.",
            todayChartDescription(emptyList(), 17.0, listOf(ScheduleStep(0, 600, 18.0), ScheduleStep(600, 1440, 18.0))),
        )
    }

    @Test fun descriptionUnchangedAtFullDayAndAppendsWindowWhenZoomed() {
        assertEquals("Today.", zoomedChartDescription("Today.", ChartViewport.FullDay))
        assertEquals(
            "Today. Zoomed to 09:15 to 11:45.",
            zoomedChartDescription("Today.", ChartViewport(555.0, 705.0)),
        )
        assertEquals(
            "Today. Zoomed to 21:00 to 24:00.",
            zoomedChartDescription("Today.", ChartViewport(1260.0, 1440.0)),
        )
    }

    // Zoom state resets when the room changes because TodayTemperatureCard holds it in
    // `remember(zone.id) { mutableStateOf(ChartViewport.FullDay) }` (never rememberSaveable / ViewModel);
    // that is Compose runtime behaviour and needs an instrumented test, so the pure part is covered here:
    // a fresh viewport is exactly the full day and a reset from any zoom returns to that same value.
    @Test fun freshAndResetViewportAreIdentical() {
        val zoomedIn = ChartViewport.FullDay.zoomed(5.0, 0.4)
        assertEquals(ChartViewport.FullDay, zoomedIn.reset())
    }

    @Test fun londonZoneIsUsedByDefault() {
        assertEquals("Europe/London", GoodHiveTime.londonZone.id)
    }
}
