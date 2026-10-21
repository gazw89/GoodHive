package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.db.CommandStatus
import com.gazw89.goodhive.data.db.PendingCommandEntity
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class CommandExecutorTest : RepositoryTestBase() {
    private suspend fun CommandExecutor.target(value: Double) = enqueue("zone", "Room", "trvcontrol", "Set target", buildTargetPayload(value), expectedStateForTarget(value))
    /** Spacing uses the test scheduler, so a 5s gap lines up with [advanceTimeBy] instead of the wall clock. */
    private fun TestScope.executor(scope: CoroutineScope = this) =
        CommandExecutor(pending, zones, repository, json, scope) { testScheduler.currentTime }

    private suspend fun otherZone() {
        val cached = zones.getAll().single()
        zones.upsertAll(listOf(cached.copy(id = "other", name = "Other")))
    }
    private fun http(code: Int, retryAfter: String? = null): HttpException {
        val raw = okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://example.test").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(code).message("Test")
            .headers(Headers.headersOf("Retry-After", retryAfter ?: "0")).build()
        return HttpException(Response.error<Unit>("rejected".toResponseBody(), raw))
    }

    @Test fun acceptedWriteRemainsPendingUntilPolledStateMatches() = runTest {
        configureToken()
        val executor = executor(this)
        val id = executor.target(21.0)
        runCurrent()
        assertEquals(CommandStatus.CONFIRMING, pending.getById(id)?.status)
        assertTrue(pending.logs.isEmpty())
        advanceTimeBy(8_000); runCurrent()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(id)?.status)
        assertEquals(1, api.writes.size)
        assertEquals(1, api.getCount)
    }

    @Test fun failedRefreshCannotConfirmAgainstPreviouslyMatchingCache() = runTest {
        configureToken()
        api.state = JsonObject(api.state + ("target" to JsonPrimitive(21.0)))
        repository.refresh()
        api.get = { throw IOException("offline") }
        val id = executor(this).target(21.0)
        advanceUntilIdle()
        assertEquals(CommandStatus.FAILED, pending.getById(id)?.status)
        assertEquals(1, api.writes.size)
        assertEquals("FAILED", pending.logs.single().outcome)
    }

    @Test fun uncertainSendThatAppliedIsReconciledBeforeAnyRetry() = runTest {
        configureToken()
        api.set = { _, body -> api.state = JsonObject(api.state + body); throw IOException("response lost") }
        val id = executor(this).target(21.0)
        advanceUntilIdle()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(id)?.status)
        assertEquals(1, api.writes.size)
    }

    @Test fun permanentRejectionFailsWithoutRepeatingWrite() = runTest {
        configureToken()
        api.set = { _, _ -> throw http(400) }
        val id = executor(this).target(21.0)
        advanceUntilIdle()
        assertEquals(CommandStatus.FAILED, pending.getById(id)?.status)
        assertEquals(1, api.writes.size)
    }

    @Test fun rejectedNoOpOnlyConfirmsWhenFreshRemoteStateMatches() = runTest {
        configureToken()
        api.state = JsonObject(api.state + ("target" to JsonPrimitive(21.0)))
        api.set = { _, _ -> throw http(400) }
        val id = executor(this).target(21.0)
        advanceUntilIdle()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(id)?.status)
        assertEquals(1, api.writes.size)
    }

    @Test fun rateLimitHonoursRetryAfterAndThenRecovers() = runTest {
        configureToken()
        api.set = { _, body -> if (api.writes.size == 1) throw http(429, "10") else api.state = JsonObject(api.state + body) }
        val id = executor(this).target(21.0)
        runCurrent(); advanceTimeBy(9_999); runCurrent()
        assertEquals(1, api.writes.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, api.writes.size)
        advanceUntilIdle()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(id)?.status)
    }

    @Test fun differentZonesShareOneOutboundGap() = runTest {
        configureToken()
        otherZone()
        val executor = executor()
        val first = executor.target(21.0)
        val second = executor.enqueue("other", "Other", "trvcontrol", "Set target", buildTargetPayload(19.0), expectedStateForTarget(19.0))
        runCurrent()
        assertEquals(1, api.writes.size)
        assertEquals(CommandStatus.CONFIRMING, pending.getById(first)?.status)
        assertEquals(CommandStatus.PENDING, pending.getById(second)?.status)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(1, api.writes.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("zone" to 21.0, "other" to 19.0), api.writes.map { it.first to it.second.getValue("target").jsonPrimitive.double })
    }

    @Test fun supersededCommandWaitingForTheGapDoesNotDelayTheNextWrite() = runTest {
        configureToken()
        otherZone()
        val executor = executor()
        executor.target(21.0)
        runCurrent()
        val dropped = executor.enqueue("other", "Other", "trvcontrol", "Set target", buildTargetPayload(19.0), expectedStateForTarget(19.0))
        runCurrent()
        val kept = executor.enqueue("other", "Other", "trvcontrol", "Set target", buildTargetPayload(22.0), expectedStateForTarget(22.0))
        runCurrent()
        assertEquals(CommandStatus.SUPERSEDED, pending.getById(dropped)?.status)
        assertEquals(1, api.writes.size)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(1, api.writes.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(21.0, 22.0), api.writes.map { it.second.getValue("target").jsonPrimitive.double })
        assertEquals(CommandStatus.CONFIRMING, pending.getById(kept)?.status)
    }

    @Test fun confirmationKeepsWatchingPastTheOldHundredSecondWindow() = runTest {
        configureToken()
        api.set = { _, _ -> }
        val id = executor().target(21.0)
        runCurrent()
        advanceTimeBy(100_000); runCurrent()
        assertEquals(CommandStatus.CONFIRMING, pending.getById(id)?.status)
        assertEquals(1, api.writes.size)
        assertTrue(pending.logs.isEmpty())
        api.state = JsonObject(api.state + ("target" to JsonPrimitive(21.0)))
        advanceUntilIdle()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(id)?.status)
        assertEquals(1, api.writes.size)
    }

    @Test fun expiredCommandConfirmsWithoutSendingWhenHiveAlreadyMatches() = runTest {
        configureToken()
        api.state = JsonObject(api.state + ("target" to JsonPrimitive(21.0)))
        val id = pending.insert(savedCommand(CommandStatus.PENDING, buildTargetPayload(21.0), expectedStateForTarget(21.0))
            .copy(createdAtEpochMillis = System.currentTimeMillis() - 16 * 60_000L))
        executor().resumeInFlightCommands()
        advanceUntilIdle()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(id)?.status)
        assertEquals(0, api.writes.size)
        assertEquals("CONFIRMED", pending.logs.single().outcome)
    }

    @Test fun newerCommandWaitsForOlderSendAndCannotBeOverwrittenByIt() = runTest {
        configureToken()
        val gate = CompletableDeferred<Unit>()
        api.set = { _, body -> if (api.writes.size == 1) gate.await(); api.state = JsonObject(api.state + body) }
        val executor = executor(this)
        val older = executor.target(19.0)
        runCurrent()
        val newer = executor.target(21.0)
        runCurrent()
        assertEquals(1, api.writes.size)
        assertEquals(CommandStatus.SUPERSEDED, pending.getById(older)?.status)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(19.0, 21.0), api.writes.map { it.second.getValue("target").jsonPrimitive.double })
        assertEquals(CommandStatus.SUPERSEDED, pending.getById(older)?.status)
        assertEquals(CommandStatus.CONFIRMED, pending.getById(newer)?.status)
        assertEquals(1, pending.logs.size)
    }

    @Test fun pendingScheduleAndHeatOnDemandAreNotSupersededByTargetChange() = runTest {
        configureToken()
        val executor = executor(this)
        val schedule = com.gazw89.goodhive.data.model.WeekSchedule(emptyMap())
        val scheduleId = executor.enqueue("zone", "Room", "trvcontrol", "Schedule", buildSchedulePayload(schedule), expectedStateForSchedule(schedule))
        val hodId = executor.enqueue("zone", "Room", "trvcontrol", "HoD", buildHeatOnDemandPayload(true, "thermostat"), expectedStateForHeatOnDemand(true))
        executor.target(21.0)
        assertEquals(CommandStatus.PENDING, pending.getById(scheduleId)?.status)
        assertEquals(CommandStatus.PENDING, pending.getById(hodId)?.status)
        advanceUntilIdle()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(scheduleId)?.status)
        assertEquals(CommandStatus.CONFIRMED, pending.getById(hodId)?.status)
        assertEquals(3, api.writes.size)
    }

    @Test fun boostThatFailsToSendIsRetriedUntilItLands() = runTest {
        configureToken()
        var attempts = 0
        api.set = { _, body -> if (++attempts < 3) throw http(503) else api.state = JsonObject(api.state + body) }
        val id = executor(this).enqueue("zone", "Room", "trvcontrol", "Boost", buildBoostPayload(30, 21.0), expectedStateForBoost(21.0))
        advanceUntilIdle()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(id)?.status)
        assertEquals(3, api.writes.size)
    }

    @Test fun boostResendNeverExtendsEndTime() {
        val payload = buildBoostPayload(30, 21.0)
        val start = 1_000_000L
        assertEquals(30, boostPayloadForSend(payload, start, start)?.get("boost")?.jsonPrimitive?.int)
        assertEquals(30, boostPayloadForSend(payload, start, start + 59_999)?.get("boost")?.jsonPrimitive?.int)
        assertEquals(29, boostPayloadForSend(payload, start, start + 60_000)?.get("boost")?.jsonPrimitive?.int)
        assertEquals(1, boostPayloadForSend(payload, start, start + 29 * 60_000L)?.get("boost")?.jsonPrimitive?.int)
        assertNull(boostPayloadForSend(payload, start, start + 30 * 60_000L))
        val target = buildTargetPayload(21.0)
        assertSame(target, boostPayloadForSend(target, start, start + 10 * 60_000L))
    }

    @Test fun repeatedResumeDoesNotDuplicateRunningCommand() = runTest {
        configureToken()
        val gate = CompletableDeferred<Unit>()
        api.set = { _, body -> gate.await(); api.state = JsonObject(api.state + body) }
        val executor = executor(this)
        executor.target(21.0)
        runCurrent()
        executor.resumeInFlightCommands()
        assertEquals(1, api.writes.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.writes.size)
    }

    @Test fun recoveredBoostIsResentWithRemainingMinutesAndExpiredCommandsAreNotSent() = runTest {
        configureToken()
        val boosted = pending.insert(savedCommand(CommandStatus.CONFIRMING, buildBoostPayload(30, 21.0), expectedStateForBoost(21.0))
            .copy(createdAtEpochMillis = System.currentTimeMillis() - 10 * 60_000L))
        val expired = pending.insert(savedCommand(CommandStatus.PENDING, buildTargetPayload(21.0), expectedStateForTarget(21.0)).copy(zoneId = "expired-zone", createdAtEpochMillis = 1L))
        executor(this).resumeInFlightCommands()
        advanceUntilIdle()
        assertEquals(CommandStatus.CONFIRMED, pending.getById(boosted)?.status)
        assertEquals(20, api.writes.single().second["boost"]?.jsonPrimitive?.int)
        assertEquals(CommandStatus.FAILED, pending.getById(expired)?.status)
    }

    @Test fun recoverySupersedesDuplicateOlderIntentBeforeSending() = runTest {
        configureToken()
        val older = pending.insert(savedCommand(CommandStatus.PENDING, buildTargetPayload(19.0), expectedStateForTarget(19.0)))
        val newer = pending.insert(savedCommand(CommandStatus.PENDING, buildTargetPayload(21.0), expectedStateForTarget(21.0)))
        executor(this).resumeInFlightCommands()
        assertEquals(CommandStatus.SUPERSEDED, pending.getById(older)?.status)
        assertEquals(CommandStatus.CONFIRMED, pending.getById(newer)?.status)
        assertEquals(1, api.writes.size)
    }

    @Test fun cancelledProcessingRemainsResumableWithoutFailureNotice() = runTest {
        configureToken()
        val gate = CompletableDeferred<Unit>()
        api.set = { _, _ -> gate.await() }
        val job = SupervisorJob()
        val executor = executor(CoroutineScope(job + StandardTestDispatcher(testScheduler)))
        val id = executor.target(21.0)
        runCurrent(); job.cancel(); runCurrent()
        assertEquals(CommandStatus.SENDING, pending.getById(id)?.status)
        assertTrue(pending.logs.isEmpty())
    }

    @Test fun emptyOrUnrecognisedConfirmationCriteriaCannotClaimSuccess() = runTest {
        configureToken()
        repository.refresh()
        val cached = zones.getAll().single()
        assertFalse(matchesExpectedState(cached.copy(reportedByHive = false), expectedStateForTarget(cached.targetTemperatureC!!), json))
        assertFalse(matchesExpectedState(cached, emptyMap(), json))
        assertFalse(matchesExpectedState(cached, mapOf("typo" to "18"), json))
        assertFalse(matchesExpectedState(cached, mapOf("target" to "NaN"), json))
        assertFailsWith<IllegalArgumentException> { executor(this).enqueue("zone", "Room", "trvcontrol", "Empty", buildTargetPayload(18.0), emptyMap()) }
    }

    private fun savedCommand(status: String, payload: JsonObject, expected: Map<String, String>) = PendingCommandEntity(
        zoneId = "zone", zoneName = "Room", productType = "trvcontrol", description = "Recovered",
        payloadJson = json.encodeToString(JsonObject.serializer(), payload),
        expectedStateJson = json.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), expected),
        status = status, createdAtEpochMillis = System.currentTimeMillis(),
    )
    @Test fun accountWipeCancelsWorkerResumedCommandBeforeDeletingRows() = runTest {
        configureToken()
        val ownScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val executor = executor(ownScope)
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        // Persisted work is resumed in the worker's scope, independently of executor's own scope.
        val id = pending.insert(PendingCommandEntity(
            zoneId = "zone", zoneName = "Room", productType = "trvcontrol", description = "Set target",
            payloadJson = """{"target":21.0}""", expectedStateJson = """{"target":"21.0"}""",
            status = CommandStatus.PENDING, createdAtEpochMillis = System.currentTimeMillis(),
        ))
        api.set = { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        }
        val worker = backgroundScope.launch { executor.resumeInFlightCommands() }
        entered.await()
        executor.clearAccountData { pending.rows.value = emptyList() }
        runCurrent()
        assertTrue(cancelled)
        assertNull(pending.getById(id))
        assertTrue(pending.logs.isEmpty())
        assertEquals(1, api.writes.size)
        worker.cancelAndJoin()
        ownScope.cancel()
    }

}
