package com.gazw89.goodhive.data.repository

import android.util.Log
import com.gazw89.goodhive.BuildConfig
import com.gazw89.goodhive.data.db.HeatingZoneCacheDao
import com.gazw89.goodhive.data.db.HeatingZoneCacheEntity
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.network.HiveApiService
import com.gazw89.goodhive.data.network.HiveNodesResponse
import com.gazw89.goodhive.data.network.activityProbeLines
import com.gazw89.goodhive.data.network.toHeatingZones
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong
import retrofit2.HttpException

/**
 * Single source of truth for heating zone state. UI reads only from Room (instant render
 * on launch from cache); [refresh] is what actually talks to Hive and keeps that cache —
 * current. Each snapshot also feeds [ZoneStateLogger] (our own mode/target/schedule history).
 * Temperature history is stored separately, by [HistorySyncRepository].
 *
 * Refresh is single-flight and rate-limited: concurrent callers share one nodes/all,
 * recent successes are reused, and HTTP 403 triggers an exponential backoff so we do not
 * keep hammering Beekeeper into a longer forbid window.
 */
@Singleton
class HeatingRepository @Inject constructor(
    private val requestRunner: HiveRequestRunner,
    private val hiveApiService: HiveApiService,
    private val zoneCacheDao: HeatingZoneCacheDao,
    private val json: Json,
    private val stateLogger: ZoneStateLogger,
) {
    private val refreshMutex = Mutex()
    private var lastSuccessAtEpochMillis = 0L
    private var lastSuccessStartedAtEpochMillis = 0L
    private var lastAttemptStartedAtEpochMillis = 0L
    private var lastRefreshFailure: Exception? = null
    private val refreshGeneration = AtomicLong()
    private var backoffUntilEpochMillis = 0L
    private var consecutive403Count = 0
    private var lastProbeLines: List<String> = emptyList()
    private val _foregroundActive = MutableStateFlow(false)
    val foregroundActive = _foregroundActive.asStateFlow()

    fun setForegroundActive(active: Boolean) { _foregroundActive.value = active }

    fun observeZones(): Flow<List<HeatingZone>> =
        zoneCacheDao.observeAll().map { cached -> cached.map { it.toDomain(json) } }

    /**
     * Polls Hive and updates the Room cache, retaining unreported products until manual removal.
     *
     * @param force ignore the min-gap reuse of a recent success (still shares overlapping fetches and respects backoff).
     * @param notBeforeEpochMillis only reuse an observation whose fetch started after this write completed.
     * @return true if a network fetch ran; false if skipped because a recent success is fresh enough.
     */
    suspend fun refresh(force: Boolean = false, notBeforeEpochMillis: Long = 0L): Boolean {
        val generationAtRequest = refreshGeneration.get()
        return refreshMutex.withLock {
            val now = System.currentTimeMillis()
            if (refreshGeneration.get() != generationAtRequest && lastAttemptStartedAtEpochMillis >= notBeforeEpochMillis) {
                lastRefreshFailure?.let { throw it }
                return@withLock false
            }
            if (now < backoffUntilEpochMillis) {
                val retryAfter = backoffUntilEpochMillis - now
                Log.w(TAG, "Skipping nodes/all — Hive read backoff ${retryAfter}ms remaining")
                throw HiveRateLimitedException(retryAfterMs = retryAfter)
            }
            if (lastSuccessStartedAtEpochMillis >= notBeforeEpochMillis &&
                !force && lastSuccessAtEpochMillis > 0L && now - lastSuccessAtEpochMillis < MIN_REFRESH_GAP_MS
            ) {
                Log.d(TAG, "Skipping nodes/all — last success ${now - lastSuccessAtEpochMillis}ms ago")
                return@withLock false
            }

            try {
                lastAttemptStartedAtEpochMillis = now
                fetchAndPersist()
                lastSuccessAtEpochMillis = System.currentTimeMillis()
                lastSuccessStartedAtEpochMillis = now
                refreshGeneration.incrementAndGet()
                lastRefreshFailure = null
                consecutive403Count = 0
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                val failure = if (err is HttpException && (err.code() == 403 || err.code() == 429)) {
                    consecutive403Count += 1
                    val backoffMs = maxOf(retryAfterMillis(err) ?: 0L, min(
                        MAX_403_BACKOFF_MS,
                        BASE_403_BACKOFF_MS * (1L shl (consecutive403Count - 1).coerceAtMost(4)),
                    ))
                    backoffUntilEpochMillis = System.currentTimeMillis() + backoffMs
                    Log.w(
                        TAG,
                        "Hive nodes/all HTTP ${err.code()} (#$consecutive403Count) — backing off ${backoffMs}ms",
                    )
                    HiveRateLimitedException(retryAfterMs = backoffMs, cause = err)
                } else err
                lastRefreshFailure = failure
                refreshGeneration.incrementAndGet()
                throw failure
            }
        }
    }

    /**
     * nodes/all is a read, so replaying it is free. Hive's API drops connections and 5xxs often enough that one
     * quick retry pair spares the user a failed refresh. 429/403 are left to the backoff in [refresh].
     */
    private suspend fun <T> retryTransientRead(block: suspend () -> T): T {
        for (delayMs in TRANSIENT_READ_RETRY_DELAYS_MS) {
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                val transient = err is IOException || (err is HttpException && (err.code() == 408 || err.code() in 500..599))
                if (!transient) throw err
            }
            delay(delayMs)
        }
        return block()
    }

    private suspend fun fetchAndPersist() {
        val response = retryTransientRead { requestRunner.call { token -> hiveApiService.getAllNodes(token = token) } }
        if (BuildConfig.DEBUG) logActivityProbe(response)
        val zones = response.toHeatingZones()
        val now = System.currentTimeMillis()

        // A successful nodes/all response is authoritative. Hive omits state.autoBoost when
        // Heat on Demand is disabled on a TRV, so retaining the previous cached value here
        // would turn a real external OFF change into a permanently stale ON in GoodHive.
        zoneCacheDao.applySnapshot(zones.map { it.toCacheEntity(now, json) }, response.products.map { it.id })
        stateLogger.record(zones, now)
    }

    /** Drain a refresh before the wipe, and reset account-specific refresh reuse/backoff. */
    suspend fun clearAccountData(clearDatabase: suspend () -> Unit) = refreshMutex.withLock {
        clearDatabase()
        stateLogger.clear()
        lastSuccessAtEpochMillis = 0L
        lastSuccessStartedAtEpochMillis = 0L
        lastAttemptStartedAtEpochMillis = 0L
        lastRefreshFailure = null
        backoffUntilEpochMillis = 0L
        consecutive403Count = 0
        lastProbeLines = emptyList()
        refreshGeneration.incrementAndGet()
    }

    /** Local cache removal only; never sends a Hive API write. */
    suspend fun removeUnreportedDevice(zoneId: String): Boolean = refreshMutex.withLock {
        zoneCacheDao.removeUnreported(zoneId)
    }

    /** Debug builds only: logs the whitelisted activity fields whenever they change between polls. */
    private fun logActivityProbe(response: HiveNodesResponse) {
        val lines = response.activityProbeLines()
        if (lines == lastProbeLines) return
        lastProbeLines = lines
        lines.forEach { Log.i(PROBE_TAG, it) }
    }

    /**
     * Sends a raw state change to a zone. User-initiated changes go through CommandExecutor
     * to check Hive's subsequently reported state. Neither the POST nor that observation
     * proves that a physical valve or receiver applied the setting.
     */
    suspend fun setState(zoneId: String, productType: String, payload: JsonObject) {
        check(zoneCacheDao.getById(zoneId)?.reportedByHive == true) { "Hive no longer lists this device" }
        requestRunner.call { token ->
            hiveApiService.setState(token = token, type = productType, id = zoneId, body = payload)
        }
    }

    /**
     * Zone id of the primary thermostat that TRV Heat on Demand must link to when enabling.
     * Prefer the heating product's `props.zone`; fall back to any TRV that still has a linked zone.
     */
    suspend fun thermostatHiveZoneId(): String? {
        val cached = zoneCacheDao.getAll().filter { it.reportedByHive }
        cached.firstOrNull {
            it.productType == ProductType.HEATING && !it.hiveZoneId.isNullOrBlank()
        }?.hiveZoneId?.let { return it }
        return cached.firstOrNull { !it.hiveZoneId.isNullOrBlank() }?.hiveZoneId
    }

    companion object {
        private const val TAG = "GoodHiveSync"
        private const val PROBE_TAG = "HiveProbe"
        /** Dashboard/command callers within this window share the last successful fetch. */
        private const val MIN_REFRESH_GAP_MS = 8_000L
        private const val BASE_403_BACKOFF_MS = 15_000L
        private val TRANSIENT_READ_RETRY_DELAYS_MS = longArrayOf(1_000, 3_000)
        private const val MAX_403_BACKOFF_MS = 120_000L
    }
}

