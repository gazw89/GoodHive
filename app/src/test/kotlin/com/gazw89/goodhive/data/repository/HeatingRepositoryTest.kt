package com.gazw89.goodhive.data.repository

import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.mockito.Mockito
import retrofit2.HttpException
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class HeatingRepositoryTest : RepositoryTestBase() {
    @Test fun heatOnDemandDoesNotLinkToAnUnreportedThermostat() = runTest {
        configureToken()
        repository.refresh()
        val cached = zones.getAll().single()
        zones.upsertAll(listOf(
            cached.copy(id = "old-thermostat", productType = "heating", hiveZoneId = "old-link", reportedByHive = false),
            cached.copy(hiveZoneId = "current-link"),
        ))
        assertEquals("current-link", repository.thermostatHiveZoneId())
        zones.markAllUnreported()
        assertNull(repository.thermostatHiveZoneId())
    }

    @Test fun overlappingForcedRefreshesShareOneFetch() = runTest {
        configureToken()
        val gate = CompletableDeferred<Unit>()
        api.get = { gate.await(); api.snapshot() }
        val callers = List(6) { async { repository.refresh(force = true) } }
        runCurrent()
        assertEquals(1, api.getCount)
        gate.complete(Unit)
        assertEquals(1, callers.awaitAll().count { it })
        assertEquals(1, api.getCount)
        assertEquals(1, zones.rows.value.size)
    }

    @Test fun overlappingRefreshesShareFailureInsteadOfRepeatingFailedRequest() = runTest {
        configureToken()
        val gate = CompletableDeferred<Unit>()
        api.get = { gate.await(); throw IOException("offline") }
        val callers = List(6) { async { runCatching { repository.refresh(force = true) } } }
        runCurrent(); gate.complete(Unit)
        assertTrue(callers.awaitAll().all { it.exceptionOrNull() is IOException })
        assertEquals(3, api.getCount) // one shared fetch: the first try plus two transient retries
        api.get = { api.snapshot() }
        assertTrue(repository.refresh(force = true))
    }

    @Test fun refreshRecoversFromTransientReadFailure() = runTest {
        configureToken()
        var calls = 0
        api.get = { if (++calls < 3) throw IOException("dropped") else api.snapshot() }
        assertTrue(repository.refresh(force = true))
        assertEquals(3, calls)
    }

    @Test fun confirmationCannotReuseFetchStartedBeforeItsWriteFinished() = runTest {
        configureToken()
        val gate = CompletableDeferred<Unit>()
        api.get = { gate.await(); api.snapshot() }
        val first = async { repository.refresh(force = true) }
        runCurrent()
        val second = async { repository.refresh(force = true, notBeforeEpochMillis = Long.MAX_VALUE) }
        runCurrent(); gate.complete(Unit)
        first.await(); second.await()
        assertEquals(2, api.getCount)
    }

    @Test fun recentSuccessfulFetchIsReusedByNormalRefresh() = runTest {
        configureToken()
        assertTrue(repository.refresh())
        assertFalse(repository.refresh())
        assertEquals(1, api.getCount)
    }

    @Test fun failedRefreshRetainsCacheAndDoesNotPoisonNextAttempt() = runTest {
        configureToken()
        repository.refresh()
        val saved = zones.getAll()
        api.get = { throw IOException("offline") }
        assertFailsWith<IOException> { repository.refresh(force = true) }
        assertEquals(saved, zones.getAll())
        api.get = { api.snapshot() }
        assertTrue(repository.refresh(force = true))
    }

    @Test fun explicitUnauthorisedReadRefreshesTokenOnceAndRetries() = runTest {
        configureToken()
        Mockito.`when`(auth.refreshAfterRejection("test-token")).thenReturn("new-token")
        api.get = { if (api.getCount == 1) throw http(401) else api.snapshot() }
        assertTrue(repository.refresh(force = true))
        assertEquals(2, api.getCount)
        Mockito.verify(auth).refreshAfterRejection("test-token")
    }

    @Test fun explicitUnauthorisedWriteIsRetriedOnlyOnce() = runTest {
        configureToken()
        Mockito.`when`(auth.refreshAfterRejection("test-token")).thenReturn("new-token")
        api.set = { _, _ -> throw http(401) }
        assertFailsWith<HttpException> { repository.setState("zone", "trvcontrol", buildTargetPayload(21.0)) }
        assertEquals(2, api.writes.size)
        Mockito.verify(auth).refreshAfterRejection("test-token")
    }

    @Test fun forbiddenAndRateLimitedReadsBackOffInsteadOfReauthenticating() = runTest {
        configureToken()
        api.get = { throw http(429) }
        assertFailsWith<HiveRateLimitedException> { repository.refresh(force = true) }
        assertFailsWith<HiveRateLimitedException> { repository.refresh(force = true) }
        assertEquals(1, api.getCount)
        Mockito.verify(auth, Mockito.never()).refreshAfterRejection(Mockito.anyString())
    }

    @Test fun forbiddenReadDoesNotForceTokenRefresh() = runTest {
        configureToken()
        api.get = { throw http(403) }
        assertFailsWith<HiveRateLimitedException> { repository.refresh(force = true) }
        Mockito.verify(auth, Mockito.never()).refreshAfterRejection(Mockito.anyString())
    }

    @Test fun unreportedDeviceIsRetainedUntilExplicitRemoval() = runTest {
        configureToken()
        repository.refresh()
        api.get = { com.gazw89.goodhive.data.network.HiveNodesResponse(products = emptyList()) }
        repository.refresh(force = true)
        assertEquals(1, zones.rows.value.size)
        assertFalse(zones.rows.value.single().reportedByHive)
        assertFailsWith<IllegalStateException> { repository.setState("zone", "trvcontrol", buildTargetPayload(21.0)) }
        assertTrue(repository.removeUnreportedDevice("zone"))
        assertTrue(zones.rows.value.isEmpty())
        assertTrue(api.writes.isEmpty())
    }

    @Test fun listedOfflineDeviceCannotBeRemovedAsMissing() = runTest {
        configureToken()
        api.get = {
            val snapshot = api.snapshot()
            snapshot.copy(products = snapshot.products.map { it.copy(props = kotlinx.serialization.json.buildJsonObject { put("online", kotlinx.serialization.json.JsonPrimitive(false)) }) })
        }
        repository.refresh()
        assertFalse(zones.rows.value.single().online)
        assertTrue(zones.rows.value.single().reportedByHive)
        assertFalse(repository.removeUnreportedDevice("zone"))
        assertEquals(1, zones.rows.value.size)
    }

    @Test fun failedRefreshDoesNotMakeAListedDeviceRemovable() = runTest {
        configureToken()
        repository.refresh()
        api.get = { throw IOException("offline") }
        assertFailsWith<IOException> { repository.refresh(force = true) }
        assertTrue(zones.rows.value.single().reportedByHive)
        assertFalse(repository.removeUnreportedDevice("zone"))
    }

    @Test fun deviceThatReturnsIsKeptWhenRemovalConfirmationArrivesLate() = runTest {
        configureToken()
        repository.refresh()
        api.get = { com.gazw89.goodhive.data.network.HiveNodesResponse(products = emptyList()) }
        repository.refresh(force = true)
        api.get = { api.snapshot() }
        repository.refresh(force = true)
        assertFalse(repository.removeUnreportedDevice("zone"))
        assertTrue(zones.rows.value.single().reportedByHive)
    }

    @Test fun malformedOrMissingProductsAreNotAnEmptyAuthoritativeSnapshot() = runTest {
        configureToken()
        repository.refresh()
        api.get = { json.decodeFromString(com.gazw89.goodhive.data.network.HiveNodesResponse.serializer(), "{}") }
        assertFailsWith<kotlinx.serialization.SerializationException> { repository.refresh(force = true) }
        assertTrue(zones.rows.value.single().reportedByHive)
    }

    private fun http(code: Int) = HttpException(Response.error<Unit>(code, "test error".toResponseBody()))
    @Test fun accountWipeDrainsRefreshThenDeletesItsResultsAndResetsReuse() = runTest {
        configureToken()
        val release = CompletableDeferred<Unit>()
        api.get = { release.await(); api.snapshot() }
        val read = async { repository.refresh() }
        runCurrent()
        val wipe = async { repository.clearAccountData { zones.rows.value = emptyList() } }
        runCurrent()
        assertFalse(wipe.isCompleted)
        release.complete(Unit)
        read.await()
        wipe.await()
        assertTrue(zones.rows.value.isEmpty())
        assertTrue(stateDao.states.isEmpty())
        api.get = { api.snapshot() }
        assertTrue(repository.refresh()) // Account A's success must not suppress B's first poll.
    }

}
