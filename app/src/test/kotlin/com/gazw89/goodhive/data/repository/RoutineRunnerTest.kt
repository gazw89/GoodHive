package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.db.CommandStatus
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.routines.BoostRoom
import com.gazw89.goodhive.data.model.routines.ModeRoom
import com.gazw89.goodhive.data.model.routines.Routine
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.data.network.HiveNode
import com.gazw89.goodhive.data.network.HiveNodesResponse
import com.gazw89.goodhive.data.network.toHeatingZones
import com.gazw89.goodhive.data.repository.RoutineRunner.Outcome
import com.gazw89.goodhive.data.repository.RoutineRunner.RunState
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineRunnerTest : RepositoryTestBase() {
    private val types = mapOf("thermo" to "heating", "bed" to "trvcontrol", "nursery" to "trvcontrol", "kitchen" to "trvcontrol")
    private val states = types.keys.associateWith { id ->
        buildJsonObject { put("name", id); put("mode", "SCHEDULE"); put("target", 16.0) }
    }.toMutableMap()
    private val writeTimes = mutableListOf<Long>()
    private fun snapshot() = HiveNodesResponse(products = states.map { (id, state) -> HiveNode(id, types.getValue(id), state = state) })
    private val morning = Routine(
        id = 1, name = "Morning boost",
        steps = listOf(RoutineStep.Boost(listOf("thermo", "bed", "nursery").map { BoostRoom(it, 21.0, 60) })),
    )

    private suspend fun TestScope.setUp(): Pair<CommandExecutor, RoutineRunner> {
        configureToken()
        zones.rows.value = snapshot().toHeatingZones().map { it.toCacheEntity(0L, json) }
        api.get = { snapshot() }
        api.set = { id, body -> writeTimes += testScheduler.currentTime; states[id] = JsonObject(states.getValue(id) + body) }
        val executor = CommandExecutor(pending, zones, repository, json, this) { testScheduler.currentTime }
        return executor to RoutineRunner(executor, this)
    }

    private fun assertSpacedByGap() {
        writeTimes.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 5_000, "writes at $a and $b are under 5 s apart") }
    }

    @Test fun boostsEveryRoomOneWriteAtATimeAndShowsItAtOnce() = runTest {
        val (executor, runner) = setUp()
        assertTrue(runner.run(1, morning))
        runCurrent()
        assertEquals(1, api.writes.size, "only the first write goes out straight away")
        assertEquals(listOf("BOOST", "BOOST", "BOOST"), executor.observeZones().first().filter { it.id != "kitchen" && it.id != "zone" }.map { it.mode })
        assertIs<RunState.Running>(runner.states.value[1])
        advanceUntilIdle()
        assertEquals(listOf("thermo", "bed", "nursery"), api.writes.map { it.first })
        assertSpacedByGap()
        val finished = assertIs<RunState.Finished>(runner.states.value[1])
        assertEquals(listOf(Outcome.DONE, Outcome.DONE, Outcome.DONE), finished.results.map { it.outcome })
    }

    @Test fun sameRoomStepsRunInOrderWithoutSupersedingEachOther() = runTest {
        val (_, runner) = setUp()
        val routine = Routine(
            id = 2, name = "Back home",
            steps = listOf(
                RoutineStep.SetMode(listOf(ModeRoom("thermo", HeatingMode.MANUAL, 18.0))),
                RoutineStep.Boost(listOf(BoostRoom("thermo", 21.0, 30), BoostRoom("bed", 21.0, 30))),
            ),
        )
        runner.run(2, routine)
        advanceUntilIdle()
        assertEquals(listOf("MANUAL", "BOOST"), api.writes.filter { it.first == "thermo" }.map { it.second.getValue("mode").jsonPrimitive.content })
        assertTrue(pending.rows.value.none { it.status == CommandStatus.SUPERSEDED })
        assertSpacedByGap()
        assertTrue(assertIs<RunState.Finished>(runner.states.value[2]).failed.isEmpty())
    }

    @Test fun manualChangeDuringARunSharesTheSameGap() = runTest {
        val (executor, runner) = setUp()
        runner.run(1, morning)
        runCurrent()
        executor.enqueue("kitchen", "kitchen", "trvcontrol", "Set target", buildTargetPayload(19.0), expectedStateForTarget(19.0))
        advanceUntilIdle()
        assertEquals(4, api.writes.size)
        assertSpacedByGap()
    }

    @Test fun secondTapWhileRunningIsIgnored() = runTest {
        val (_, runner) = setUp()
        assertTrue(runner.run(1, morning))
        assertFalse(runner.run(1, morning))
        advanceUntilIdle()
        assertEquals(3, api.writes.size)
    }

    @Test fun failedRoomIsReportedAndRetryResendsOnlyThatRoom() = runTest {
        val (_, runner) = setUp()
        api.set = { id, body ->
            writeTimes += testScheduler.currentTime
            if (id == "bed") throw IllegalStateException("rejected")
            states[id] = JsonObject(states.getValue(id) + body)
        }
        runner.run(1, morning)
        advanceUntilIdle()
        val finished = assertIs<RunState.Finished>(runner.states.value[1])
        assertEquals(listOf("bed"), finished.failed.map { it.zoneId })
        assertEquals(2, finished.done)

        api.set = { id, body -> writeTimes += testScheduler.currentTime; states[id] = JsonObject(states.getValue(id) + body) }
        api.writes.clear()
        assertTrue(runner.retryFailed(1))
        advanceUntilIdle()
        assertEquals(listOf("bed"), api.writes.map { it.first })
        val retried = assertIs<RunState.Finished>(runner.states.value[1])
        assertTrue(retried.isRetry)
        assertTrue(retried.failed.isEmpty())
    }

    @Test fun cancelWithNothingBoostedIsSkippedNotFailed() = runTest {
        val (_, runner) = setUp()
        runner.run(3, Routine(id = 3, name = "Settle", steps = listOf(RoutineStep.CancelBoost(listOf("bed", "gone")))))
        advanceUntilIdle()
        assertTrue(api.writes.isEmpty())
        val finished = assertIs<RunState.Finished>(runner.states.value[3])
        assertEquals(listOf(Outcome.SKIPPED, Outcome.SKIPPED), finished.results.map { it.outcome })
    }
}