/** Hive refused nodes/all (typically rate-limit). Wait [retryAfterMs] before forcing another attempt. */
class HiveRateLimitedException(
    val retryAfterMs: Long,
    cause: Throwable? = null,
) : Exception("Hive rate-limited requests — retry in ${(retryAfterMs + 999) / 1000}s", cause)

fun buildTargetPayload(targetC: Double): JsonObject = buildJsonObject { put("target", targetC) }
fun expectedStateForTarget(targetC: Double): Map<String, String> = mapOf("target" to targetC.toString())

/**
 * Mode-change write. `target` rules:
 * - MANUAL: [targetC] is sent and expected.
 * - OFF: `target` is sent as [offTargetC] (callers pass the zone's `frostProtectionC`, default 7°),
 *   never the UI's current target. Confirmed live: `{"mode":"OFF"}` alone returns 200 but does NOT
 *   apply during a Heat on Demand boost; `{"mode":"OFF","target":N}` does (Pyhiveapi
 *   `set_boost_off` also always sends a target with OFF). It is not *expected* back — Hive
 *   restores its own OFF setpoint, so [expectedStateForMode] is mode-only for OFF.
 * - SCHEDULE: [scheduleTargetC] must be the target of the zone's schedule slot in force *now*
 *   (`zone.scheduleNowNextLater()?.currentTargetC`), never the zone's reported/optimistic target.
 *   Confirmed live 2026-10-15: after OFF, a mode-only `{"mode":"SCHEDULE"}` leaves Hive reporting
 *   `target=7` (the OFF frost setpoint) until the next slot, so the room stays effectively off.
 *   `{"mode":"SCHEDULE","target":<current slot target>}` fixes it and creates no override
 *   (`props.scheduleOverride=false`). The old mode-only rule guarded against a *stale* target (e.g.
 *   a boost's 25°) creating an override; the current slot's target is not stale. When the slot
 *   target is unknown (null) this stays mode-only and mode-only is expected.
 * - Anything else: mode only.
 */
