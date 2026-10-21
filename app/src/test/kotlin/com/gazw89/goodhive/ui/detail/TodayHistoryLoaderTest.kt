package com.gazw89.goodhive.ui.detail

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.repository.HistoryException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** A loader whose store is empty and whose sync never fires; for ViewModel tests that ignore history. */
internal fun noopTodayHistoryLoader() = TodayHistoryLoader(
    storeReadings = { _, _, _ -> flowOf(emptyList()) },
    isSyncDue = { false },
    enqueueSync = {},
    fetchOnDemand = { _, _, _ -> emptyList() },
    startOfToday = Instant::now,
    now = Instant::now,
)

class TodayHistoryLoaderTest {
    private val midnight = Instant.parse("2026-10-14T23:00:00Z")
    private val store = MutableStateFlow<List<TemperatureReading>>(emptyList())
    private var syncDue = false
    private var syncs = 0
    private var onDemandCalls = 0
    private var onDemand: suspend () -> List<TemperatureReading> = { emptyList() }
    private var requestedFromMs = -1L
    private var requestedIds: List<String> = emptyList()

    private fun loader() = TodayHistoryLoader(
        storeReadings = { ids, from, _ ->
            requestedIds = ids
            requestedFromMs = from
            store as Flow<List<TemperatureReading>>
        },
        isSyncDue = { syncDue },
        enqueueSync = { syncs++ },
        fetchOnDemand = { _, _, _ -> onDemandCalls++; onDemand() },
        startOfToday = { midnight },
        now = { midnight.plusSeconds(3600) },
    )

    private fun reading(minute: Int) = TemperatureReading(midnight.toEpochMilli() + minute * 60_000L, 19.0)

    @Test fun noSourcesEmitsNoSource() = runTest {
        assertEquals(TodayHistoryState.NoSource, loader().observe(emptyList()).first())
    }

    @Test fun storedReadingsAreUsedWithoutAnOnDemandFetch() = runTest {
        store.value = listOf(reading(5), reading(10))
        val state = loader().observe(listOf("a", "b")).first()
        assertEquals(TodayHistoryState.Ready(store.value), state)
        assertEquals(0, onDemandCalls)
        assertEquals(listOf("a", "b"), requestedIds)
        assertEquals(midnight.toEpochMilli(), requestedFromMs)
    }

    @Test fun emptyStoreFallsBackToOnDemand() = runTest {
        onDemand = { listOf(reading(1)) }
        val state = loader().observe(listOf("a")).first()
        assertEquals(TodayHistoryState.Ready(listOf(reading(1))), state)
        assertEquals(1, onDemandCalls)
    }

    @Test fun failedFallbackIsUnavailable() = runTest {
        onDemand = { throw HistoryException.OutOfRange() }
        assertEquals(TodayHistoryState.Unavailable, loader().observe(listOf("a")).first())
    }

    @Test fun laterSyncReplacesFallbackWithStoredReadings() = runTest {
        onDemand = { listOf(reading(1)) }
        val states = mutableListOf<TodayHistoryState>()
        val loader = loader()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            loader.observe(listOf("a")).collect { states += it }
        }
        store.value = listOf(reading(5), reading(10))
        job.cancel()
        assertEquals(
            listOf(TodayHistoryState.Ready(listOf(reading(1))), TodayHistoryState.Ready(store.value)),
            states,
        )
        assertEquals(1, onDemandCalls)
    }

    @Test fun topUpOnlyWhenDue() = runTest {
        val loader = loader()
        loader.topUpIfDue()
        assertEquals(0, syncs)
        syncDue = true
        loader.topUpIfDue()
        assertEquals(1, syncs)
    }

    @Test fun observingRequestsTopUpWhenDue() = runTest {
        store.value = listOf(reading(5))
        loader().observe(listOf("a")).first()
        assertEquals(0, syncs)
        syncDue = true
        loader().observe(listOf("a")).first()
        assertEquals(1, syncs)
    }
}
