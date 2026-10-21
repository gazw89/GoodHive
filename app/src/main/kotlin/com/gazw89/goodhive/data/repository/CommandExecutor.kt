package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.auth.HiveAuthException
import com.gazw89.goodhive.data.db.CommandStatus
import com.gazw89.goodhive.data.db.HeatingZoneCacheDao
import com.gazw89.goodhive.data.db.HeatingZoneCacheEntity
import com.gazw89.goodhive.data.db.PendingCommandDao
import com.gazw89.goodhive.data.db.PendingCommandEntity
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.network.toWeekSchedule
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/** Durable writes with immediate UI intent, bounded retries, and cloud-state confirmation. */
@Singleton
class CommandExecutor internal constructor(
    private val pendingCommandDao: PendingCommandDao,
    private val zoneCacheDao: HeatingZoneCacheDao,
    private val heatingRepository: HeatingRepository,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    @Inject constructor(
        pendingCommandDao: PendingCommandDao,
        zoneCacheDao: HeatingZoneCacheDao,
        heatingRepository: HeatingRepository,
        json: Json,
    ) : this(pendingCommandDao, zoneCacheDao, heatingRepository, json, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    private val inFlight = ConcurrentHashMap.newKeySet<Long>()
    private val processingJobs = ConcurrentHashMap<Long, Job>()
    private val sendLocks = ConcurrentHashMap<String, Mutex>()
    private val enqueueMutex = Mutex()
    private val activeStatuses = listOf(CommandStatus.PENDING, CommandStatus.SENDING, CommandStatus.CONFIRMING)

    /** Stop account-owned commands and hold enqueueing until the account wipe finishes. */
    suspend fun clearAccountData(clear: suspend () -> Unit) = enqueueMutex.withLock {
        val jobs = (scope.coroutineContext[Job]?.children.orEmpty().toList() + processingJobs.values).distinct()
        jobs.forEach { it.cancel() }
        jobs.forEach { it.cancelAndJoin() }
        clear()
    }

    fun observePending(): Flow<List<PendingCommandEntity>> = pendingCommandDao.observeWithStatuses(activeStatuses)

    /** All screens retain pending intent, including after navigation or a process restart. */
    fun observeZones(): Flow<List<HeatingZone>> = combine(heatingRepository.observeZones(), observePending()) { zones, commands ->
        zones.map { zone ->
            commands.filter { it.zoneId == zone.id }.fold(zone) { displayed, command ->
                applyPendingState(displayed, json.decodeFromString(JsonObject.serializer(), command.payloadJson))
            }
        }
    }

    suspend fun awaitSettled(commandId: Long) {
        pendingCommandDao.observeById(commandId).first { it == null || it.status !in activeStatuses }
    }

    suspend fun enqueue(
        zoneId: String,
        zoneName: String,
        productType: String,
        description: String,
        payload: JsonObject,
        expectedState: Map<String, String>,
    ): Long {
        require(expectedState.isNotEmpty()) { "A device change must have confirmation criteria" }
        val commandId = enqueueMutex.withLock {
            val supersededIds = pendingCommandDao.withStatuses(activeStatuses)
                .filter { it.zoneId == zoneId && commandGroup(json.decodeFromString(JsonObject.serializer(), it.payloadJson)) == commandGroup(payload) }
                .map { it.commandId }
            pendingCommandDao.insertSuperseding(
                PendingCommandEntity(
                    zoneId = zoneId,
                    zoneName = zoneName,
                    productType = productType,
                    description = description,
                    payloadJson = json.encodeToString(JsonObject.serializer(), payload),
                    expectedStateJson = json.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), expectedState),
                    status = CommandStatus.PENDING,
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
                supersededIds,
                activeStatuses,
            )
        }
        scope.launch { process(commandId) }
        return commandId
    }

    suspend fun retryLatestFailed(zoneId: String): Boolean {
        val failed = pendingCommandDao.latestWithStatus(zoneId, CommandStatus.FAILED) ?: return false
        enqueue(
            failed.zoneId, failed.zoneName, failed.productType, "Retry: ${failed.description}",
            json.decodeFromString(JsonObject.serializer(), failed.payloadJson),
            json.decodeFromString(kotlinx.serialization.serializer<Map<String, String>>(), failed.expectedStateJson),
        )
        return true
    }

    /** The worker owns resumed jobs until they finish; they do not outlive its execution window. */
    suspend fun resumeInFlightCommands() = coroutineScope {
        val commands = enqueueMutex.withLock {
            val active = pendingCommandDao.withStatuses(activeStatuses)
            val latest = active.groupBy {
                it.zoneId to commandGroup(json.decodeFromString(JsonObject.serializer(), it.payloadJson))
            }.values.map { group -> group.maxBy { it.commandId } }
            val latestIds = latest.map { it.commandId }.toSet()
            pendingCommandDao.supersede(active.filter { it.commandId !in latestIds }.map { it.commandId }, activeStatuses)
            latest
        }
        commands.forEach { command -> launch { process(command.commandId) } }
    }

    private suspend fun process(commandId: Long) {
        val registered = enqueueMutex.withLock {
            if (!inFlight.add(commandId)) false else {
                processingJobs[commandId] = currentCoroutineContext().job
                true
            }
        }
        if (!registered) return
        try {
            val initial = reloadOrAbort(commandId) ?: return
            if (System.currentTimeMillis() - initial.createdAtEpochMillis > MAX_RESUME_AGE_MS) {
                finish(commandId, "Change expired before it could be confirmed — please retry")
                return
            }
            val payload = json.decodeFromString(JsonObject.serializer(), initial.payloadJson)
            val expected = json.decodeFromString(kotlinx.serialization.serializer<Map<String, String>>(), initial.expectedStateJson)
            if (expected.isEmpty()) {
                finish(commandId, "This saved change has no confirmation criteria — please retry")
                return
            }
            var accepted = initial.status != CommandStatus.PENDING
            var observedAfter = System.currentTimeMillis()
            var retryAfterMs = 0L

            // Process-death recovery and HoD no-ops must observe before sending anything.
            if (accepted || commandGroup(payload) == "heatOnDemand") {
                if (refreshAndMatches(commandId, expected, observedAfter)) { finish(commandId); return }
                accepted = false
            }

            if (!accepted) {
                for ((index, backoff) in SEND_DELAYS_MS.withIndex()) {
                    delay(maxOf(backoff, retryAfterMs))
                    reloadOrAbort(commandId) ?: return
                    // A resend must not push the boost's end time later, so each attempt sends only the minutes left.
                    val sendPayload = boostPayloadForSend(payload, initial.createdAtEpochMillis, System.currentTimeMillis())
                    if (sendPayload == null) { finish(commandId, "The boost time has already passed — please retry"); return }
                    val result = sendLocks.getOrPut(initial.zoneId) { Mutex() }.withLock {
                        val command = reloadOrAbort(commandId) ?: return
                        if (pendingCommandDao.advanceIfActive(commandId, CommandStatus.SENDING, command.attemptCount + 1, System.currentTimeMillis()) == 0) return
                        catching { heatingRepository.setState(command.zoneId, command.productType, sendPayload) }
                    }
                    observedAfter = System.currentTimeMillis()
                    if (result.isSuccess) { accepted = true; break }
                    val error = result.exceptionOrNull()!!
                    // An error/timeout may have occurred AFTER Hive applied the write. Reconcile
                    // before resending.
                    if (refreshAndMatches(commandId, expected, observedAfter)) { finish(commandId); return }
                    if (!error.isRetryable()) {
                        finish(commandId, "Hive rejected the change — ${error.message ?: "please retry"}")
                        return
                    }
                    retryAfterMs = retryAfterMillis(error as? HttpException) ?: if ((error as? HttpException)?.code() == 429) 15_000L else 0L
                    if (retryAfterMs > MAX_RETRY_AFTER_MS) {
                        finish(commandId, "Hive is busy — please retry later")
                        return
                    }
                    if (index == SEND_DELAYS_MS.lastIndex) break
                }
            }
            if (!accepted) { finish(commandId, "Couldn't confirm whether Hive received the change — please retry"); return }
            val command = reloadOrAbort(commandId) ?: return
            if (pendingCommandDao.advanceIfActive(commandId, CommandStatus.CONFIRMING, command.attemptCount, command.lastAttemptAtEpochMillis) == 0) return

            for (waitMs in CONFIRM_DELAYS_MS) {
                delay(waitMs)
                reloadOrAbort(commandId) ?: return
                if (refreshAndMatches(commandId, expected, observedAfter)) { finish(commandId); return }
            }
            // No blind resend: missing confirmation is not proof of a failed write. The durable retry
            // affordance lets the user choose what to do after this bounded observation window.
            finish(commandId, "Couldn't confirm the change with Hive — check the device or retry")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finish(commandId, "Couldn't complete the change — ${error.message ?: "please retry"}")
        } finally {
            processingJobs.remove(commandId)
            inFlight.remove(commandId)
        }
    }

    private suspend fun refreshAndMatches(commandId: Long, expected: Map<String, String>, notBefore: Long): Boolean {
        if (catching { heatingRepository.refresh(force = true, notBeforeEpochMillis = notBefore) }.isFailure) return false
        val command = reloadOrAbort(commandId) ?: return false
        val cached = zoneCacheDao.getById(command.zoneId) ?: return false
        return cached.lastSyncedAtEpochMillis >= notBefore && matchesExpectedState(cached, expected, json)
    }

    private suspend fun reloadOrAbort(commandId: Long): PendingCommandEntity? =
        pendingCommandDao.getById(commandId)?.takeIf { it.status in activeStatuses }

    private suspend fun finish(commandId: Long, failure: String? = null) {
        pendingCommandDao.finishIfActive(
            commandId,
            if (failure == null) CommandStatus.CONFIRMED else CommandStatus.FAILED,
            failure?.let { " — $it" }.orEmpty(),
            System.currentTimeMillis(),
        )
    }

    private companion object {
        val SEND_DELAYS_MS = longArrayOf(0, 2_000, 5_000, 15_000, 30_000)
        val CONFIRM_DELAYS_MS = longArrayOf(8_000, 10_000, 12_000, 15_000, 15_000, 20_000, 20_000)
        const val MAX_RESUME_AGE_MS = 15 * 60_000L
        const val MAX_RETRY_AFTER_MS = 120_000L
    }
}

