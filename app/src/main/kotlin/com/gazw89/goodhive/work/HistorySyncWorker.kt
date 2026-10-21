package com.gazw89.goodhive.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gazw89.goodhive.auth.HiveAuthException
import com.gazw89.goodhive.data.repository.HistorySyncOutcome
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/**
 * Backfills and tops up the local temperature-history store. All history network traffic must run
 * here (or while the app is visible): with the phone locked, Android destroys the app's sockets
 * unless a job is running (live finding 2026-10-15). Progress is committed per chunk, so being
 * stopped by the system only costs the chunk in flight.
 */
@HiltWorker
class HistorySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val historySyncRepository: HistorySyncRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            when (historySyncRepository.sync()) {
                HistorySyncOutcome.COMPLETE -> Result.success()
                HistorySyncOutcome.RETRY_LATER -> retryOrStop()
            }
        } catch (e: HiveAuthException) {
            if (e.isDefiniteSessionRejection) {
                Log.w("GoodHiveAuth", "History sync hit definite session rejection (${e.cognitoErrorType}) — stopping")
                Result.failure()
            } else {
                retryOrStop()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GoodHiveSync", "History sync failed: ${e.message}")
            retryOrStop()
        }
    }

    private fun retryOrStop(): Result = if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()

    companion object {
        private const val MAX_RETRIES = 3
        const val UNIQUE_WORK_NAME = "goodhive_history_sync"
    }
}

/** Entry point for starting (or stopping) the history sync job. */
@Singleton
class HistorySync @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Enqueues a sync now. A sync that is already queued or running is kept (`KEEP`). */
    fun enqueueNow() {
        val request = OneTimeWorkRequestBuilder<HistorySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            HistorySyncWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(HistorySyncWorker.UNIQUE_WORK_NAME)
    }
}
