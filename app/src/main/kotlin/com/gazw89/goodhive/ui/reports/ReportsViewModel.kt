package com.gazw89.goodhive.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.data.model.reports.InsightConfig
import com.gazw89.goodhive.data.prefs.ReportPreferences
import com.gazw89.goodhive.data.repository.HistorySyncRepository
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.work.HistorySync
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PeriodSelection(val kind: ReportPeriodKind = ReportPeriodKind.WEEK, val offset: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val data: ReportsDataSource,
    private val historySyncRepository: HistorySyncRepository,
    private val historySync: HistorySync,
    private val savedState: SavedStateHandle,
    reportPreferences: ReportPreferences,
) : ViewModel() {
    private val selection = MutableStateFlow(PeriodSelection(
        savedState.get<String>("reportKind")?.let { runCatching { ReportPeriodKind.valueOf(it) }.getOrNull() } ?: ReportPeriodKind.WEEK,
        savedState.get<Int>("reportOffset") ?: 0))
    private val resumedAt = MutableStateFlow(System.currentTimeMillis())

    val uiState: StateFlow<ReportsState> = combine(selection, reportPreferences.coldNightThresholdC, resumedAt) { s, t, _ -> s to t }
        .flatMapLatest { (sel, threshold) ->
            val window = periodWindow(sel.kind, sel.offset, GoodHiveTime.londonDateNow())
            data.observeZones().flatMapLatest { zones ->
                combine(data.observeInputs(zones, window, System::currentTimeMillis), data.observeProgress()) { rooms, progress ->
                    buildReportsState(rooms, window, System.currentTimeMillis(), syncProgress(zones, progress),
                        InsightConfig(coldNightThresholdC = threshold))
                }
            }.onStart { emit(ReportsState(kind = sel.kind, offset = sel.offset, periodLabel = window.label, canGoNext = window.canGoNext)) }
                .catch { emit(ReportsState(kind = sel.kind, offset = sel.offset, periodLabel = window.label,
                    canGoNext = window.canGoNext, status = ReportsStatus.ERROR, error = "Stored report could not be loaded. Try again.")) }
        }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsState())

    private fun select(s: PeriodSelection) {
        selection.value = s
        savedState["reportKind"] = s.kind.name
        savedState["reportOffset"] = s.offset
    }
    fun selectKind(kind: ReportPeriodKind) = select(PeriodSelection(kind))
    fun previous() = select(selection.value.let { it.copy(offset = it.offset + 1) })
    fun next() = select(selection.value.let { it.copy(offset = (it.offset - 1).coerceAtLeast(0)) })
    fun retry() { resumedAt.value = System.currentTimeMillis(); historySync.enqueueNow() }
    fun onScreenOpened() {
        resumedAt.value = System.currentTimeMillis()
        viewModelScope.launch { if (historySyncRepository.isSyncDue()) historySync.enqueueNow() }
    }
}
