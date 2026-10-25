package com.gazw89.goodhive.auth

import com.gazw89.goodhive.data.db.AppDatabase
import com.gazw89.goodhive.data.prefs.RoutinePreferences
import com.gazw89.goodhive.data.repository.CommandExecutor
import com.gazw89.goodhive.data.repository.HeatingRepository
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import com.gazw89.goodhive.data.repository.TemperatureHistoryRepository
import com.gazw89.goodhive.work.HistorySync
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Invoked through a provider to keep authentication independent of the repository dependency graph. */
@Singleton
class AccountDataCleaner @Inject constructor(
    private val database: AppDatabase,
    private val historySync: HistorySync,
    private val history: HistorySyncRepository,
    private val heating: HeatingRepository,
    private val commands: CommandExecutor,
    private val temperatureHistory: TemperatureHistoryRepository,
    private val routinePreferences: RoutinePreferences,
) {
    suspend fun clear() {
        historySync.cancel()
        history.clearAllReportData() // Cancels and joins the active sync before any deletion.
        commands.clearAccountData {
            heating.clearAccountData {
                temperatureHistory.clearCache()
                withContext(Dispatchers.IO) { database.clearAllTables() }
                // Routines went with the tables; the next account gets its own starter routine.
                routinePreferences.setSeeded(false)
            }
        }
    }
}
