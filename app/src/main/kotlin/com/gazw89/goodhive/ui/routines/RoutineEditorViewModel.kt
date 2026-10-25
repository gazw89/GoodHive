package com.gazw89.goodhive.ui.routines

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.routines.Routine
import com.gazw89.goodhive.data.model.routines.RoutineIcons
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.data.model.routines.RoutineWarning
import com.gazw89.goodhive.data.model.routines.validate
import com.gazw89.goodhive.data.repository.CommandExecutor
import com.gazw89.goodhive.data.repository.RoutineRepository
import com.gazw89.goodhive.data.repository.RoutineRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class Draft(
    val loaded: Boolean = false,
    val name: String = "",
    val iconKey: String = RoutineIcons.DEFAULT,
    val steps: List<RoutineStep> = emptyList(),
    val original: Routine? = null,
)

data class RoutineEditorUiState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val name: String = "",
    val iconKey: String = RoutineIcons.DEFAULT,
    val steps: List<RoutineStep> = emptyList(),
    /** Thermostat first, then TRVs by name. */
    val zones: List<HeatingZone> = emptyList(),
    val warnings: List<RoutineWarning> = emptyList(),
    val hasChanges: Boolean = false,
    val testRun: RoutineRunner.RunState? = null,
    val error: String? = null,
) {
    val canSave get() = name.isNotBlank() && steps.isNotEmpty()
    fun nameOf(zoneId: String): String? = zones.firstOrNull { it.id == zoneId }?.routineLabel()
}

/** Edits one routine. Route argument `routineId` is a routine id, or [NEW] to create one. */
@HiltViewModel
class RoutineEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: RoutineRepository,
    private val runner: RoutineRunner,
    commandExecutor: CommandExecutor,
) : ViewModel() {
    private val routineId: Long? = savedStateHandle.get<String>(ARG_ROUTINE_ID)?.toLongOrNull()
    private val draft = MutableStateFlow(Draft())
    private val error = MutableStateFlow<String?>(null)
    private val _closed = MutableStateFlow(false)
    /** True once the routine was saved or deleted and the screen should close. */
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    val uiState: StateFlow<RoutineEditorUiState> = combine(
        draft,
        commandExecutor.observeZones(),
        runner.states,
        error,
    ) { draft, zones, states, error ->
        val sorted = zones.sortedWith(compareBy<HeatingZone>({ it.productType != ProductType.HEATING }, { it.name.lowercase() }))
        val current = Routine(id = routineId ?: 0, name = draft.name, iconKey = draft.iconKey, steps = draft.steps)
        val original = draft.original
        RoutineEditorUiState(
            loaded = draft.loaded,
            isNew = routineId == null,
            name = draft.name,
            iconKey = draft.iconKey,
            steps = draft.steps,
            zones = sorted,
            warnings = current.validate { id -> sorted.firstOrNull { it.id == id }?.name }
                .filterNot { it.stepIndex == null && draft.steps.isEmpty() },
            hasChanges = if (original == null) draft.name.isNotBlank() || draft.steps.isNotEmpty()
            else original.name != draft.name || original.iconKey != draft.iconKey || original.steps != draft.steps,
            testRun = states[RoutineRunner.DRAFT_KEY],
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineEditorUiState())

    init {
        runner.clear(RoutineRunner.DRAFT_KEY)
        viewModelScope.launch {
            val existing = routineId?.let { repository.get(it) }
            draft.value = if (existing == null) Draft(loaded = true)
            else Draft(true, existing.name, existing.iconKey, existing.steps, existing)
        }
    }

    fun setName(name: String) = draft.update { it.copy(name = name.take(MAX_NAME_LENGTH)) }
    fun setIcon(key: String) = draft.update { it.copy(iconKey = key) }
    fun addStep(step: RoutineStep) = draft.update { it.copy(steps = it.steps + step) }
    fun replaceStep(index: Int, step: RoutineStep) = draft.update {
        it.copy(steps = it.steps.mapIndexed { i, old -> if (i == index) step else old })
    }
    fun deleteStep(index: Int) = draft.update { it.copy(steps = it.steps.filterIndexed { i, _ -> i != index }) }

    /** [order] holds the step indices (as strings, from the reorder sheet) in their new order. */
    fun reorderSteps(order: List<String>) = draft.update { current ->
        val reordered = order.mapNotNull { it.toIntOrNull()?.let(current.steps::getOrNull) }
        if (reordered.size == current.steps.size) current.copy(steps = reordered) else current
    }

    /** Removes rooms that are no longer on the account; steps left with no rooms are dropped. */
    fun removeMissingRooms(index: Int) = draft.update { current ->
        val known = uiState.value.zones.map { it.id }.toSet()
        val step = current.steps.getOrNull(index) ?: return@update current
        val cleaned = when (step) {
            is RoutineStep.Boost -> step.copy(rooms = step.rooms.filter { it.zoneId in known })
            is RoutineStep.SetMode -> step.copy(rooms = step.rooms.filter { it.zoneId in known })
            is RoutineStep.CancelBoost -> step.copy(zoneIds = step.zoneIds.filter { it in known })
        }
        val steps = if (cleaned.zoneIds.isEmpty()) current.steps.filterIndexed { i, _ -> i != index }
        else current.steps.mapIndexed { i, old -> if (i == index) cleaned else old }
        current.copy(steps = steps)
    }

    /** Runs the unsaved draft now. Changes real heating, like any run. */
    fun testRun() {
        val current = draft.value
        if (current.steps.isEmpty()) return
        runner.run(RoutineRunner.DRAFT_KEY, Routine(name = current.name.ifBlank { "Test run" }, steps = current.steps))
    }

    fun save() {
        val state = uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            try {
                repository.save(Routine(id = routineId ?: 0, name = state.name.trim(), iconKey = state.iconKey, steps = state.steps))
                _closed.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "Couldn't save the routine. Try again."
            }
        }
    }

    fun delete() {
        val id = routineId ?: run { _closed.value = true; return }
        viewModelScope.launch {
            try {
                repository.delete(id)
                runner.clear(id)
                _closed.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error.value = "Couldn't delete the routine. Try again."
            }
        }
    }

    fun dismissError() { error.value = null }

    companion object {
        const val ARG_ROUTINE_ID = "routineId"
        const val NEW = "new"
        const val MAX_NAME_LENGTH = 40
    }
}
