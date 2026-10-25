package com.gazw89.goodhive.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.data.model.routines.Routine
import com.gazw89.goodhive.data.prefs.RoutinePreferences
import com.gazw89.goodhive.data.repository.CommandExecutor
import com.gazw89.goodhive.data.repository.RoutineRepository
import com.gazw89.goodhive.data.repository.RoutineRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RoutineTile(val routine: Routine, val state: RoutineRunner.RunState?)

data class RoutinesUiState(
    val loaded: Boolean = false,
    val tiles: List<RoutineTile> = emptyList(),
    /** Only the header and its chevron show. */
    val collapsed: Boolean = false,
)

/** A finished run to report once, with Retry when some rooms failed. */
data class RoutineRunMessage(val routineId: Long, val text: String, val canRetry: Boolean)

/** The dashboard's Routines row. Kept apart from DashboardViewModel so that class doesn't grow further. */
@HiltViewModel
class RoutinesViewModel @Inject constructor(
    private val repository: RoutineRepository,
    private val runner: RoutineRunner,
    private val preferences: RoutinePreferences,
    commandExecutor: CommandExecutor,
) : ViewModel() {

    val uiState: StateFlow<RoutinesUiState> = combine(
        repository.observeRoutines(),
        runner.states,
        preferences.observeCollapsed(),
    ) { routines, states, collapsed ->
        RoutinesUiState(loaded = true, tiles = routines.map { RoutineTile(it, states[it.id]) }, collapsed = collapsed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesUiState())

    private val _messages = Channel<RoutineRunMessage>(Channel.BUFFERED)
    val messages: Flow<RoutineRunMessage> = _messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            val zones = commandExecutor.observeZones().first { zones -> zones.any { it.reportedByHive } }
            runCatching { repository.seedDefaultsIfNeeded(zones) }
        }
        viewModelScope.launch {
            runner.states.collect { states ->
                states.forEach { (id, state) ->
                    if (id == RoutineRunner.DRAFT_KEY || state !is RoutineRunner.RunState.Finished || state.announced) return@forEach
                    runner.markAnnounced(id)
                    _messages.send(RoutineRunMessage(id, runSummary(state), state.failed.isNotEmpty()))
                }
            }
        }
    }

    fun run(routineId: Long) {
        val routine = uiState.value.tiles.firstOrNull { it.routine.id == routineId }?.routine ?: return
        runner.run(routineId, routine)
    }

    fun setCollapsed(collapsed: Boolean) {
        viewModelScope.launch { preferences.setCollapsed(collapsed) }
    }

    fun retryFailed(routineId: Long) {
        runner.retryFailed(routineId)
    }

    suspend fun reorder(ids: List<String>) {
        repository.reorder(ids.map { it.toLong() })
    }
}
