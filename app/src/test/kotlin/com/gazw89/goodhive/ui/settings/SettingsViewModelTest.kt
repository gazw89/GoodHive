package com.gazw89.goodhive.ui.settings

import com.gazw89.goodhive.auth.AuthRepository
import com.gazw89.goodhive.data.prefs.ReportPreferences
import com.gazw89.goodhive.data.prefs.ThemeMode
import com.gazw89.goodhive.data.prefs.ThemePreferences
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import com.gazw89.goodhive.data.repository.StoredHistoryStats
import com.gazw89.goodhive.work.HistorySync
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val themePrefs = mock(ThemePreferences::class.java)
    private val auth = mock(AuthRepository::class.java)
    private val historyRepo = mock(HistorySyncRepository::class.java)
    private val historySync = mock(HistorySync::class.java)
    private val reportPrefs = mock(ReportPreferences::class.java)

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        `when`(themePrefs.themeMode).thenReturn(flowOf(ThemeMode.SYSTEM))
        `when`(themePrefs.dynamicColor).thenReturn(flowOf(false))
        `when`(reportPrefs.coldNightThresholdC).thenReturn(flowOf(16.0))
        `when`(historyRepo.observeStoredStats()).thenReturn(flowOf(StoredHistoryStats(10, 1L)))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(themePrefs, auth, historyRepo, historySync, reportPrefs)

    @Test fun syncNowEnqueuesTheHistorySync() = runTest {
        viewModel().syncNow()
        verify(historySync).enqueueNow()
    }

    @Test fun clearStoredHistoryCallsRepositoryOnlyWhenAsked() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        verify(historyRepo, never()).clearStoredHistory()
        vm.clearStoredHistory()
        advanceUntilIdle()
        verify(historyRepo).clearStoredHistory()
    }

    @Test fun thresholdWritesToPreferences() = runTest {
        val vm = viewModel()
        vm.setColdNightThreshold(14.5)
        advanceUntilIdle()
        verify(reportPrefs).setColdNightThresholdC(14.5)
    }

    @Test fun thresholdNormalisation() {
        assertEquals(listOf(12.0, 12.5), ReportPreferences.coldNightChoices.take(2))
        assertEquals(13, ReportPreferences.coldNightChoices.size)
        assertEquals(18.0, ReportPreferences.coldNightChoices.last(), 0.0)
        assertEquals(12.0, ReportPreferences.normalizeColdNightThreshold(3.0), 0.0)
        assertEquals(18.0, ReportPreferences.normalizeColdNightThreshold(25.0), 0.0)
        assertEquals(15.5, ReportPreferences.normalizeColdNightThreshold(15.6), 0.0)
        assertEquals(16.0, ReportPreferences.DEFAULT_COLD_NIGHT_C, 0.0)
    }

    @Test fun copyAndFormatting() {
        assertEquals("16.0°", formatThreshold(16.0))
        assertEquals("500 KB", formatApproxSize(500_000))
        assertEquals("12.0 MB", formatApproxSize(12_000_000))
        val now = Instant.parse("2026-10-15T12:00:00Z")
        assertTrue(storedHistorySummary(null, now).startsWith("Nothing stored yet"))
        val recent = StoredHistoryStats(rowCount = 340_000, lastSyncMs = now.toEpochMilli() - 5 * 60_000)
        assertEquals("340,000 readings, estimated reading storage 40.8 MB. Last sync 5 min ago.", storedHistorySummary(recent, now))
    }

    @Test fun signOutDelegatesCleanupToAuthAndDoesNotLaunchAnIndependentWipe() = runTest {
        viewModel().signOut()
        advanceUntilIdle()
        verify(auth).signOut()
        verify(historyRepo, never()).clearAllReportData()
    }
}
