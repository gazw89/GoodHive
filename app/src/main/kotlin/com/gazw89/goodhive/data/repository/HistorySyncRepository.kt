package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.db.HeatingZoneCacheDao
import com.gazw89.goodhive.data.db.HistoryDao
import com.gazw89.goodhive.data.db.HistoryReadingEntity
import com.gazw89.goodhive.data.db.HistorySyncEntity
import com.gazw89.goodhive.data.model.HistoryAggregate
import com.gazw89.goodhive.data.model.HistoryResolution
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.mergeSourceReadings
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** How a [HistorySyncRepository.sync] run ended. Network failures other than these propagate. */
enum class HistorySyncOutcome {
    /** Every source is up to date, backfilled, skipped as unsupported, or finished with recorded gaps. */
    COMPLETE,

    /** Hive rate-limited (403/429). Progress is saved; ask WorkManager to back off and retry. */
    RETRY_LATER,
}

/** What the Settings "Stored history" row shows. */
data class StoredHistoryStats(
    val rowCount: Int,
    /** Newest sync attempt across all sources, epoch ms; null when nothing has synced yet. */
    val lastSyncMs: Long?,
) {
    /**
     * Estimated reading storage, not the database file size. Room uses a rowid table with UUID text
     * source ids and a composite-primary-key index. Budget 120 bytes/read including that index;
     * file allocation, WAL and other tables vary independently.
     */
    val approxBytes: Long get() = rowCount.toLong() * APPROX_BYTES_PER_ROW

    companion object {
        const val APPROX_BYTES_PER_ROW = 120L
    }
}

/**
 * Keeps raw 5-minute readings (AVG) of every history source in Room, for reports.
 *
 * - **Backfill**, per source, newest-first in 60-day chunks back to now − 450 days. Each chunk and its
 *   `history_sync` bounds commit in one transaction, so a killed job resumes at `oldestMs`.
 * - **Top-up**: `newestMs − 2 h → now`. Overlap is harmless: `(sourceId, epochMs)` upserts.
 * - **Errors** (live 2026-10-15: Hive returns transient 404 for valid windows). A 404 is retried by
 *   [HistoryFetcher] (5 s, 30 s), then the window is halved recursively down to 7 days. A 7-day
 *   window that still 404s is skipped and noted in `lastError`. A 404 for a window ending more than
 *   ~440 days ago is the retention edge: backfill for that source is finished. An API-Gateway 403 marks
 *   the source unsupported. Other 403 / 429 stop the run with [HistorySyncOutcome.RETRY_LATER].
 *
 * Must run inside a WorkManager job: Android blocks the app's network once the phone is locked and
 * the app has no running job. Requests share [HistoryFetcher]'s serialization with the on-demand reads.
 */
