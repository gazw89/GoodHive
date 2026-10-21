package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HistoryAggregate
import com.gazw89.goodhive.data.model.HistoryResolution
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.network.HiveApiService
import com.gazw89.goodhive.util.GoodHiveTime
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a temperature-history request failed. Other failures propagate unchanged. */
sealed class HistoryException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** HTTP 404 that outlasted the retries: beyond Hive's ~15-month retention, or the range is too wide. */
    class OutOfRange(cause: Throwable? = null) : HistoryException("History range is outside what Hive keeps", cause)

    /** API Gateway's reply for an unknown route (not an auth or rate-limit problem). */
    class UnsupportedSource(cause: Throwable? = null) : HistoryException("Hive has no history for this device", cause)

    /** HTTP 429 or a plain 403. */
    class RateLimited(cause: Throwable? = null) : HistoryException("Hive is rate-limiting history requests", cause)
}

/**
 * Reads temperature history from Hive on demand. Results live only in a small in-memory LRU cache
 * (the persistent store that reports use is [HistorySyncRepository]). Long ranges are chunked to stay
 * inside what the endpoint accepts, and each chunk goes through the shared [HistoryFetcher], which
 * serializes requests and retries transient 404s. Never touches HeatingRepository's nodes/all backoff.
 */
@Singleton
class TemperatureHistoryRepository internal constructor(
    private val fetcher: HistoryFetcher,
    private val clock: () -> Long,
) {
    @Inject constructor(fetcher: HistoryFetcher) : this(fetcher, System::currentTimeMillis)

    internal constructor(api: HiveApiService, requestRunner: HiveRequestRunner, clock: () -> Long) :
        this(HistoryFetcher(api, requestRunner), clock)

    internal constructor(api: HiveApiService, requestRunner: HiveRequestRunner) :
        this(api, requestRunner, System::currentTimeMillis)

    private data class Key(
        val sourceId: String, val resolution: HistoryResolution, val aggregate: HistoryAggregate,
        val fromMs: Long, val toMs: Long,
    )

    /** [expiresAtMs] null means the entry never expires. */
    private class Entry(val readings: List<TemperatureReading>, val expiresAtMs: Long?)

    /** Stops identical concurrent reads fetching twice. The network itself is serialized by [HistoryFetcher]. */
    private val readMutex = Mutex()
    private val cache = object : LinkedHashMap<Key, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?) = size > MAX_CACHE_ENTRIES
    }

    private fun cached(key: Key): List<TemperatureReading>? = synchronized(cache) {
        val entry = cache[key] ?: return null
        val expiry = entry.expiresAtMs
        if (expiry != null && clock() >= expiry) {
            cache.remove(key)
            null
        } else {
            entry.readings
        }
    }

    suspend fun readings(
        sourceId: String,
        from: Instant,
        to: Instant,
        resolution: HistoryResolution,
        aggregate: HistoryAggregate = HistoryAggregate.AVG,
    ): List<TemperatureReading> {
        val key = Key(sourceId, resolution, aggregate, from.toEpochMilli(), to.toEpochMilli())
        cached(key)?.let { return it }
        return readMutex.withLock {
            cached(key)?.let { return@withLock it }
            val result = fetch(key)
            val now = clock()
            val startOfToday = GoodHiveTime.londonStartOfDay(
                Instant.ofEpochMilli(now).atZone(GoodHiveTime.londonZone).toLocalDate(),
            ).toEpochMilli()
            val expiry = if (key.toMs < startOfToday) null else now + TODAY_TTL_MS
            synchronized(cache) { cache[key] = Entry(result, expiry) }
            result
        }
    }

    /** Drain a pending on-demand read before deleting its account's process-local cache. */
    suspend fun clearCache() = readMutex.withLock {
        synchronized(cache) { cache.clear() }
    }

    private suspend fun fetch(key: Key): List<TemperatureReading> {
        val all = HashMap<Long, TemperatureReading>()
        var start = key.fromMs
        var first = true
        while (start < key.toMs || first) {
            if (!first) delay(CHUNK_GAP_MS)
            first = false
            val end = minOf(key.toMs, start + MAX_CHUNK_MS)
            for (reading in fetcher.fetchWithRetry(key.sourceId, start, end, key.resolution, key.aggregate)) {
                all[reading.epochMillis] = reading
            }
            start = end
        }
        return all.values.sortedBy { it.epochMillis }
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 64
        const val MAX_CHUNK_MS = HISTORY_MAX_CHUNK_MS
        const val CHUNK_GAP_MS = HISTORY_CHUNK_GAP_MS
        const val TODAY_TTL_MS = 5L * 60 * 1000
    }
}

/** Longest window the history endpoint accepts (75 days worked live, 90 returned 404). */
internal const val HISTORY_MAX_CHUNK_MS = 60L * 24 * 60 * 60 * 1000

/** Pause between consecutive history requests, to stay polite to the unofficial API. */
internal const val HISTORY_CHUNK_GAP_MS = 1_000L