/** Cancellation is control flow, never a failed network operation to silently retry. */
internal suspend inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}

/**
 * Hive's `boost` is minutes from receipt, so replaying the original body restarts the clock. Sending the minutes
 * still left (rounded up, so never short) keeps the end time fixed however many attempts it takes. Null once
 * the boost would already be over; non-boost payloads pass through unchanged.
 */
internal fun boostPayloadForSend(payload: JsonObject, createdAtEpochMillis: Long, nowEpochMillis: Long): JsonObject? {
    val minutes = payload["boost"]?.jsonPrimitive?.intOrNull ?: return payload
    val remaining = minutes - (nowEpochMillis - createdAtEpochMillis).coerceAtLeast(0) / 60_000
    if (remaining <= 0) return null
    return JsonObject(payload + ("boost" to JsonPrimitive(remaining.toInt())))
}

internal fun commandGroup(payload: JsonObject): String = when {
    "schedule" in payload -> "schedule"
    "autoBoost" in payload || "zone" in payload -> "heatOnDemand"
    else -> "heating"
}

internal fun Throwable.isRetryable(): Boolean = when (this) {
    is HttpException -> code() == 408 || code() == 429 || code() in 500..599
    is IOException -> true
    is HiveAuthException -> !isDefiniteSessionRejection &&
        (cognitoErrorType == "TooManyRequestsException" || httpStatusCode == 429 || (httpStatusCode ?: 0) >= 500)
    else -> false
}

