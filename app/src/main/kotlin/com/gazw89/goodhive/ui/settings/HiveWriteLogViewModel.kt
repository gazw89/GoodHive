package com.gazw89.goodhive.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.data.db.HeatingZoneCacheDao
import com.gazw89.goodhive.data.db.HiveRequestLogEntity
import com.gazw89.goodhive.data.network.HiveWriteKindFilter
import com.gazw89.goodhive.data.network.HiveWriteResultFilter
import com.gazw89.goodhive.data.network.HiveWriteSort
import com.gazw89.goodhive.data.network.classifyHiveWrite
import com.gazw89.goodhive.data.network.formatHiveWriteDetailTime
import com.gazw89.goodhive.data.network.formatHiveWriteListTime
import com.gazw89.goodhive.data.network.hiveWriteSucceeded
import com.gazw89.goodhive.data.network.hiveWriteSummary
import com.gazw89.goodhive.data.network.hiveWriteTitle
import com.gazw89.goodhive.data.network.matches
import com.gazw89.goodhive.data.network.prettyHiveBody
import com.gazw89.goodhive.data.network.zoneIdFromHivePath
import com.gazw89.goodhive.data.repository.HiveRequestLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HiveWriteFilters(
    val sort: HiveWriteSort = HiveWriteSort.Newest,
    val result: HiveWriteResultFilter = HiveWriteResultFilter.All,
    val kind: HiveWriteKindFilter = HiveWriteKindFilter.All,
    val roomId: String? = null,
)

data class HiveWriteRoomOption(val zoneId: String, val label: String)

data class HiveWriteRow(
    val id: Long,
    val title: String,
    val summary: String,
    val responseCode: Int?,
    val durationMs: Long,
    val whenLabel: String,
    val failed: Boolean,
)

data class HiveWriteLogUi(
    val logEmpty: Boolean = true,
    val filtersHideAll: Boolean = false,
    val rows: List<HiveWriteRow> = emptyList(),
    val rooms: List<HiveWriteRoomOption> = emptyList(),
    val filters: HiveWriteFilters = HiveWriteFilters(),
)

data class HiveWriteDetail(
    val title: String,
    val responseCode: String,
    val failed: Boolean,
    val duration: String,
    val whenLabel: String,
    val path: String,
    val requestText: String,
    val responseText: String,
    val responseIsError: Boolean,
)

data class HiveWriteDetailUi(
    val loaded: Boolean = false,
    val detail: HiveWriteDetail? = null,
)

@HiltViewModel
class HiveWriteLogViewModel @Inject constructor(
    private val log: HiveRequestLogRepository,
    zones: HeatingZoneCacheDao,
) : ViewModel() {
    private val filters = MutableStateFlow(HiveWriteFilters())

    val ui: StateFlow<HiveWriteLogUi> = combine(log.observeAll(), zones.observeAll(), filters) { entries, zoneRows, selected ->
        val names = zoneRows.associate { it.id to it.name }
        val rooms = roomOptions(entries, names)
        val roomId = selected.roomId?.takeIf { id -> rooms.any { it.zoneId == id } }
        val rows = entries
            .filter { entry ->
                selected.result.matches(entry.responseCode) &&
                    selected.kind.matches(classifyHiveWrite(entry.requestBody)) &&
                    (roomId == null || zoneIdFromHivePath(entry.path) == roomId)
            }
            .let { matched ->
                if (selected.sort == HiveWriteSort.Oldest) matched.sortedBy { it.startedAtEpochMillis }
                else matched.sortedByDescending { it.startedAtEpochMillis }
            }
            .map { entry -> entry.toRow(names) }
        HiveWriteLogUi(
            logEmpty = entries.isEmpty(),
            filtersHideAll = entries.isNotEmpty() && rows.isEmpty(),
            rows = rows,
            rooms = rooms,
            filters = selected.copy(roomId = roomId),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HiveWriteLogUi())

    fun setSort(sort: HiveWriteSort) = filters.update { it.copy(sort = sort) }

    fun setResult(result: HiveWriteResultFilter) = filters.update { it.copy(result = result) }

    fun setKind(kind: HiveWriteKindFilter) = filters.update { it.copy(kind = kind) }

    fun setRoom(zoneId: String?) = filters.update { it.copy(roomId = zoneId) }

    fun clear() {
        viewModelScope.launch { log.clear() }
    }

    private fun roomOptions(
        entries: List<HiveRequestLogEntity>,
        names: Map<String, String>,
    ): List<HiveWriteRoomOption> = entries
        .mapNotNull { entry ->
            val zoneId = zoneIdFromHivePath(entry.path) ?: return@mapNotNull null
            HiveWriteRoomOption(zoneId, hiveWriteTitle(entry.path, names))
        }
        .distinctBy { it.zoneId }
        .sortedBy { it.label.lowercase() }

    private fun HiveRequestLogEntity.toRow(names: Map<String, String>): HiveWriteRow {
        val now = System.currentTimeMillis()
        return HiveWriteRow(
            id = id,
            title = hiveWriteTitle(path, names),
            summary = hiveWriteSummary(requestBody),
            responseCode = responseCode,
            durationMs = durationMs,
            whenLabel = formatHiveWriteListTime(startedAtEpochMillis, now),
            failed = !hiveWriteSucceeded(responseCode),
        )
    }
}

@HiltViewModel
class HiveWriteDetailViewModel @Inject constructor(
    log: HiveRequestLogRepository,
    zones: HeatingZoneCacheDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val writeId: Long = savedStateHandle.get<Long>(WRITE_ID) ?: -1L

    val ui: StateFlow<HiveWriteDetailUi> = combine(log.observeById(writeId), zones.observeAll()) { entry, zoneRows ->
        val names = zoneRows.associate { it.id to it.name }
        HiveWriteDetailUi(loaded = true, detail = entry?.toDetail(names))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HiveWriteDetailUi())

    private fun HiveRequestLogEntity.toDetail(names: Map<String, String>): HiveWriteDetail {
        val transportError = error?.takeIf { it.isNotBlank() }
        val responseText = when {
            transportError != null -> transportError
            responseBody.isBlank() -> "Empty"
            else -> prettyHiveBody(responseBody)
        }
        return HiveWriteDetail(
            title = hiveWriteTitle(path, names),
            responseCode = responseCode?.toString() ?: "No response",
            failed = !hiveWriteSucceeded(responseCode),
            duration = "$durationMs ms",
            whenLabel = formatHiveWriteDetailTime(startedAtEpochMillis),
            path = path,
            requestText = if (requestBody.isBlank()) "Empty" else prettyHiveBody(requestBody),
            responseText = responseText,
            responseIsError = transportError != null,
        )
    }

    companion object {
        const val WRITE_ID = "writeId"
    }
}
