package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.model.routines.BoostRoom
import com.gazw89.goodhive.data.model.routines.ModeRoom
import com.gazw89.goodhive.data.model.routines.RoutineStep
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class RoutineActionPlannerTest {
    private val now = LocalDateTime.of(2026, 10, 26, 6, 0) // a Monday
    private fun zone(
        id: String,
        productType: String = ProductType.TRV_CONTROL,
        mode: String = HeatingMode.SCHEDULE,
        online: Boolean = true,
        previousMode: String? = null,
        previousTargetC: Double? = null,
        autoBoostActive: Boolean? = null,
    ) = HeatingZone(
        id = id, productType = productType, name = id.replaceFirstChar { it.uppercase() }, online = online,
        currentTemperatureC = 17.0, targetTemperatureC = 18.0, mode = mode, frostProtectionC = 7.0,
        maxEventsPerDay = 6, minTemperatureC = 5.0, maxTemperatureC = 25.0,
        schedule = WeekSchedule(WeekSchedule.DAY_KEYS.associateWith { listOf(ScheduleSlot(0, 16.0), ScheduleSlot(420, 19.0)) }),
        heatOnDemand = null, previousMode = previousMode, previousTargetC = previousTargetC, autoBoostActive = autoBoostActive,
    )

    @Test fun boostSendsClampedTargetAndMinutes() {
        val step = RoutineStep.Boost(listOf(BoostRoom("bed", 30.0, 600)))
        val send = assertIs<PlannedAction.Send>(planRoutineAction(step, "bed", listOf(zone("bed")), now))
        assertEquals("BOOST", send.payload.getValue("mode").jsonPrimitive.content)
        assertEquals(25.0, send.payload.getValue("target").jsonPrimitive.double)
        assertEquals(360, send.payload.getValue("boost").jsonPrimitive.int)
        assertEquals(mapOf("mode" to "BOOST", "target" to "25.0"), send.expectedState)
    }

    @Test fun scheduleSendsTheCurrentSlotTarget() {
        val step = RoutineStep.SetMode(listOf(ModeRoom("bed", HeatingMode.SCHEDULE)))
        val send = assertIs<PlannedAction.Send>(planRoutineAction(step, "bed", listOf(zone("bed", mode = HeatingMode.OFF)), now))
        assertEquals(16.0, send.payload.getValue("target").jsonPrimitive.double)
        assertEquals(mapOf("mode" to "SCHEDULE", "target" to "16.0"), send.expectedState)
    }

    @Test fun offSendsFrostTargetAndManualSendsItsTarget() {
        val off = assertIs<PlannedAction.Send>(planRoutineAction(RoutineStep.SetMode(listOf(ModeRoom("bed", HeatingMode.OFF))), "bed", listOf(zone("bed")), now))
        assertEquals(7.0, off.payload.getValue("target").jsonPrimitive.double)
        assertEquals(mapOf("mode" to "OFF"), off.expectedState)
        val manual = assertIs<PlannedAction.Send>(planRoutineAction(RoutineStep.SetMode(listOf(ModeRoom("bed", HeatingMode.MANUAL, 19.5))), "bed", listOf(zone("bed")), now))
        assertEquals(mapOf("mode" to "MANUAL", "target" to "19.5"), manual.expectedState)
    }

    @Test fun cancelBoostRestoresPreviousModeOrSkips() {
        val step = RoutineStep.CancelBoost(listOf("bed"))
        val restored = assertIs<PlannedAction.Send>(
            planRoutineAction(step, "bed", listOf(zone("bed", mode = HeatingMode.BOOST, previousMode = HeatingMode.MANUAL, previousTargetC = 18.5)), now),
        )
        assertEquals(mapOf("mode" to "MANUAL", "target" to "18.5"), restored.expectedState)
        assertIs<PlannedAction.Skip>(planRoutineAction(step, "bed", listOf(zone("bed")), now))
    }

    @Test fun heatOnDemandBoostOnTheThermostatIsNotCancelled() {
        val thermostat = zone("thermo", ProductType.HEATING, mode = HeatingMode.BOOST, autoBoostActive = true)
        assertIs<PlannedAction.Skip>(planRoutineAction(RoutineStep.CancelBoost(listOf("thermo")), "thermo", listOf(thermostat), now))
    }

    @Test fun heatOnDemandBoostOnTheThermostatIsReplacedByARoutineBoost() {
        val thermostat = zone("thermo", ProductType.HEATING, mode = HeatingMode.BOOST, autoBoostActive = true)
        val send = assertIs<PlannedAction.Send>(planRoutineAction(RoutineStep.Boost(listOf(BoostRoom("thermo", 21.0, 60))), "thermo", listOf(thermostat), now))
        assertEquals(60, send.payload.getValue("boost").jsonPrimitive.int)
        assertEquals("heating", send.productType)
    }

    @Test fun missingAndOfflineRoomsAreSkipped() {
        val step = RoutineStep.Boost(listOf(BoostRoom("bed", 21.0, 60)))
        assertEquals(PlannedAction.Skip("Room not found"), planRoutineAction(step, "bed", emptyList(), now))
        assertIs<PlannedAction.Skip>(planRoutineAction(step, "bed", listOf(zone("bed", online = false)), now))
    }
}
