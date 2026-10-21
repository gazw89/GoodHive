package com.gazw89.goodhive.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import com.gazw89.goodhive.work.HistorySync
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Shell-only local snapshot. enqueue=true delegates to the normal durable WorkManager job;
 * this receiver never fetches history itself and never logs source IDs or exception contents.
 * A snapshot immediately after enqueue is not a claim that the worker has already finished.
 */
class ReportsSyncProbeReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReportsSyncProbeEntryPoint {
        fun historySyncRepository(): HistorySyncRepository
        fun historySync(): HistorySync
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val enqueue = intent.getBooleanExtra("enqueue", false)
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        appContext, ReportsSyncProbeEntryPoint::class.java,
                    )
                    if (enqueue) entryPoint.historySync().enqueueNow()
                    val repository = entryPoint.historySyncRepository()
                    val stats = repository.observeStoredStats().first()
                    val progress = repository.observeProgress().first()
                    Log.i(TAG, "count=${stats.rowCount} completed=${progress.count { it.backfillDone }} " +
                        "total=${progress.size} oldestMs=${progress.mapNotNull { it.oldestMs }.minOrNull()} " +
                        "newestMs=${progress.mapNotNull { it.newestMs }.maxOrNull()} " +
                        "errors=${progress.count { it.lastError != null }}")
                }
            } catch (_: Exception) {
                // Exception messages and stack traces can contain IDs, URLs or database values.
                Log.w(TAG, "Aggregate local history snapshot unavailable")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION = "com.gazw89.goodhive.PROBE_REPORT_SYNC"
        const val TAG = "ReportsSyncProbe"
    }
}