internal fun retryAfterMillis(error: HttpException?): Long? {
    val raw = error?.response()?.headers()?.get("Retry-After") ?: return null
    raw.toLongOrNull()?.let { return it.coerceIn(0, Long.MAX_VALUE / 1000) * 1000 }
    return runCatching {
        (ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
    }.getOrNull()
}

internal fun matchesExpectedState(cached: HeatingZoneCacheEntity, expected: Map<String, String>, json: Json): Boolean {
    if (!cached.reportedByHive || expected.isEmpty()) return false
    expected["target"]?.let { raw ->
        val target = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return false
        val actual = cached.targetTemperatureC?.takeIf { it.isFinite() } ?: return false
        if (kotlin.math.abs(actual - target) > 0.1) return false
    }
    expected["mode"]?.let { if (cached.mode != it) return false }
    expected["autoBoost"]?.let { if (effectiveHeatOnDemand(cached.heatOnDemand) != it) return false }
    expected["schedule"]?.let {
        val actual = runCatching { canonicalizeWeekSchedule(weekScheduleFromCacheJson(json, cached.scheduleJson)) }.getOrNull() ?: return false
        if (actual != it) return false
    }
    return expected.keys.all { it in setOf("target", "mode", "autoBoost", "schedule") }
}

internal fun applyPendingState(zone: HeatingZone, payload: JsonObject): HeatingZone {
    val schedule = (payload["schedule"] as? JsonObject)?.toWeekSchedule()
    return zone.copy(
        mode = payload["mode"]?.jsonPrimitive?.contentOrNull ?: zone.mode,
        targetTemperatureC = payload["target"]?.jsonPrimitive?.doubleOrNull ?: zone.targetTemperatureC,
        heatOnDemand = when {
            "autoBoost" in payload -> payload["autoBoost"]?.jsonPrimitive?.contentOrNull
            "zone" in payload && payload["zone"]?.jsonPrimitive?.contentOrNull == "" -> "DISABLED"
            else -> zone.heatOnDemand
        },
        schedule = schedule ?: zone.schedule,
    )
}
