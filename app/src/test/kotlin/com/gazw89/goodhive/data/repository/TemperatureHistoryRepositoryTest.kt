package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HistoryAggregate
import com.gazw89.goodhive.data.model.HistoryResolution
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.network.HiveHistoryPoint
import com.gazw89.goodhive.data.network.HiveHistoryResponse
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.mockito.Mockito
import retrofit2.HttpException
import retrofit2.Response

class TemperatureHistoryRepositoryTest : RepositoryTestBase() {
    private var now = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
    private val history get() = TemperatureHistoryRepository(api, HiveRequestRunner(auth)) { now }
    private val from = Instant.parse("2026-06-01T00:00:00Z")
    private val res = HistoryResolution.HOURLY

    private fun httpError(code: Int, body: String = "") =
        HttpException(Response.error<Any>(code, body.toResponseBody()))

    private fun point(date: String?, t: Double?) = HiveHistoryPoint(date = date, temperature = t)

    @org.junit.Test fun longRangeIsChunkedContiguously() = runTest {
        configureToken()
        val repo = history
        repo.readings("a", from, from.plus(Duration.ofDays(120)), res)
        assertEquals(2, api.historyCalls.size)
        val (c1, c2) = api.historyCalls
        assertEquals(from.toEpochMilli(), c1.start)
        assertEquals(Duration.ofDays(60).toMillis(), c1.end - c1.start)
        assertEquals(c1.end, c2.start)
        assertEquals(from.plus(Duration.ofDays(120)).toEpochMilli(), c2.end)
    }

    @org.junit.Test fun dedupesSortsAndParses() = runTest {
        configureToken()
        api.history = { HiveHistoryResponse(listOf(point("2000", 20.0), point("1000", 19.0), point("2000", 21.0))) }
        val out = history.readings("a", from, from.plusSeconds(3600), res)
        assertEquals(listOf(TemperatureReading(1000, 19.0), TemperatureReading(2000, 21.0)), out)
    }

    @org.junit.Test fun dropsNullTemperatureAndBadDate() = runTest {
        configureToken()
        api.history = { HiveHistoryResponse(listOf(point("1000", null), point("abc", 5.0), point(null, 5.0), point("3000", 18.5))) }
        val out = history.readings("a", from, from.plusSeconds(3600), res)
        assertEquals(listOf(TemperatureReading(3000, 18.5)), out)
    }

    @org.junit.Test fun cacheHitSkipsNetwork() = runTest {
        configureToken()
        val repo = history
        repo.readings("a", from, from.plusSeconds(3600), res)
        repo.readings("a", from, from.plusSeconds(3600), res)
        assertEquals(1, api.historyCalls.size)
        repo.readings("a", from, from.plusSeconds(3600), res, HistoryAggregate.MAX)
        assertEquals(2, api.historyCalls.size)
    }

    @org.junit.Test fun todayExpiresPastDoesNot() = runTest {
        configureToken()
        val repo = history
        val today = Instant.ofEpochMilli(now)
        val past = Instant.parse("2026-10-10T00:00:00Z")
        repo.readings("a", past, past.plusSeconds(3600), res)
        repo.readings("a", past, today, res)
        assertEquals(2, api.historyCalls.size)
        now += 4 * 60_000
        repo.readings("a", past, past.plusSeconds(3600), res)
        repo.readings("a", past, today, res)
        assertEquals(2, api.historyCalls.size)
        now += 2 * 60_000
        repo.readings("a", past, past.plusSeconds(3600), res)
        repo.readings("a", past, today, res)
        assertEquals(3, api.historyCalls.size)
    }

    private suspend fun assertMaps(code: Int, body: String, check: (Throwable) -> Boolean) {
        api.history = { throw httpError(code, body) }
        try {
            history.readings("a", from, from.plusSeconds(3600), res)
            fail("expected exception")
        } catch (e: HistoryException) {
            assertTrue(e.toString(), check(e))
        }
    }

    @org.junit.Test fun status404IsOutOfRange() = runTest {
        configureToken()
        assertMaps(404, "") { it is HistoryException.OutOfRange }
    }

    @org.junit.Test fun transient404InOneChunkIsRetriedNotFatal() = runTest {
        configureToken()
        var failures = 0
        api.history = { call ->
            // The second 60-day chunk fails twice, then succeeds.
            if (call.start > from.toEpochMilli() && failures++ < 2) throw httpError(404)
            HiveHistoryResponse(listOf(point((call.start + 1000).toString(), 19.0)))
        }
        val out = history.readings("a", from, from.plus(Duration.ofDays(120)), res)
        assertEquals(2, out.size)
        assertEquals(4, api.historyCalls.size)
    }

    @org.junit.Test fun sigV4403IsUnsupportedSource() = runTest {
        configureToken()
        assertMaps(403, "{\"message\":\"Authorization header requires 'Credential' parameter.\"}") { it is HistoryException.UnsupportedSource }
    }

    @org.junit.Test fun other403And429AreRateLimited() = runTest {
        configureToken()
        assertMaps(403, "{\"message\":\"Forbidden\"}") { it is HistoryException.RateLimited }
        assertMaps(429, "") { it is HistoryException.RateLimited }
    }

    @org.junit.Test fun status401RefreshesAndRetriesOnce() = runTest {
        configureToken()
        Mockito.`when`(auth.refreshAfterRejection("test-token")).thenReturn("fresh-token")
        api.history = { call ->
            if (call.token == "test-token") throw httpError(401) else HiveHistoryResponse(listOf(point("1000", 19.0)))
        }
        val out = history.readings("a", from, from.plusSeconds(3600), res)
        assertEquals(1, out.size)
        assertEquals(listOf("test-token", "fresh-token"), api.historyCalls.map { it.token })
    }
}
