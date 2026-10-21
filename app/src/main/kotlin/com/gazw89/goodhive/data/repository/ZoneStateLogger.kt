package com.gazw89.goodhive.data.repository

import android.util.Log
import com.gazw89.goodhive.data.db.ScheduleSnapshotEntity
import com.gazw89.goodhive.data.db.ZoneStateDao
import com.gazw89.goodhive.data.db.ZoneStateLogEntity
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.util.scheduleNowNextLater
import java.time.Instant
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Builds our own mode/target history, which Hive does not expose. [record] is called from
 * [HeatingRepository.refresh] with every snapshot and writes a `zone_state_log` row only when a
 * logged value differs from the zone's latest row or five minutes have passed, and a
 * `schedule_snapshot` only when the canonical schedule changes. The latest values are held in memory (seeded from Room once), so an
 * unchanged refresh within five minutes costs no database work. A periodic checkpoint records
 * fresh observations without implying that the state remained unchanged between polls.
 */
@Singleton
class ZoneStateLogger @Inject constructor(
    private val dao: ZoneStateDao,
    private val json: Json,
) {
    private val lock = Mutex()
    private var lastState: MutableMap<String, ZoneStateLogEntity>? = null
    private var lastSchedule: MutableMap<String, String>? = null

    /** Never throws (other than cancellation): a logging problem must not fail a refresh. */
    suspend fun record(zones: List<HeatingZone>, nowMs: Long) {
        try {
            lock.withLock { recordLocked(zones, nowMs) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            Log.w(TAG, "State log write failed: ${err.message}")
        }
    }

    /** Deletes the whole log. Used by sign-out. */
    suspend fun clear() = lock.withLock {
        dao.clearLog()
        lastState = null
        lastSchedule = null
    }

    private suspend fun recordLocked(zones: List<HeatingZone>, nowMs: Long) {
        val states = lastState ?: dao.latestStatePerZone().associateBy { it.zoneId }.toMutableMap().also { lastState = it }
        val schedules = lastSchedule ?: dao.latestSchedulePerZone().associate { snapshot ->
            snapshot.zoneId to (
                runCatching { canonicalizeWeekSchedule(weekScheduleFromCacheJson(json, snapshot.scheduleJson)) }.getOrNull() ?: ""
                )
        }.toMutableMap().also { lastSchedule = it }

        for (zone in zones) {
            if (!zone.reportedByHive) continue
            val entry = zone.toLogEntry(nowMs)
            val previous = states[zone.id]
            if (previous == null || !previous.sameLoggedValues(entry) || nowMs - previous.epochMs >= OBSERVATION_CHECKPOINT_MS) {
                dao.insertState(entry)
                states[zone.id] = entry
            }
            val canonical = canonicalizeWeekSchedule(zone.schedule)
            val known = schedules[zone.id]
            val hasSlots = zone.schedule.days.values.any { it.isNotEmpty() }
            if (known != canonical && (known != null || hasSlots)) {
                dao.insertSchedule(
                    ScheduleSnapshotEntity(
                        zoneId = zone.id, fromMs = nowMs,
                        scheduleJson = zone.toCacheEntity(nowMs, json).scheduleJson,
                    ),
                )
                schedules[zone.id] = canonical
            }
        }
    }

    private companion object {
        const val TAG = "GoodHiveSync"
        // Checkpoints show observations resumed after a gap; they do not prove continuity.
        const val OBSERVATION_CHECKPOINT_MS = 5 * 60_000L
    }
}

/**
 * The values worth logging for [this] zone at [nowMs].
 *
 * `scheduleOverride` is derived (the cache does not store Hive's `props.scheduleOverride`): in
 * SCHEDULE mode, true when the target differs from the current slot's by more than 0.1 degrees.
 * A user BOOST is a BOOST mode that is not a Heat on Demand one (`props.autoBoost.active`).
 */
internal fun HeatingZone.toLogEntry(nowMs: Long): ZoneStateLogEntity {
    val hod = autoBoostActive == true
    val override = if (mode == HeatingMode.SCHEDULE && targetTemperatureC != null) {
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), GoodHiveTime.londonZone)
        scheduleNowNextLater(now)?.let { abs(it.currentTargetC - targetTemperatureC) > 0.1 }
    } else {
        null
    }
    return ZoneStateLogEntity(
        zoneId = id, epochMs = nowMs, mode = mode, targetC = targetTemperatureC,
        scheduleOverride = override,
        boostActive = mode == HeatingMode.BOOST && !hod,
        hodActive = hod, working = working, online = online, battery = batteryLevel,
    )
}

/** Equal in every logged value; the row id and timestamp are ignored. */
internal fun ZoneStateLogEntity.sameLoggedValues(other: ZoneStateLogEntity): Boolean =
    copy(id = 0, epochMs = 0) == other.copy(id = 0, epochMs = 0)
