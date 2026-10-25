package com.gazw89.goodhive.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Latest known state per zone — read on launch for an instant dashboard, before the first poll returns. */
@Entity(tableName = "heating_zone_cache")
data class HeatingZoneCacheEntity(
    @PrimaryKey val id: String,
    val productType: String,
    val name: String,
    val online: Boolean,
    val currentTemperatureC: Double?,
    val targetTemperatureC: Double?,
    val mode: String,
    val frostProtectionC: Double,
    val maxEventsPerDay: Int,
    val scheduleJson: String,
    val heatOnDemand: String?,
    val heatOnDemandAvailable: Boolean = false,
    /** See [com.gazw89.goodhive.data.model.HeatingZone.hiveZoneId]. */
    val hiveZoneId: String? = null,
    /** See [com.gazw89.goodhive.data.model.HeatingZone.batteryLevel]. */
    val batteryLevel: Int? = null,
    /** See [com.gazw89.goodhive.data.model.HeatingZone.working]. */
    val working: Boolean? = null,
    /** See [com.gazw89.goodhive.data.model.HeatingZone.autoBoostActive]. */
    val autoBoostActive: Boolean? = null,
    /** JSON array of strings — see [com.gazw89.goodhive.data.model.HeatingZone.autoBoostTrvIds]. */
    val autoBoostTrvIdsJson: String = "[]",
    /** JSON array of strings — see [com.gazw89.goodhive.data.model.HeatingZone.historySourceIds]. */
    @ColumnInfo(defaultValue = "[]")
    val historySourceIdsJson: String = "[]",
    /** See [com.gazw89.goodhive.data.model.HeatingZone.previousMode]. */
    val previousMode: String? = null,
    /** See [com.gazw89.goodhive.data.model.HeatingZone.previousTargetC]. */
    val previousTargetC: Double? = null,
    /** See [com.gazw89.goodhive.data.model.HeatingZone.boostMinutesLeft]. */
    val boostMinutesLeft: Int? = null,
    val lastSyncedAtEpochMillis: Long,
    /** Last complete Hive snapshot listed this product, including devices reported offline. */
    val reportedByHive: Boolean = true,
)

/** Command lifecycle states — see the command reliability layer in the plan doc. */
object CommandStatus {
    const val PENDING = "PENDING"
    const val SENDING = "SENDING"
    const val CONFIRMING = "CONFIRMING"
    const val CONFIRMED = "CONFIRMED"
    const val FAILED = "FAILED"
    const val SUPERSEDED = "SUPERSEDED"
}

/**
 * A durable queued write. Persisted the instant the user confirms a change, before any
 * network call — survives the app being backgrounded or killed mid-flight.
 */
@Entity(tableName = "pending_command")
data class PendingCommandEntity(
    @PrimaryKey(autoGenerate = true) val commandId: Long = 0,
    val zoneId: String,
    val zoneName: String,
    val productType: String,
    val description: String,
    /** JSON body to POST to /nodes/{productType}/{zoneId}, e.g. {"target":19.0}. */
    val payloadJson: String,
    /** Fields we expect Hive to report after the write; this is not a physical-device acknowledgement. */
    val expectedStateJson: String,
    val status: String,
    val createdAtEpochMillis: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtEpochMillis: Long? = null,
)

/** Command outcomes used to surface failures and support acknowledgement/retry. */
@Entity(tableName = "command_log")
data class CommandLogEntity(
    @PrimaryKey(autoGenerate = true) val logId: Long = 0,
    val zoneId: String,
    val zoneName: String,
    val description: String,
    val outcome: String,
    val createdAtEpochMillis: Long,
    val resolvedAtEpochMillis: Long,
    val attemptCount: Int,
)

/**
 * User's manual drag-to-reorder position for a TRV/room card on the dashboard — the main
 * thermostat is always pinned first and never has a row here. Lower position sorts first.
 */
@Entity(tableName = "room_order")
data class RoomOrderEntity(
    @PrimaryKey val zoneId: String,
    val position: Int,
)

/** One raw 5-minute (AVG) temperature reading for a Hive history source id. See HistorySyncRepository. */
@Entity(tableName = "history_reading", primaryKeys = ["sourceId", "epochMs"])
data class HistoryReadingEntity(
    val sourceId: String,
    val epochMs: Long,
    val tempC: Double,
)

/** Per-source sync bookkeeping. [oldestMs]/[newestMs] bound the window already fetched. */
@Entity(tableName = "history_sync")
data class HistorySyncEntity(
    @PrimaryKey val sourceId: String,
    val oldestMs: Long? = null,
    val newestMs: Long? = null,
    val backfillDone: Boolean = false,
    val lastAttemptMs: Long? = null,
    /** Skipped windows, or [UNSUPPORTED]. Null when nothing went wrong. */
    val lastError: String? = null,
) {
    companion object {
        const val UNSUPPORTED = "unsupported"
    }
}

/** Appended by HeatingRepository.refresh() only when a logged value changed for the zone. */
@Entity(tableName = "zone_state_log", indices = [Index(value = ["zoneId", "epochMs"])])
data class ZoneStateLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val zoneId: String,
    val epochMs: Long,
    val mode: String,
    val targetC: Double?,
    /** Derived: SCHEDULE mode with a target differing from the slot's. Null when not computable. */
    val scheduleOverride: Boolean?,
    /** A user-started BOOST (not a Heat on Demand one). */
    val boostActive: Boolean,
    /** `props.autoBoost.active`. */
    val hodActive: Boolean,
    val working: Boolean?,
    val online: Boolean,
    val battery: Int?,
)

/** The zone's schedule from [fromMs] until the next snapshot. [scheduleJson] is the cache JSON. */
@Entity(tableName = "schedule_snapshot", indices = [Index(value = ["zoneId", "fromMs"])])
data class ScheduleSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val zoneId: String,
    val fromMs: Long,
    val scheduleJson: String,
)

/**
 * One Beekeeper write (`POST`), sanitised before insert. Polls and login calls are not stored.
 * [path] is the path and query only. [responseCode] and [error] are null when the other applies:
 * a transport failure has no code; a completed HTTP call has no [error].
 */
@Entity(tableName = "hive_request_log", indices = [Index(value = ["startedAtEpochMillis"])])
data class HiveRequestLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMillis: Long,
    val method: String,
    val path: String,
    val requestBody: String,
    val responseCode: Int?,
    val durationMs: Long,
    val responseBody: String,
    val error: String?,
)

/** A user-built routine. [stepsJson] is the serialised `List<RoutineStep>`, always edited with its routine. */
@Entity(tableName = "routine")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String,
    /** Dashboard order; lower sorts first. */
    val position: Int,
    val stepsJson: String,
    val createdAtEpochMillis: Long,
)