fun buildModePayload(
    mode: String,
    targetC: Double? = null,
    offTargetC: Double? = null,
    scheduleTargetC: Double? = null,
): JsonObject = buildJsonObject {
    put("mode", mode)
    when (mode) {
        HeatingMode.MANUAL -> targetC?.let { put("target", it) }
        HeatingMode.OFF -> put("target", offTargetC ?: DEFAULT_OFF_TARGET_C)
        HeatingMode.SCHEDULE -> scheduleTargetC?.let { put("target", it) }
    }
}
fun expectedStateForMode(
    mode: String,
    targetC: Double? = null,
    scheduleTargetC: Double? = null,
): Map<String, String> = buildMap {
    put("mode", mode)
    when (mode) {
        HeatingMode.MANUAL -> targetC?.let { put("target", it.toString()) }
        HeatingMode.SCHEDULE -> scheduleTargetC?.let { put("target", it.toString()) }
    }
}

private const val DEFAULT_OFF_TARGET_C = 7.0

/** Setpoint Pyhiveapi's `set_boost_off` falls back to when restoring OFF/MANUAL without a known target. */
private const val CANCEL_BOOST_DEFAULT_TARGET_C = 7.0

/**
 * Cancel-boost write, mirroring Pyhiveapi `set_boost_off`: if the mode before the boost
 * (`props.previous`) was MANUAL or OFF, restore it with its target; otherwise hand control back
 * to SCHEDULE with [scheduleTargetC], the current schedule slot's target (see [buildModePayload]).
 * Never the boost's 25°: that stale target risks a lingering override. Mode-only when the slot
 * target is unknown.
 */
