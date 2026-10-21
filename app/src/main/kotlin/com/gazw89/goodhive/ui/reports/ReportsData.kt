package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.data.db.HistorySyncEntity
import com.gazw89.goodhive.data.db.ZoneStateDao
import com.gazw89.goodhive.data.db.ZoneStateLogEntity
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.reports.ModeInterval
import com.gazw89.goodhive.data.model.reports.ScheduleAt
import com.gazw89.goodhive.data.model.reports.TimeInterval
import com.gazw89.goodhive.data.repository.HeatingRepository
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import com.gazw89.goodhive.data.repository.weekScheduleFromCacheJson
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** Each logged observation supports at most five minutes. Missing heartbeats remain unknown. */
fun modeIntervalsFromLog(entries: List<ZoneStateLogEntity>, nowMs: Long): List<ModeInterval> {
    val sorted = entries.sortedBy { it.epochMs }.distinctBy { it.epochMs }
    return sorted.mapIndexed { i, e ->
        val end = minOf(sorted.getOrNull(i + 1)?.epochMs ?: nowMs, e.epochMs + 5 * 60_000L)
        val mode = if (e.boostActive || e.hodActive) "BOOST" else if (e.scheduleOverride == true) "MANUAL" else e.mode
        ModeInterval(e.epochMs, maxOf(e.epochMs, end), mode)
    }
}

/** Spans during which a user boost or Heat on Demand boost was active. */
fun boostIntervalsFromLog(entries: List<ZoneStateLogEntity>, nowMs: Long): List<TimeInterval> {
    val sorted = entries.sortedBy { it.epochMs }.distinctBy { it.epochMs }
    return sorted.mapIndexedNotNull { i, e ->
        if (e.boostActive || e.hodActive) {
            TimeInterval(e.epochMs, maxOf(e.epochMs, minOf(sorted.getOrNull(i + 1)?.epochMs ?: nowMs, e.epochMs + 5 * 60_000L)))
        } else {
            null
        }
    }
}

/**
 * Rooms finished = every history source of the room is backfilled, unsupported, or ended with
 * recorded gaps. Null when no room has a source.
 */
fun syncProgress(zones: List<HeatingZone>, rows: List<HistorySyncEntity>): SyncProgress? {
    val withSources = zones.filter { it.historySourceIds.isNotEmpty() }
    if (withSources.isEmpty()) return null
    val byId = rows.associateBy { it.sourceId }
    val done = withSources.count { zone ->
        zone.historySourceIds.all { id ->
            val row = byId[id]
            row != null && (row.backfillDone || row.lastError == HistorySyncEntity.UNSUPPORTED)
        }
    }
    val relevant = rows.filter { it.sourceId in withSources.flatMap { z -> z.historySourceIds } }
    return SyncProgress(done, withSources.size,
        issueCount = relevant.count { it.lastError != null && it.lastError != HistorySyncEntity.UNSUPPORTED },
        unsupportedCount = relevant.count { it.lastError == HistorySyncEntity.UNSUPPORTED })
}

/** Loads the stored readings and state log for every room; shared by both Reports ViewModels. */
@Singleton
class ReportsDataSource @Inject constructor(
    private val heatingRepository: HeatingRepository,
    private val historySync: HistorySyncRepository,
    private val stateDao: ZoneStateDao,
    private val json: Json,
) {
    /** Thermostat first, then valves by name. */
    fun observeZones(): Flow<List<HeatingZone>> =
        heatingRepository.observeZones().map { zones ->
            zones.filter { it.reportedByHive }.sortedWith(compareBy({ it.productType != ProductType.HEATING }, { it.name }))
        }.distinctUntilChanged { old, new ->
            // Live temperatures change every poll; reports depend on stored history instead.
            fun reportKey(z: HeatingZone) = listOf(z.id, z.name, z.productType, z.mode,
                z.online, z.batteryLevel, z.schedule, z.historySourceIds)
            old.map(::reportKey) == new.map(::reportKey)
        }

    fun observeProgress(): Flow<List<HistorySyncEntity>> = historySync.observeProgress()

    @OptIn(FlowPreview::class)
    fun observeInputs(zones: List<HeatingZone>, window: PeriodWindow, nowMs: () -> Long): Flow<List<RoomInputs>> {
        if (zones.isEmpty()) return flowOf(emptyList())
        // Include four weeks for the heatmap and the most recent finished winter of the selected year.
        val selectedEnd = window.toExclusive.minusDays(1)
        val winterEndYear = if (selectedEnd.monthValue >= 3) selectedEnd.year else selectedEnd.year - 1
        val winterFrom = java.time.LocalDate.of(winterEndYear - 1, 12, 1)
        val from = minOf(window.prevFromMs,
            com.gazw89.goodhive.util.GoodHiveTime.londonStartOfDay(window.toExclusive.minusWeeks(4)).toEpochMilli(),
            if (window.kind == ReportPeriodKind.YEAR) com.gazw89.goodhive.util.GoodHiveTime.londonStartOfDay(winterFrom).toEpochMilli() else Long.MAX_VALUE)
        val perRoom = zones.map { zone ->
            val main = historySync.readings(zone.historySourceIds, from, window.toMs)
            combine(main, stateDao.observeStateLog(zone.id), stateDao.observeSchedules(zone.id)) { readings, log, schedules ->
                val snapshots = schedules.mapNotNull { s ->
                    runCatching { ScheduleAt(s.fromMs, weekScheduleFromCacheJson(json, s.scheduleJson)) }.getOrNull()
                }
                RoomInputs(
                    zoneId = zone.id, name = zone.name,
                    isValve = zone.productType == ProductType.TRV_CONTROL,
                    mode = zone.mode, online = zone.online, battery = zone.batteryLevel,
                    schedule = zone.schedule, readings = readings,
                    scheduleSnapshots = snapshots,
                    modeIntervals = modeIntervalsFromLog(log, nowMs()),
                    boostIntervals = boostIntervalsFromLog(log, nowMs()),
                )
            }
        }
        return combine(perRoom) { it.toList() }.debounce(300)
    }
}
