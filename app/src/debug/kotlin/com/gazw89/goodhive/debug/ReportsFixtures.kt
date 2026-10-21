package com.gazw89.goodhive.debug

import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.ui.reports.ReportPeriodKind
import com.gazw89.goodhive.ui.reports.ReportsState
import com.gazw89.goodhive.ui.reports.ReportsStatus
import com.gazw89.goodhive.ui.reports.RoomInputs
import com.gazw89.goodhive.ui.reports.RoomReportState
import com.gazw89.goodhive.ui.reports.SyncProgress
import com.gazw89.goodhive.ui.reports.buildReportsState
import com.gazw89.goodhive.ui.reports.buildRoomReportState
import com.gazw89.goodhive.ui.reports.periodWindow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Explicitly invented data for visual verification; never read from or written to the app store.
 * Uses the real pure report builders, but never constructs a ViewModel or repository.
 */
internal object ReportsFixtures {
    const val DEFAULT_ROOM = "demo-study"
    val today: LocalDate = LocalDate.of(2026, 10, 15)
    private val london = ZoneId.of("Europe/London")
    private val nowMs = today.atTime(12, 0).atZone(london).toInstant().toEpochMilli()

    enum class Status { READY, NO_DATA, PARTIAL, LOADING, ERROR }

    data class Snapshot(val overview: ReportsState, val room: RoomReportState)

    fun build(
        status: Status,
        kind: ReportPeriodKind,
        offset: Int,
        roomId: String = DEFAULT_ROOM,
    ): Snapshot {
        val window = periodWindow(kind, offset, today)
        val first = minOf(window.prevFrom, window.toExclusive.minusWeeks(4))
        val last = minOf(window.toExclusive, today.plusDays(1))
        val schedule = WeekSchedule(WeekSchedule.DAY_KEYS.associateWith {
            listOf(ScheduleSlot(0, 16.0), ScheduleSlot(360, 20.0), ScheduleSlot(540, 17.0),
                ScheduleSlot(1020, 21.0), ScheduleSlot(1320, 16.0))
        })
        val names = listOf("Demo living room", "Demo study", "Demo spare room · unheated")
        val ids = listOf("demo-living", DEFAULT_ROOM, "demo-spare")
        val rooms = names.mapIndexed { index, name ->
            fun readings(from: LocalDate, to: LocalDate): List<TemperatureReading> = buildList {
                var date = from
                while (date < to) {
                    // Partial scenario: absent spare-room history, only three observed study days,
                    // and a midday study gap. Missing days are absent, never zero-temperature rows.
                    val omitDay = status == Status.NO_DATA ||
                        (status == Status.PARTIAL && (index == 2 ||
                            (index == 1 && date !in window.from.plusDays(1)..window.from.plusDays(3))))
                    if (!omitDay) {
                        for (minute in 0 until 1440 step 15) {
                            if (status == Status.PARTIAL && index == 1 && minute in 600 until 840) continue
                            val epoch = date.atStartOfDay(london).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
                            if (epoch >= nowMs) continue
                            val hour = minute / 60.0
                            val base = when {
                                hour < 6 -> 17.2 - hour * 0.12
                                hour < 8 -> 16.5 + (hour - 6) * 1.8
                                hour < 17 -> 20.1 - (hour - 8) * 0.13
                                hour < 19 -> 18.9 + (hour - 17) * 1.05
                                hour < 22 -> 21.0 - (hour - 19) * 0.1
                                else -> 20.7 - (hour - 22) * 1.7
                            }
                            val roomDelta = listOf(0.5, -0.6, -2.0)[index]
                            val dayDelta = (date.toEpochDay() % 7 - 3) * 0.08
                            val previousYearDelta = if (date.year < today.year) -0.4 else 0.0
                            add(TemperatureReading(epoch, base + roomDelta + dayDelta + previousYearDelta))
                        }
                    }
                    date = date.plusDays(1)
                }
            }
            RoomInputs(
                zoneId = ids[index], name = name, isValve = index != 0,
                mode = if (index == 2) "OFF" else "SCHEDULE",
                online = !(status == Status.PARTIAL && index == 1),
                battery = if (status == Status.PARTIAL && index == 1) 0 else 85 - index * 10,
                schedule = schedule,
                readings = readings(first, last),
                lastYearReadings = if (kind == ReportPeriodKind.YEAR) null
                    else readings(window.lastYearFrom, window.lastYearToExclusive),
            )
        }
        val sync = when (status) {
            Status.PARTIAL -> SyncProgress(1, 3, issueCount = 1, unsupportedCount = 1)
            Status.NO_DATA -> SyncProgress(0, 3)
            else -> SyncProgress(3, 3)
        }
        val overview = buildReportsState(rooms, window, nowMs, sync)
        val room = buildRoomReportState(roomId, rooms, window, nowMs)
        val forcedStatus = when (status) {
            Status.LOADING -> ReportsStatus.LOADING
            Status.ERROR -> ReportsStatus.ERROR
            else -> null
        }
        return Snapshot(
            overview = overview.copy(
                status = forcedStatus ?: overview.status,
                error = if (status == Status.ERROR) "Demo error: synthetic history unavailable. Retry stays offline." else null,
            ),
            room = room.copy(
                status = forcedStatus ?: room.status,
                error = if (status == Status.ERROR) "Demo error: synthetic room history unavailable." else null,
            ),
        )
    }
}
