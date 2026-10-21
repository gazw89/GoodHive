package com.gazw89.goodhive.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.auth.AuthRepository
import com.gazw89.goodhive.data.prefs.ReportPreferences
import com.gazw89.goodhive.data.prefs.ThemeMode
import com.gazw89.goodhive.data.prefs.ThemePreferences
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import com.gazw89.goodhive.data.repository.StoredHistoryStats
import com.gazw89.goodhive.work.HistorySync
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val authRepository: AuthRepository,
    private val historySyncRepository: HistorySyncRepository,
    private val historySync: HistorySync,
    private val reportPreferences: ReportPreferences,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = themePreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val dynamicColor: StateFlow<Boolean> = themePreferences.dynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val coldNightThresholdC: StateFlow<Double> = reportPreferences.coldNightThresholdC
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportPreferences.DEFAULT_COLD_NIGHT_C)

    val storedHistory: StateFlow<StoredHistoryStats?> = historySyncRepository.observeStoredStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accountEmail: String? = authRepository.savedUsername()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePreferences.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { themePreferences.setDynamicColor(enabled) }
    }

    fun setColdNightThreshold(valueC: Double) {
        viewModelScope.launch { reportPreferences.setColdNightThresholdC(valueC) }
    }

    /** Wipes stored readings and sync progress; the next sync backfills again. */
    fun clearStoredHistory() {
        viewModelScope.launch { historySyncRepository.clearStoredHistory() }
    }

    fun syncNow() {
        historySync.enqueueNow()
    }

    /**
     * Clears the tokens and everything Reports derived from the account (stored history and state
     * log). Auth owns the wipe independently of navigation and gates the next login.
     */
    fun signOut() {
        authRepository.signOut()
    }
}
