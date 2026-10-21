package com.gazw89.goodhive.ui.detail

import com.gazw89.goodhive.data.model.HistoryResolution
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.repository.HistoryException
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import com.gazw89.goodhive.data.repository.TemperatureHistoryRepository
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.work.HistorySync
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Feeds the room detail "Today" chart. Reads today's readings from the local history store (so the
 * chart updates live as the sync worker tops it up) and asks for a top-up when one is due. Only when
 * the store holds nothing for today, for example before the first backfill, does it fall back to the
 * on-demand Hive fetch.
 */
class TodayHistoryLoader internal constructor(
    private val storeReadings: (sourceIds: List<String>, fromMs: Long, toMs: Long) -> Flow<List<TemperatureReading>>,
    private val isSyncDue: suspend () -> Boolean,
    private val enqueueSync: () -> Unit,
    private val fetchOnDemand: suspend (sourceId: String, from: Instant, to: Instant) -> List<TemperatureReading>,
    private val startOfToday: () -> Instant,
    private val now: () -> Instant,
) {
    @Inject constructor(
        syncRepository: HistorySyncRepository,
        historySync: HistorySync,
        onDemand: TemperatureHistoryRepository,
    ) : this(
        storeReadings = syncRepository::readings,
        isSyncDue = syncRepository::isSyncDue,
        enqueueSync = historySync::enqueueNow,
        fetchOnDemand = { id, from, to -> onDemand.readings(id, from, to, HistoryResolution.FIVE_MINUTES) },
        startOfToday = { GoodHiveTime.londonStartOfDay(GoodHiveTime.londonDateNow()) },
        now = Instant::now,
    )

    /** Starts a sync job when one is due. WorkManager keeps a job that is already queued or running. */
    suspend fun topUpIfDue() {
        if (isSyncDue()) enqueueSync()
    }

    /**
     * Today's readings (London midnight onwards) for [sourceIds], merged to one series. Stays active so
     * later syncs update the chart; cancel the collector to stop.
     */
    fun observe(sourceIds: List<String>): Flow<TodayHistoryState> = flow {
        if (sourceIds.isEmpty()) {
            emit(TodayHistoryState.NoSource)
            return@flow
        }
        val from = startOfToday()
        topUpIfDue()
        var triedOnDemand = false
        storeReadings(sourceIds, from.toEpochMilli(), Long.MAX_VALUE).collect { readings ->
            if (readings.isNotEmpty()) {
                emit(TodayHistoryState.Ready(readings))
            } else if (!triedOnDemand) {
                triedOnDemand = true
                emit(onDemandState(sourceIds.first(), from))
            }
        }
    }

    private suspend fun onDemandState(sourceId: String, from: Instant): TodayHistoryState = try {
        TodayHistoryState.Ready(fetchOnDemand(sourceId, from, now()))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HistoryException) {
        TodayHistoryState.Unavailable
    } catch (_: IOException) {
        TodayHistoryState.Unavailable
    }
}