fun buildCancelBoostPayload(
    previousMode: String?,
    previousTargetC: Double?,
    scheduleTargetC: Double? = null,
): JsonObject =
    buildJsonObject {
        if (previousMode == HeatingMode.MANUAL || previousMode == HeatingMode.OFF) {
            put("mode", previousMode)
            put("target", previousTargetC ?: CANCEL_BOOST_DEFAULT_TARGET_C)
        } else {
            put("mode", HeatingMode.SCHEDULE)
            scheduleTargetC?.let { put("target", it) }
        }
    }
fun expectedStateForCancelBoost(
    previousMode: String?,
    previousTargetC: Double?,
    scheduleTargetC: Double? = null,
): Map<String, String> =
    if (previousMode == HeatingMode.MANUAL || previousMode == HeatingMode.OFF) {
        mapOf(
            "mode" to previousMode,
            "target" to (previousTargetC ?: CANCEL_BOOST_DEFAULT_TARGET_C).toString(),
        )
    } else {
        expectedStateForMode(HeatingMode.SCHEDULE, scheduleTargetC = scheduleTargetC)
    }

fun buildBoostPayload(minutes: Int, targetC: Double): JsonObject = buildJsonObject {
    put("mode", "BOOST")
    put("boost", minutes)
    put("target", targetC)
}
fun expectedStateForBoost(targetC: Double): Map<String, String> =
    mapOf("mode" to "BOOST", "target" to targetC.toString())

/**
 * Hive Heat on Demand write payloads (confirmed against a live account, 2026-08):
 * - Enable: `{"autoBoost":"ENABLED","zone":"<thermostat props.zone>"}` — `autoBoost` alone
 *   returns HTTP 200 but never sticks.
 * - Disable: `{"zone":""}` — `{"autoBoost":"DISABLED"}` returns HTTP 400 MALFORMED_REQUEST
 *   while HoD is on; clearing the zone link is what turns it off.
 */
fun buildHeatOnDemandPayload(enabled: Boolean, thermostatHiveZoneId: String? = null): JsonObject =
    buildJsonObject {
        if (enabled) {
            val zoneId = requireNotNull(thermostatHiveZoneId) {
                "Enabling Heat on demand requires the primary thermostat Hive zone id"
            }
            put("autoBoost", "ENABLED")
            put("zone", zoneId)
        } else {
            put("zone", "")
        }
    }

fun expectedStateForHeatOnDemand(enabled: Boolean): Map<String, String> =
    mapOf("autoBoost" to if (enabled) "ENABLED" else "DISABLED")

/**
 * Hive omits `state.autoBoost` when HoD is disabled on a TRV. Missing therefore means
 * DISABLED — missing ≡ authoritative disabled.
 */
fun effectiveHeatOnDemand(raw: String?): String = if (raw == "ENABLED") "ENABLED" else "DISABLED"

/**
 * Full-week schedule write payload. Hive expects every day key present; callers always send the
 * entire week (not a single-day patch) so partial merges can't silently drop other days.
 */
fun buildSchedulePayload(schedule: WeekSchedule): JsonObject = buildJsonObject {
    put(
        "schedule",
        buildJsonObject {
            WeekSchedule.DAY_KEYS.forEach { day ->
                put(
                    day,
                    buildJsonArray {
                        schedule.days[day].orEmpty().forEach { slot ->
                            add(
                                buildJsonObject {
                                    put("start", slot.startMinutes)
                                    put("value", buildJsonObject { put("target", slot.targetTemperatureC) })
                                },
                            )
                        }
                    },
                )
            }
        },
    )
}

/**
 * Canonical schedule fingerprint for CommandExecutor confirmation — sorted day keys and slots,
 * targets rounded to 0.1°C so float noise from Hive doesn't fail a match.
 */
