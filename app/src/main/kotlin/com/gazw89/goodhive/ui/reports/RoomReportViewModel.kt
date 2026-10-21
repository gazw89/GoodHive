package com.gazw89.goodhive.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.data.model.reports.InsightConfig
import com.gazw89.goodhive.data.prefs.ReportPreferences
import com.gazw89.goodhive.work.HistorySync
import com.gazw89.goodhive.util.GoodHiveTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RoomReportViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val historySync: HistorySync,
    private val data: ReportsDataSource,
    reportPreferences: ReportPreferences,
) : ViewModel() {

    val zoneId: String = savedState.get<String>(ARG_ZONE_ID).orEmpty()
    private val selection = MutableStateFlow(
        PeriodSelection(
            kind = savedState.get<String>(ARG_KIND)?.let { runCatching { ReportPeriodKind.valueOf(it) }.getOrNull() }
                ?: ReportPeriodKind.WEEK,
            offset = savedState.get<Int>(ARG_OFFSET) ?: 0,
        ),
    )
    private val resumedAt = MutableStateFlow(System.currentTimeMillis())
    private val clock: () -> Long = System::currentTimeMillis

    val uiState: StateFlow<RoomReportState> = combine(selection, reportPreferences.coldNightThresholdC, resumedAt) { s, t, _ -> s to t }
        .flatMapLatest { (sel, threshold) ->
            val window = periodWindow(sel.kind, sel.offset, GoodHiveTime.londonDateNow())
            data.observeZones().flatMapLatest { zones ->
                data.observeInputs(zones, window, clock).map { rooms ->
                    buildRoomReportState(zoneId, rooms, window, clock(), InsightConfig(coldNightThresholdC = threshold))
                }
            }.onStart { emit(RoomReportState(zoneId = zoneId, kind = sel.kind, offset = sel.offset,
                periodLabel = window.label, canGoNext = window.canGoNext)) }
                .catch { emit(RoomReportState(zoneId = zoneId, kind = sel.kind, offset = sel.offset,
                    periodLabel = window.label, canGoNext = window.canGoNext,
                    status = ReportsStatus.ERROR, error = "Stored room report could not be loaded. Try again.")) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoomReportState(zoneId = zoneId))

    private fun select(s: PeriodSelection) {
        selection.value = s
        savedState[ARG_KIND] = s.kind.name
        savedState[ARG_OFFSET] = s.offset
    }
    fun selectKind(kind: ReportPeriodKind) = select(PeriodSelection(kind))
    fun onScreenOpened() { resumedAt.value = System.currentTimeMillis() }
    fun retry() { resumedAt.value = System.currentTimeMillis(); historySync.enqueueNow() }

    fun previous() {
        select(selection.value.let { it.copy(offset = it.offset + 1) })
    }

    fun next() {
        select(selection.value.let { it.copy(offset = (it.offset - 1).coerceAtLeast(0)) })
    }

    companion object {
        const val ARG_ZONE_ID = "zoneId"
        const val ARG_KIND = "kind"
        const val ARG_OFFSET = "offset"
    }
}