@Singleton
class HistorySyncRepository internal constructor(
    private val fetcher: HistoryFetcher,
    private val historyDao: HistoryDao,
    private val zoneDao: HeatingZoneCacheDao,
    private val stateLogger: ZoneStateLogger,
    private val json: Json,
    private val clock: () -> Long,
) {
    @Inject constructor(
        fetcher: HistoryFetcher,
        historyDao: HistoryDao,
        zoneDao: HeatingZoneCacheDao,
        stateLogger: ZoneStateLogger,
        json: Json,
    ) : this(fetcher, historyDao, zoneDao, stateLogger, json, System::currentTimeMillis)

    private val syncMutex = Mutex()
    private var activeSync: Deferred<HistorySyncOutcome>? = null

    /** What one fetched window produced. */
    private class WindowResult(
        val readings: List<TemperatureReading> = emptyList(),
        val skipped: List<LongRange> = emptyList(),
        val hitRetentionEdge: Boolean = false,
    ) {
        operator fun plus(other: WindowResult) = WindowResult(
            readings + other.readings, skipped + other.skipped, hitRetentionEdge || other.hitRetentionEdge,
        )
    }

    /** Every history source id of every zone Hive still reports, from the Room zone cache. */
    suspend fun sourceIds(): List<String> =
        zoneDao.getAll().filter { it.reportedByHive }
            .flatMap { it.toDomain(json).historySourceIds }
            .distinct()

    /** Per-source progress, for the Reports screen. */
    fun observeProgress(): Flow<List<HistorySyncEntity>> = historyDao.observeAllSync()

    /** Row count and last sync time, for Settings. */
    fun observeStoredStats(): Flow<StoredHistoryStats> =
        combine(historyDao.observeReadingCount(), historyDao.observeAllSync()) { count, rows ->
            StoredHistoryStats(count, rows.mapNotNull { it.lastAttemptMs }.maxOrNull())
        }

    /**
     * True when the Reports data needs a sync job: a source never synced, a backfill still open, or
     * the latest attempt for a source older than [TOP_UP_INTERVAL_MS]. Unsupported sources never count.
     */
    suspend fun isSyncDue(): Boolean {
        val ids = sourceIds()
        if (ids.isEmpty()) return false
        val rows = historyDao.getAllSync().associateBy { it.sourceId }
        val staleBefore = clock() - TOP_UP_INTERVAL_MS
        return ids.any { id ->
            val row = rows[id] ?: return@any true
            row.lastError != HistorySyncEntity.UNSUPPORTED &&
                (!row.backfillDone || (row.lastAttemptMs ?: 0L) < staleBefore)
        }
    }

    /**
     * Raw readings for a room, `fromMs` inclusive to `toMs` exclusive, re-emitted as the store grows.
     * Several sources (a multi-valve room) are merged to the mean per 5-minute bucket.
     */
    fun readings(sourceIds: List<String>, fromMs: Long, toMs: Long): Flow<List<TemperatureReading>> {
        if (sourceIds.isEmpty()) return flowOf(emptyList())
        return historyDao.observeReadings(sourceIds, fromMs, toMs).map { rows ->
            val bySource = rows.groupBy { it.sourceId }
            mergeSourceReadings(sourceIds.distinct().map { id ->
                bySource[id].orEmpty().map { TemperatureReading(it.epochMs, it.tempC) }
            })
        }
    }

    /** Deletes the stored readings and sync bookkeeping. The state log is kept. */
    suspend fun clearStoredHistory() = syncMutex.withLock {
        activeSync?.cancelAndJoin()
        activeSync = null
        historyDao.clearHistory()
    }

    /** Sign-out: everything Reports derived from the account, including our own state log. */
    suspend fun clearAllReportData() = syncMutex.withLock {
        activeSync?.cancelAndJoin()
        activeSync = null
        historyDao.clearHistory()
        stateLogger.clear()
    }

    /**
     * Tops up every source, then backfills each one. The first pass gives every source its latest
     * 60 days (so Week/Month are usable early); the second walks back through history.
     */
    suspend fun sync(): HistorySyncOutcome = coroutineScope {
        val work = async(start = CoroutineStart.LAZY) { performSync() }
        syncMutex.withLock {
            activeSync?.cancelAndJoin()
            activeSync = work
            work.start()
        }
        work.await()
    }

    private suspend fun performSync(): HistorySyncOutcome {
        return try {
            val ids = sourceIds()
            var first = true
            for (id in ids) {
                if (!first) delay(HISTORY_CHUNK_GAP_MS)
                first = false
                guardingUnsupported(id) { syncLatest(id) }
            }
            for (id in ids) guardingUnsupported(id) { backfill(id) }
            HistorySyncOutcome.COMPLETE
        } catch (limited: HistoryException.RateLimited) {
            HistorySyncOutcome.RETRY_LATER
        }
    }

    private suspend fun guardingUnsupported(sourceId: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (unsupported: HistoryException.UnsupportedSource) {
            val row = historyDao.getSync(sourceId) ?: HistorySyncEntity(sourceId)
            historyDao.upsertSync(
                row.copy(backfillDone = true, lastAttemptMs = clock(), lastError = HistorySyncEntity.UNSUPPORTED),
            )
        }
    }

    /** First run for a source: its latest chunk. Afterwards: forward from `newestMs − 2 h` to now. */
    private suspend fun syncLatest(sourceId: String) {
        val row = historyDao.getSync(sourceId) ?: HistorySyncEntity(sourceId)
        if (row.lastError == HistorySyncEntity.UNSUPPORTED) return
        val now = clock()
        val newest = row.newestMs
        if (newest == null || row.oldestMs == null) {
            val start = now - HISTORY_MAX_CHUNK_MS
            val result = fetchWindow(sourceId, start, now)
            commit(
                row.copy(
                    oldestMs = start, newestMs = coveredNewest(result, now, null),
                    backfillDone = row.backfillDone || result.hitRetentionEdge || start <= backfillFloor(now),
                ),
                result, now,
            )
            return
        }
        var current = row
        var start = newest - TOP_UP_OVERLAP_MS
        var firstWindow = true
        while (start < now) {
            if (!firstWindow) delay(HISTORY_CHUNK_GAP_MS)
            firstWindow = false
            val end = minOf(now, start + HISTORY_MAX_CHUNK_MS)
            val result = fetchWindow(sourceId, start, end)
            current = current.copy(newestMs = coveredNewest(result, end, current.newestMs))
            commit(current, result, now)
            start = end
        }
    }

    private suspend fun backfill(sourceId: String) {
        var row = historyDao.getSync(sourceId) ?: return
        if (row.lastError == HistorySyncEntity.UNSUPPORTED) return
        var firstWindow = true
        while (!row.backfillDone) {
            val end = row.oldestMs ?: return
            val floor = backfillFloor(clock())
            if (end <= floor) {
                row = row.copy(backfillDone = true).also { historyDao.upsertSync(it) }
                break
            }
            if (!firstWindow) delay(HISTORY_CHUNK_GAP_MS)
            firstWindow = false
            val start = maxOf(floor, end - HISTORY_MAX_CHUNK_MS)
            val result = fetchWindow(sourceId, start, end)
            row = row.copy(
                oldestMs = start,
                backfillDone = result.hitRetentionEdge || start <= floor,
            )
            commit(row, result, clock())
        }
    }

    /**
     * The newest time the store now fully covers: the window end, unless part of it was skipped, in
     * which case only the newest reading we actually have (never past [previous]'s coverage claim).
     */
    private fun coveredNewest(result: WindowResult, windowEnd: Long, previous: Long?): Long {
        if (result.skipped.isEmpty()) return maxOf(windowEnd, previous ?: windowEnd)
        val newestReading = result.readings.maxOfOrNull { it.epochMillis }
        return maxOf(newestReading ?: Long.MIN_VALUE, previous ?: Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            ?: windowEnd
    }

    private suspend fun commit(row: HistorySyncEntity, result: WindowResult, now: Long) {
        val error = if (result.skipped.isEmpty()) row.lastError else appendSkipped(row.lastError, result.skipped)
        historyDao.commitChunk(
            result.readings.map { HistoryReadingEntity(row.sourceId, it.epochMillis, it.temperatureC) },
            row.copy(lastAttemptMs = now, lastError = error),
        )
    }

    private fun appendSkipped(existing: String?, skipped: List<LongRange>): String {
        val notes = skipped.joinToString("; ") { "${isoDay(it.first)}..${isoDay(it.last)} 404" }
        val combined = if (existing.isNullOrEmpty()) "skipped $notes" else "$existing; $notes"
        return if (combined.length <= MAX_ERROR_LENGTH) combined else "…" + combined.takeLast(MAX_ERROR_LENGTH - 1)
    }

    private fun isoDay(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun backfillFloor(now: Long) = now - BACKFILL_DAYS * DAY_MS

    /**
     * Fetches [startMs, endMs], retrying and splitting on 404 (see class docs). [retry] is false for
     * the middle levels of a split, so a persistent hole costs one retry pass per 7-day leaf rather
     * than one per level.
     */
    private suspend fun fetchWindow(sourceId: String, startMs: Long, endMs: Long, retry: Boolean = true): WindowResult {
        try {
            val readings = if (retry) {
                fetcher.fetchWithRetry(sourceId, startMs, endMs, SYNC_RESOLUTION, SYNC_AGGREGATE)
            } else {
                fetcher.fetchOnce(sourceId, startMs, endMs, SYNC_RESOLUTION, SYNC_AGGREGATE)
            }
            return WindowResult(readings)
        } catch (outOfRange: HistoryException.OutOfRange) {
            if (endMs <= clock() - RETENTION_EDGE_DAYS * DAY_MS) return WindowResult(hitRetentionEdge = true)
            val length = endMs - startMs
            if (length <= MIN_SPLIT_MS) return WindowResult(skipped = listOf(startMs..endMs))
            val mid = startMs + length / 2
            val halfIsLeaf = length / 2 <= MIN_SPLIT_MS
            delay(HISTORY_CHUNK_GAP_MS)
            val newer = fetchWindow(sourceId, mid, endMs, retry = halfIsLeaf)
            delay(HISTORY_CHUNK_GAP_MS)
            val older = fetchWindow(sourceId, startMs, mid, retry = halfIsLeaf)
            return older + newer
        }
    }

    companion object {
        private val SYNC_RESOLUTION = HistoryResolution.FIVE_MINUTES
        private val SYNC_AGGREGATE = HistoryAggregate.AVG
        private const val DAY_MS = 24L * 60 * 60 * 1000
        /** Backfill target: how far back we try to reach. */
        const val BACKFILL_DAYS = 450L
        /** A 404 for a window ending before this many days ago is Hive's retention edge, not a hole. */
        const val RETENTION_EDGE_DAYS = 440L
        const val TOP_UP_OVERLAP_MS = 2L * 60 * 60 * 1000
        const val TOP_UP_INTERVAL_MS = 60L * 60 * 1000
        private const val MIN_SPLIT_MS = 7L * DAY_MS
        private const val MAX_ERROR_LENGTH = 480
    }
}
