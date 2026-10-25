package com.gazw89.goodhive.ui.routines

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.routines.BoostRoom
import com.gazw89.goodhive.data.model.routines.ModeRoom
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.data.repository.RoutineRunner.ActionResult
import com.gazw89.goodhive.data.repository.RoutineRunner.Outcome
import com.gazw89.goodhive.data.repository.RoutineRunner.RunState
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutineTextTest {
    private val names = mapOf("thermo" to "Thermostat", "bed" to "Bedroom")

    @Test fun summariesGroupMatchingValuesAndListDifferingOnes() {
        assertEquals("Thermostat, Bedroom: 21° for 1h", stepSummary(RoutineStep.Boost(listOf(BoostRoom("thermo", 21.0, 60), BoostRoom("bed", 21.0, 60))), names::get))
        assertEquals("Thermostat: 21° for 1h · Bedroom: 22.5° for 45m", stepSummary(RoutineStep.Boost(listOf(BoostRoom("thermo", 21.0, 60), BoostRoom("bed", 22.5, 45))), names::get))
        assertEquals(
            "Thermostat, Bedroom: 21° for 1h · Missing room: 22° for 1h",
            stepSummary(RoutineStep.Boost(listOf(BoostRoom("thermo", 21.0, 60), BoostRoom("gone", 22.0, 60), BoostRoom("bed", 21.0, 60))), names::get),
        )
        assertEquals("Bedroom → Manual 19°", stepSummary(RoutineStep.SetMode(listOf(ModeRoom("bed", HeatingMode.MANUAL, 19.0))), names::get))
        assertEquals("Missing room", stepSummary(RoutineStep.CancelBoost(listOf("gone")), names::get))
    }

    @Test fun runSummaryNamesFailuresAndSkips() {
        fun result(zone: String, outcome: Outcome) = ActionResult(0, zone, names[zone] ?: zone, outcome)
        assertEquals("Morning: 2 of 2 done", runSummary(RunState.Finished("Morning", listOf(result("thermo", Outcome.DONE), result("bed", Outcome.DONE)))))
        assertEquals("Morning: 1 of 2 done — Bedroom failed", runSummary(RunState.Finished("Morning", listOf(result("thermo", Outcome.DONE), result("bed", Outcome.FAILED)))))
        assertEquals("Settle: nothing to change · 1 skipped", runSummary(RunState.Finished("Settle", listOf(result("bed", Outcome.SKIPPED)))))
    }
}
