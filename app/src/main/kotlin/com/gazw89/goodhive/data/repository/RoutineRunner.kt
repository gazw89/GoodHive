package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.db.CommandStatus
import com.gazw89.goodhive.data.model.routines.Routine
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs routines through [CommandExecutor], so every write keeps its retries, confirmation polling,
 * request log and the household-wide 5 s gap between writes. The runner never posts to Hive itself and
 * adds no timing of its own.
 *
 * Each room's actions run in routine order, one at a time: the next waits until the previous has
 * settled, because [CommandExecutor.enqueue] supersedes an active command for the same room and group
 * (mode, target and boost share one). Different rooms run side by side and simply queue for the gap.
 *
 * Later actions that were not enqueued yet are lost if the process dies mid-run; commands already
 * enqueued resume through [CommandExecutor.resumeInFlightCommands].
 */
@Singleton
class RoutineRunner internal constructor(
    private val commandExecutor: CommandExecutor,
    private val scope: CoroutineScope,
) {
    @Inject constructor(commandExecutor: CommandExecutor) :
        this(commandExecutor, CoroutineScope(SupervisorJob() + Dispatchers.IO))

    enum class Outcome { DONE, FAILED, SKIPPED }

    data class ActionResult(
        val stepIndex: Int,
        val zoneId: String,
        val zoneName: String,
        val outcome: Outcome,
        val detail: String? = null,
    )

    sealed interface RunState {
        val routineName: String

        data class Running(override val routineName: String, val done: Int, val total: Int) : RunState

        data class Finished(
            override val routineName: String,
            val results: List<ActionResult>,
            val isRetry: Boolean = false,
            /** The UI has already reported this result. */
            val announced: Boolean = false,
        ) : RunState {
            val failed get() = results.filter { it.outcome == Outcome.FAILED }
            val attempted get() = results.count { it.outcome != Outcome.SKIPPED }
            val done get() = results.count { it.outcome == Outcome.DONE }
        }
    }

    private data class Task(val order: Int, val stepIndex: Int, val zoneId: String)

    private val _states = MutableStateFlow<Map<Long, RunState>>(emptyMap())
    /** Keyed by routine id ([DRAFT_KEY] for an editor test run). Absent means idle. */
    val states: StateFlow<Map<Long, RunState>> = _states.asStateFlow()
    private val lastRoutines = ConcurrentHashMap<Long, Routine>()

    /** Starts [routine] unless it is already running. Returns false for an ignored second tap. */
    fun run(key: Long, routine: Routine): Boolean {
        val tasks = routine.steps.flatMapIndexed { stepIndex, step -> step.zoneIds.distinct().map { stepIndex to it } }
            .mapIndexed { order, (stepIndex, zoneId) -> Task(order, stepIndex, zoneId) }
        return start(key, routine, tasks, isRetry = false)
    }

    /** Re-runs only the rooms that failed in the last finished run of [key]. */
    fun retryFailed(key: Long): Boolean {
        val finished = _states.value[key] as? RunState.Finished ?: return false
        val routine = lastRoutines[key] ?: return false
        val tasks = finished.failed.mapIndexed { order, result -> Task(order, result.stepIndex, result.zoneId) }
        if (tasks.isEmpty()) return false
        return start(key, routine, tasks, isRetry = true)
    }

    fun markAnnounced(key: Long) {
        _states.update { current ->
            val finished = current[key] as? RunState.Finished ?: return@update current
            current + (key to finished.copy(announced = true))
        }
    }

    fun clear(key: Long) {
        _states.update { current -> if (current[key] is RunState.Running) current else current - key }
    }

    private fun start(key: Long, routine: Routine, tasks: List<Task>, isRetry: Boolean): Boolean {
        var started = false
        _states.update { current ->
            started = current[key] !is RunState.Running
            if (started) current + (key to RunState.Running(routine.name, 0, tasks.size)) else current
        }
        if (!started) return false
        lastRoutines[key] = routine
        scope.launch { execute(key, routine, tasks, isRetry) }
        return true
    }

    private suspend fun execute(key: Long, routine: Routine, tasks: List<Task>, isRetry: Boolean) {
        val results = mutableListOf<Pair<Task, ActionResult>>()
        val resultsLock = Mutex()
        try {
            coroutineScope {
                tasks.groupBy { it.zoneId }.values.forEach { roomTasks ->
                    launch {
                        for (task in roomTasks) {
                            val result = runTask(routine, task)
                            resultsLock.withLock { results += task to result }
                            _states.update { current ->
                                val running = current[key] as? RunState.Running ?: return@update current
                                current + (key to running.copy(done = running.done + 1))
                            }
                        }
                    }
                }
            }
        } finally {
            // Every room's coroutine has finished (or been cancelled) here, so no lock is needed.
            val ordered = results.sortedBy { it.first.order }.map { it.second }
            _states.update { it + (key to RunState.Finished(routine.name, ordered, isRetry)) }
        }
    }

    private suspend fun runTask(routine: Routine, task: Task): ActionResult {
        val step = routine.steps[task.stepIndex]
        val zones = commandExecutor.observeZones().first()
        val zoneName = zones.firstOrNull { it.id == task.zoneId }?.name ?: "Missing room"
        fun result(outcome: Outcome, detail: String? = null) = ActionResult(task.stepIndex, task.zoneId, zoneName, outcome, detail)
        return when (val planned = planRoutineAction(step, task.zoneId, zones)) {
            is PlannedAction.Skip -> result(Outcome.SKIPPED, planned.reason)
            is PlannedAction.Send -> try {
                val commandId = commandExecutor.enqueue(
                    zoneId = planned.zoneId,
                    zoneName = planned.zoneName,
                    productType = planned.productType,
                    description = "${routine.name}: ${planned.description}",
                    payload = planned.payload,
                    expectedState = planned.expectedState,
                )
                when (commandExecutor.awaitOutcome(commandId)) {
                    CommandStatus.CONFIRMED -> result(Outcome.DONE)
                    CommandStatus.SUPERSEDED -> result(Outcome.SKIPPED, "Replaced by a newer change")
                    else -> result(Outcome.FAILED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                result(Outcome.FAILED, error.message)
            }
        }
    }

    companion object {
        /** Key for a test run of an unsaved routine in the editor. */
        const val DRAFT_KEY = -1L
    }
}
