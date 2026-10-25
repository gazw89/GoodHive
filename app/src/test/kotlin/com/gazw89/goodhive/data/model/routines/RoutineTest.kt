package com.gazw89.goodhive.data.model.routines

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.repository.RoutineRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutineTest {
    private fun zone(id: String, type: String = ProductType.TRV_CONTROL, reported: Boolean = true, maxC: Double = 32.0) = HeatingZone(
        id = id, productType = type, name = id, online = true, currentTemperatureC = 17.0, targetTemperatureC = 18.0,
        mode = HeatingMode.SCHEDULE, frostProtectionC = 7.0, maxEventsPerDay = 6, maxTemperatureC = maxC,
        schedule = WeekSchedule(emptyMap()), heatOnDemand = null, reportedByHive = reported,
    )

    private val steps = listOf(
        RoutineStep.SetMode(listOf(ModeRoom("thermo", HeatingMode.SCHEDULE))),
        RoutineStep.Boost(listOf(BoostRoom("bed", 21.0, 60), BoostRoom("thermo", 20.5, 45))),
        RoutineStep.CancelBoost(listOf("bed")),
        RoutineStep.SetMode(listOf(ModeRoom("bed", HeatingMode.MANUAL, 19.0))),
    )

    @Test fun stepsRoundTripThroughStorage() {
        assertEquals(steps, RoutineRepository.decodeSteps(RoutineRepository.encodeSteps(steps)))
    }

    @Test fun unknownFieldsAreIgnoredAndUnreadableStepsOpenEmpty() {
        val withExtra = """[{"type":"cancelBoost","zoneIds":["bed"],"addedLater":true}]"""
        assertEquals(listOf(RoutineStep.CancelBoost(listOf("bed"))), RoutineRepository.decodeSteps(withExtra))
        assertEquals(emptyList(), RoutineRepository.decodeSteps("""[{"type":"fromTheFuture"}]"""))
    }

    @Test fun validateFlagsMissingRoomsAndSetModeAfterBoost() {
        val names = mapOf("thermo" to "Thermostat", "bed" to "Bedroom")
        val boostThenMode = Routine(name = "x", steps = listOf(steps[1], RoutineStep.SetMode(listOf(ModeRoom("thermo", HeatingMode.OFF)))))
        assertEquals(listOf(RoutineWarning(1, "Replaces the earlier boost in Thermostat")), boostThenMode.validate(names::get))
        // A cancel in between means the later Set mode no longer replaces a boost.
        assertEquals(emptyList(), Routine(name = "x", steps = steps).validate(names::get))
        val missing = Routine(name = "x", steps = listOf(RoutineStep.CancelBoost(listOf("gone"))))
        assertEquals(listOf(RoutineWarning(0, "Room not found")), missing.validate(names::get))
        assertEquals(listOf(RoutineWarning(null, "Add at least one step")), Routine(name = "x", steps = emptyList()).validate(names::get))
    }

    @Test fun morningBoostCoversThermostatFirstThenEveryReportedRoomForAnHour() {
        val routine = morningBoostRoutine(listOf(zone("bed"), zone("thermo", ProductType.HEATING), zone("gone", reported = false), zone("cold", maxC = 20.0)))!!
        val boost = routine.steps.single() as RoutineStep.Boost
        assertEquals(listOf("thermo", "bed", "cold"), boost.zoneIds)
        assertTrue(boost.rooms.all { it.minutes == 60 })
        assertEquals(listOf(21.0, 21.0, 20.0), boost.rooms.map { it.targetC })
        assertEquals("Morning boost", routine.name)
        assertNull(morningBoostRoutine(emptyList()))
    }
}