fun canonicalizeWeekSchedule(schedule: WeekSchedule): String =
    WeekSchedule.DAY_KEYS.joinToString("|") { day ->
        val slots = schedule.days[day].orEmpty()
            .sortedBy { it.startMinutes }
            .joinToString(",") { slot ->
                val t = (Math.round(slot.targetTemperatureC * 10.0) / 10.0)
                "${slot.startMinutes}@${"%.1f".format(t)}"
            }
        "$day:$slots"
    }

fun expectedStateForSchedule(schedule: WeekSchedule): Map<String, String> =
    mapOf("schedule" to canonicalizeWeekSchedule(schedule))

internal fun HeatingZone.toCacheEntity(nowEpochMillis: Long, json: Json) = HeatingZoneCacheEntity(
    id = id,
    productType = productType,
    name = name,
    online = online,
    currentTemperatureC = currentTemperatureC,
    targetTemperatureC = targetTemperatureC,
    mode = mode,
    frostProtectionC = frostProtectionC,
    maxEventsPerDay = maxEventsPerDay,
    scheduleJson = json.encodeToString(WeekScheduleSurrogate.serializer(), schedule.toSurrogate()),
    heatOnDemand = heatOnDemand,
    heatOnDemandAvailable = heatOnDemandAvailable,
    hiveZoneId = hiveZoneId,
    batteryLevel = batteryLevel,
    working = working,
    autoBoostActive = autoBoostActive,
    autoBoostTrvIdsJson = json.encodeToString(StringListSerializer, autoBoostTrvIds),
    historySourceIdsJson = json.encodeToString(StringListSerializer, historySourceIds),
    previousMode = previousMode,
    previousTargetC = previousTargetC,
    boostMinutesLeft = boostMinutesLeft,
    lastSyncedAtEpochMillis = nowEpochMillis,
    reportedByHive = reportedByHive,
)

internal fun HeatingZoneCacheEntity.toDomain(json: Json): HeatingZone = HeatingZone(
    id = id,
    productType = productType,
    name = name,
    online = online,
    currentTemperatureC = currentTemperatureC,
    targetTemperatureC = targetTemperatureC,
    mode = mode,
    frostProtectionC = frostProtectionC,
    maxEventsPerDay = maxEventsPerDay,
    schedule = json.decodeFromString(WeekScheduleSurrogate.serializer(), scheduleJson).toDomain(),
    heatOnDemand = heatOnDemand,
    heatOnDemandAvailable = heatOnDemandAvailable,
    hiveZoneId = hiveZoneId,
    batteryLevel = batteryLevel,
    working = working,
    autoBoostActive = autoBoostActive,
    autoBoostTrvIds = runCatching { json.decodeFromString(StringListSerializer, autoBoostTrvIdsJson) }
        .getOrDefault(emptyList()),
    historySourceIds = runCatching { json.decodeFromString(StringListSerializer, historySourceIdsJson) }
        .getOrDefault(emptyList()),
    previousMode = previousMode,
    previousTargetC = previousTargetC,
    boostMinutesLeft = boostMinutesLeft,
    reportedByHive = reportedByHive,
)

private val StringListSerializer = ListSerializer(String.serializer())

/** Decode Room/cache schedule JSON into a [WeekSchedule] for confirmation matching. */
fun weekScheduleFromCacheJson(json: Json, scheduleJson: String): WeekSchedule =
    json.decodeFromString(WeekScheduleSurrogate.serializer(), scheduleJson).toDomain()

/**
 * Manual JsonObject<->WeekSchedule mapping (rather than teaching kotlinx.serialization
 * about WeekSchedule directly) since the day keys are dynamic and this shape mirrors the
 * raw API on purpose for easy debugging.
 */
@kotlinx.serialization.Serializable
internal data class WeekScheduleSurrogate(val days: Map<String, List<SlotSurrogate>>)

@kotlinx.serialization.Serializable
internal data class SlotSurrogate(val start: Int, val target: Double)

internal fun WeekSchedule.toSurrogate() = WeekScheduleSurrogate(
    days.mapValues { (_, slots) -> slots.map { SlotSurrogate(it.startMinutes, it.targetTemperatureC) } },
)

internal fun WeekScheduleSurrogate.toDomain() = WeekSchedule(
    days.mapValues { (_, slots) -> slots.map { ScheduleSlot(it.start, it.target) } },
)
