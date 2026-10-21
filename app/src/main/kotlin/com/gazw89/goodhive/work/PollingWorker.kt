package com.gazw89.goodhive.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gazw89.goodhive.auth.HiveAuthException
import com.gazw89.goodhive.data.repository.CommandExecutor
import com.gazw89.goodhive.data.repository.HeatingRepository
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Background poll + in-flight command resumption, run periodically by WorkManager
 * (conservative interval — see the plan's rate-limiting note) and once at app start.
 * This is the durability half of the command reliability layer: if the app was killed
 * mid-command, this is what picks the pending command back up.
 *
 * Never signs the user out from here — session UX belongs to the foreground UI. On a
 * definite Cognito rejection we stop retrying so we don't hammer Cognito with a dead token.
 */
@HiltWorker
class PollingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val heatingRepository: HeatingRepository,
    private val commandExecutor: CommandExecutor,
    private val historySyncRepository: HistorySyncRepository,
    private val historySync: HistorySync,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            heatingRepository.refresh()
            commandExecutor.resumeInFlightCommands()
            enqueueHistorySyncIfDue()
            Result.success()
        } catch (e: HiveAuthException) {
            if (e.isDefiniteSessionRejection) {
                Log.w(
                    "GoodHiveAuth",
                    "Background poll hit definite session rejection (${e.cognitoErrorType}) — stopping retries",
                )
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.retry()
        }
    }

    /** Top-up (or first backfill) of the history store when the last one is over an hour old. */
    private suspend fun enqueueHistorySyncIfDue() {
        try {
            if (historySyncRepository.isSyncDue()) historySync.enqueueNow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("GoodHiveSync", "Could not check history sync: ${e.message}")
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "goodhive_background_poll"
    }
}
