package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HistoryAggregate
import com.gazw89.goodhive.data.model.HistoryResolution
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.network.HiveApiService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * The one low-level "fetch a single history window" function, shared by the on-demand reads
 * ([TemperatureHistoryRepository]) and the background store ([HistorySyncRepository]).
 *
 * Every request holds [mutex] while it is in flight, so history traffic is serialized across both
 * callers (and never starves a user write, which doesn't use this path). Callers add their own gap
 * between windows. Never touches HeatingRepository's nodes/all backoff.
 */
@Singleton
class HistoryFetcher internal constructor(
    private val api: HiveApiService,
    private val requestRunner: HiveRequestRunner,
    private val retryDelaysMs: List<Long>,
) {
    @Inject constructor(api: HiveApiService, requestRunner: HiveRequestRunner) :
        this(api, requestRunner, DEFAULT_RETRY_DELAYS_MS)

    private val mutex = Mutex()

    /**
     * One request for [startMs, endMs] with no retry. Points with a null temperature or an
     * unparsable date are dropped; the rest are de-duplicated and sorted.
     *
     * @throws HistoryException for 404 / 403 / 429; other failures propagate unchanged.
     */
    suspend fun fetchOnce(
        sourceId: String,
        startMs: Long,
        endMs: Long,
        resolution: HistoryResolution,
        aggregate: HistoryAggregate,
    ): List<TemperatureReading> {
        val response = mutex.withLock {
            try {
                requestRunner.call { token ->
                    api.getTemperatureHistory(
                        token = token, id = sourceId, start = startMs, end = endMs,
                        timeUnit = resolution.timeUnit, rate = resolution.rate,
                        operation = aggregate.wire,
                    )
                }
            } catch (err: HttpException) {
                throw map(err)
            }
        }
        val all = HashMap<Long, TemperatureReading>()
        for (point in response.data) {
            val temperature = point.temperature ?: continue
            val epoch = point.date?.trim()?.toLongOrNull() ?: continue
            all[epoch] = TemperatureReading(epoch, temperature)
        }
        return all.values.sortedBy { it.epochMillis }
    }

    /**
     * [fetchOnce], retrying a 404 after each of [retryDelaysMs] (5 s, 30 s by default). Hive returns
     * transient 404 NOT_FOUND for valid windows (live 2026-10-15; a repeat minutes later was 200).
     * The mutex is released while waiting. A 404 that outlasts the retries is thrown as
     * [HistoryException.OutOfRange].
     */
    suspend fun fetchWithRetry(
        sourceId: String,
        startMs: Long,
        endMs: Long,
        resolution: HistoryResolution,
        aggregate: HistoryAggregate,
    ): List<TemperatureReading> {
        var attempt = 0
        while (true) {
            try {
                return fetchOnce(sourceId, startMs, endMs, resolution, aggregate)
            } catch (err: HistoryException.OutOfRange) {
                if (attempt >= retryDelaysMs.size) throw err
                delay(retryDelaysMs[attempt++])
            }
        }
    }

    private fun map(err: HttpException): Exception = when (err.code()) {
        404 -> HistoryException.OutOfRange(err)
        403 -> {
            val body = try { err.response()?.errorBody()?.string().orEmpty() } catch (_: Exception) { "" }
            if (body.contains("Authorization header requires 'Credential'")) {
                HistoryException.UnsupportedSource(err)
            } else {
                HistoryException.RateLimited(err)
            }
        }
        429 -> HistoryException.RateLimited(err)
        else -> err
    }

    companion object {
        val DEFAULT_RETRY_DELAYS_MS = listOf(5_000L, 30_000L)
    }
}
