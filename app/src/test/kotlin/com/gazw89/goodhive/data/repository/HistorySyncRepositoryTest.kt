package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.db.HistorySyncEntity
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.network.HiveHistoryPoint
import com.gazw89.goodhive.data.network.HiveHistoryResponse
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistorySyncRepositoryTest : RepositoryTestBase() {
    private val day = 24L * 60 * 60 * 1000
    private var now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
    private val historyDao = FakeHistoryDao()
    private val sync get() = HistorySyncRepository(
        HistoryFetcher(api, HiveRequestRunner(auth)), historyDao, zones, stateLogger, json,
    ) { now }

    private fun httpError(code: Int, body: String = "") =
        HttpException(Response.error<Any>(code, body.toResponseBody()))

    private fun zone(id: String, vararg sources: String) = HeatingZone(
        id = id, productType = ProductType.TRV_CONTROL, name = id, online = true,
        currentTemperatureC = 19.0, targetTemperatureC = 20.0, mode = "SCHEDULE", frostProtectionC = 7.0,
        maxEventsPerDay = 6, schedule = WeekSchedule(emptyMap()), heatOnDemand = null,
        historySourceIds = sources.toList(),
    ).toCacheEntity(0L, json)

    private fun point(epoch: Long, t: Double = 19.0) = HiveHistoryPoint(date = epoch.toString(), temperature = t)

    private fun givenZones(vararg z: com.gazw89.goodhive.data.db.HeatingZoneCacheEntity) { zones.rows.value = z.toList() }

    /** A response with a single reading at the start of every window, so each chunk adds a distinct row. */
    private fun answerWithWindowStart() {
        api.history = { call -> HiveHistoryResponse(listOf(point(call.start + 1_000))) }
    }

    @Test fun storedStatsCountRowsAndReportNewestAttempt() = runTest {
        configureToken()
        givenZones(zone("z", "s1"))
        assertEquals(StoredHistoryStats(0, null), sync.observeStoredStats().first())
        answerWithWindowStart()
        sync.sync()
        val stats = sync.observeStoredStats().first()
        assertTrue(stats.rowCount > 0)
        assertEquals(now, stats.lastSyncMs)
        assertEquals(stats.rowCount * 120L, stats.approxBytes)
    }

    @Test fun transient404IsRetriedThenSucceeds() = runTest {
        configureToken()
        givenZones(zone("z", "s1"))
        var failedOnce = false
        api.history = { call ->
            if (!failedOnce) { failedOnce = true; throw httpError(404) }
            HiveHistoryResponse(listOf(point(call.start + 1_000)))
        }
        assertEquals(HistorySyncOutcome.COMPLETE, sync.sync())
        // Same window twice (the retry), after a 5 s backoff, and no splitting.
        val (first, second) = api.historyCalls
        assertEquals(first.start, second.start)
        assertEquals(first.end, second.end)
        assertTrue("backoff of 5 s expected, was $currentTime", currentTime >= 5_000)
        assertEquals(8 + 1, api.historyCalls.size)
        assertNull(historyDao.getSync("s1")!!.lastError)
        assertNotNull(historyDao.readings.value[("s1" to first.start + 1_000)])
    }

    @Test fun persistent404SplitsToSevenDaysThenSkips() = runTest {
        configureToken()
        givenZones(zone("z", "s1"))
        val holeStart = now - 10 * day
        val holeEnd = now - 9 * day
        api.history = { call ->
            if (call.start < holeEnd && call.end > holeStart) throw httpError(404)
            HiveHistoryResponse(listOf(point(call.start + 1_000)))
        }
        assertEquals(HistorySyncOutcome.COMPLETE, sync.sync())
        val row = historyDao.getSync("s1")!!
        assertTrue(row.lastError.orEmpty(), row.lastError!!.startsWith("skipped"))
        assertTrue(row.backfillDone)
        // Splitting stops at windows of 7 days or less: nothing narrower than 3.5 days was requested.
        val narrowest = api.historyCalls.minOf { it.end - it.start }
        assertTrue("narrowest window $narrowest", narrowest >= 3 * day)
        // Healthy windows next to the hole were still stored; the hole itself has no readings.
        assertTrue(historyDao.readings("s1", now - 60 * day, now - 20 * day).isNotEmpty())
        assertTrue(historyDao.readings("s1", holeStart, holeEnd).isEmpty())
        assertTrue(historyDao.readings("s1", now - 8 * day, now).isNotEmpty())
        // Every chunk beyond the first is still fetched: the hole did not abort the backfill.
        assertEquals(now - 450 * day, row.oldestMs)
    }

    @Test fun retentionEdgeStopsSplittingAndFinishesBackfill() = runTest {
        configureToken()
        givenZones(zone("z", "s1"))
        val retentionStart = now - 430 * day
        api.history = { call ->
            if (call.start < retentionStart) throw httpError(404)
            HiveHistoryResponse(listOf(point(call.start + 1_000)))
        }
        assertEquals(HistorySyncOutcome.COMPLETE, sync.sync())
        val row = historyDao.getSync("s1")!!
        assertTrue(row.backfillDone)
        // A window that ends beyond ~440 days is the retention edge: it is never split any further.
        val edgeCalls = api.historyCalls.filter { it.end <= now - 440 * day }
        assertTrue(edgeCalls.isNotEmpty())
        assertTrue(edgeCalls.all { it.end - it.start > 7 * day })
        assertTrue(historyDao.readings("s1", now - 430 * day, now).isNotEmpty())
        assertTrue(historyDao.readings("s1", 0, now - 430 * day).isEmpty())
    }

    @Test fun killedBackfillResumesFromOldestMs() = runTest {
        configureToken()
        givenZones(zone("z", "s1"))
        var calls = 0
        api.history = { call ->
            if (++calls == 3) throw httpError(429)
            HiveHistoryResponse(listOf(point(call.start + 1_000)))
        }
        assertEquals(HistorySyncOutcome.RETRY_LATER, sync.sync())
        val partial = historyDao.getSync("s1")!!
        assertFalse(partial.backfillDone)
        assertEquals(now - 120 * day, partial.oldestMs) // two 60-day chunks were committed
        val callsBefore = api.historyCalls.size

        answerWithWindowStart()
        assertEquals(HistorySyncOutcome.COMPLETE, sync.sync())
        val resumed = api.historyCalls.drop(callsBefore)
        // First the top-up, then the backfill continues exactly where it stopped.
        assertEquals(partial.newestMs!! - 2 * 60 * 60 * 1000, resumed.first().start)
        assertEquals(partial.oldestMs, resumed[1].end)
        assertEquals(now - 450 * day, historyDao.getSync("s1")!!.oldestMs)
        assertTrue(historyDao.getSync("s1")!!.backfillDone)
    }

    @Test fun topUpOverlapIsDeduplicated() = runTest {
        configureToken()
        givenZones(zone("z", "s1"))
        val epoch = now - 60 * 60 * 1000 // inside the 2 h overlap of the next top-up
        api.history = { HiveHistoryResponse(listOf(point(epoch, 19.0))) }
        sync.sync()
        assertEquals(1, historyDao.readings.value.size)

        now += 3 * 60 * 60 * 1000
        api.historyCalls.clear()
        api.history = { HiveHistoryResponse(listOf(point(epoch, 19.5), point(now - 1_000, 20.0))) }
        sync.sync()
        val topUp = api.historyCalls.single()
        assertEquals(now - 3 * 60 * 60 * 1000 - 2 * 60 * 60 * 1000, topUp.start)
        assertEquals(now, topUp.end)
        assertEquals(2, historyDao.readings.value.size)
        assertEquals(19.5, historyDao.readings.value[("s1" to epoch)]!!.tempC, 0.0)
    }

    @Test fun syncsEveryHistorySourceOfEveryZone() = runTest {
        configureToken()
        givenZones(zone("a", "s1", "s2"), zone("b", "s2", "s3"))
        answerWithWindowStart()
        sync.sync()
        assertEquals(setOf("s1", "s2", "s3"), api.historyCalls.map { it.id }.toSet())
        assertEquals(setOf("s1", "s2", "s3"), historyDao.sync.value.keys)
        assertTrue(api.historyCalls.all { it.timeUnit == "MINUTES" && it.rate == 5 && it.operation == "AVG" })
    }

    @Test fun unsupportedSourceIsSkippedAndOthersContinue() = runTest {
        configureToken()
        givenZones(zone("a", "bad", "good"))
        api.history = { call ->
            if (call.id == "bad") throw httpError(403, "{\"message\":\"Authorization header requires 'Credential' parameter.\"}")
            HiveHistoryResponse(listOf(point(call.start + 1_000)))
        }
        val repo = sync
        assertEquals(HistorySyncOutcome.COMPLETE, repo.sync())
        assertEquals(HistorySyncEntity.UNSUPPORTED, historyDao.getSync("bad")!!.lastError)
        assertTrue(historyDao.getSync("good")!!.backfillDone)
        assertEquals(1, api.historyCalls.count { it.id == "bad" })
        assertFalse(repo.isSyncDue())
    }

    @Test fun multiValveRoomReadsMeanPerBucket() = runTest {
        configureToken()
        givenZones(zone("a", "s1", "s2"))
        val bucket = 5 * 60 * 1000L
        val t = (now / bucket) * bucket - bucket
        historyDao.upsertReadings(listOf(
            com.gazw89.goodhive.data.db.HistoryReadingEntity("s1", t, 18.0),
            com.gazw89.goodhive.data.db.HistoryReadingEntity("s2", t + 30_000, 20.0),
        ))
        val out = sync.readings(listOf("s1", "s2"), t - 1, t + bucket).first()
        assertEquals(listOf(TemperatureReading(t, 19.0)), out)
    }

    @Test fun clearStoredHistoryKeepsStateLogClearAllRemovesIt() = runTest {
        configureToken()
        givenZones(zone("a", "s1"))
        answerWithWindowStart()
        val repo = sync
        repo.sync()
        stateDao.insertState(com.gazw89.goodhive.data.db.ZoneStateLogEntity(
            zoneId = "a", epochMs = 1, mode = "OFF", targetC = null, scheduleOverride = null,
            boostActive = false, hodActive = false, working = null, online = true, battery = null,
        ))
        repo.clearStoredHistory()
        assertTrue(historyDao.readings.value.isEmpty() && historyDao.sync.value.isEmpty())
        assertEquals(1, stateDao.states.size)
        repo.clearAllReportData()
        assertTrue(stateDao.states.isEmpty())
    }
    @Test fun clearCancelsInFlightNetworkBeforeDeletingAndDoesNotWaitForBackfill() = runTest {
        configureToken()
        givenZones(zone("a", "s1"))
        var cancelled = false
        api.history = {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val repo = sync
        val job = async { repo.sync() }
        runCurrent()
        assertEquals(1, api.historyCalls.size)
        repo.clearStoredHistory()
        assertTrue(cancelled)
        assertTrue(job.isCancelled)
        assertTrue(historyDao.readings.value.isEmpty())
        assertTrue(historyDao.sync.value.isEmpty())
        // Cancellation leaves the repository reusable, not permanently disabled.
        answerWithWindowStart()
        assertEquals(HistorySyncOutcome.COMPLETE, repo.sync())
        assertTrue(historyDao.readings.value.isNotEmpty())
    }

}
