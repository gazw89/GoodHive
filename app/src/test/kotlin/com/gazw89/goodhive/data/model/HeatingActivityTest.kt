package com.gazw89.goodhive.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeatingActivityTest {
    private fun zone(
        id: String,
        type: String = ProductType.TRV_CONTROL,
        mode: String = HeatingMode.SCHEDULE,
        online: Boolean = true,
        current: Double? = 20.0,
        target: Double? = 20.0,
        working: Boolean? = null,
        hod: String? = null,
        autoBoostActive: Boolean? = null,
        autoBoostTrvIds: List<String> = emptyList(),
    ) = HeatingZone(
        id = id,
        productType = type,
        name = id,
        online = online,
        currentTemperatureC = current,
        targetTemperatureC = target,
        mode = mode,
        frostProtectionC = 7.0,
        maxEventsPerDay = 6,
        schedule = WeekSchedule(emptyMap()),
        heatOnDemand = hod,
        working = working,
        autoBoostActive = autoBoostActive,
        autoBoostTrvIds = autoBoostTrvIds,
    )

    private fun thermostat(
        mode: String = HeatingMode.SCHEDULE,
        working: Boolean? = false,
        autoBoostActive: Boolean? = null,
        trvIds: List<String> = emptyList(),
        current: Double? = 20.0,
        target: Double? = 20.0,
    ) = zone(
        id = "thermo",
        type = ProductType.HEATING,
        mode = mode,
        working = working,
        autoBoostActive = autoBoostActive,
        autoBoostTrvIds = trvIds,
        current = current,
        target = target,
    )

    private fun activities(vararg zones: HeatingZone) = deriveActivities(zones.toList())

    @Test
    fun off() {
        val a = activities(thermostat(), zone("trv", mode = HeatingMode.OFF, working = true))
        assertEquals(ZoneActivity.OFF, a["trv"])
    }

    @Test
    fun thermostatHolidayCountsAsOff() {
        assertEquals(ZoneActivity.OFF, activities(thermostat(mode = "HOLIDAY"))["thermo"])
    }

    @Test
    fun offline() {
        val a = activities(thermostat(), zone("trv", online = false, working = true))
        assertEquals(ZoneActivity.OFFLINE, a["trv"])
    }

    @Test
    fun depletedBatterySuppressesStaleHeatActivityAndControls() {
        val flat = zone("flat", mode = HeatingMode.BOOST, working = true).copy(batteryLevel = 0)
        assertEquals(ZoneActivity.OFFLINE, activities(thermostat(working = true), flat)["flat"])
        assertEquals(DeviceHealth.BATTERY_EMPTY, flat.deviceHealth)
        assertEquals(false, flat.canControl)
    }

    @Test
    fun offlineDoesNotClaimFlatBatteryWithoutZeroReading() {
        val offline = zone("offline", online = false)
        assertEquals(DeviceHealth.OFFLINE, offline.deviceHealth)
        assertEquals(DeviceHealth.OFFLINE, offline.copy(batteryLevel = 80).deviceHealth)
        assertEquals(DeviceHealth.OFFLINE_LOW_BATTERY, offline.copy(batteryLevel = 15).deviceHealth)
        assertEquals(DeviceHealth.BATTERY_EMPTY, offline.copy(batteryLevel = 0).deviceHealth)
        assertEquals(DeviceHealth.LOW_BATTERY, offline.copy(online = true, batteryLevel = 15).deviceHealth)
        assertNull(offline.copy(online = true, batteryLevel = 16).deviceHealth)
        assertNull(offline.copy(reportedByHive = false).deviceHealth)
    }

    @Test
    fun unreachableDevicesCannotSupplyStaleBoilerEvidence() {
        val flat = zone("flat", working = true, hod = "ENABLED").copy(batteryLevel = 0)
        assertEquals(false, householdHeating(listOf(thermostat(working = null), flat)).boilerOn)
        assertEquals(false, householdHeating(listOf(thermostat(working = null), flat.copy(batteryLevel = 40, online = false))).boilerOn)
        val staleThermostat = thermostat(working = true, autoBoostActive = true, trvIds = listOf("flat")).copy(online = false)
        assertNull(householdHeating(listOf(staleThermostat, flat)).boilerOn)
        assertEquals(false, householdHeating(listOf(staleThermostat, flat)).hodActive)
    }

    @Test
    fun idle() {
        val a = activities(thermostat(), zone("trv", working = false))
        assertEquals(ZoneActivity.IDLE, a["trv"])
        assertEquals(ZoneActivity.IDLE, a["thermo"])
    }

    @Test
    fun heatingTrvWhenBoilerOn() {
        val a = activities(thermostat(working = true), zone("trv", working = true))
        assertEquals(ZoneActivity.HEATING, a["trv"])
        assertEquals(ZoneActivity.HEATING, a["thermo"])
    }

    @Test
    fun waitingForBoilerWhenTrvCallingAndThermostatOff() {
        // Live case: a TRV working=true while the thermostat was OFF.
        val a = activities(
            thermostat(mode = HeatingMode.OFF, working = false),
            zone("lounge", working = true, current = 19.8, target = 21.0),
        )
        assertEquals(ZoneActivity.WAITING_FOR_BOILER, a["lounge"])
        assertEquals(ZoneActivity.OFF, a["thermo"])
    }

    @Test
    fun waitingForBoilerWhenBoilerIdleAndHodOff() {
        val a = activities(thermostat(working = false), zone("trv", working = true))
        assertEquals(ZoneActivity.WAITING_FOR_BOILER, a["trv"])
    }

    @Test
    fun userBoost() {
        val a = activities(
            thermostat(mode = HeatingMode.BOOST, working = true, autoBoostActive = false, target = 25.0),
        )
        assertEquals(ZoneActivity.BOOST, a["thermo"])
    }

    @Test
    fun hodBoostIsNotUserBoostAndListedTrvIsSource() {
        // Live case: thermostat mode BOOST + autoBoost.active.
        val a = activities(
            thermostat(
                mode = HeatingMode.BOOST,
                working = true,
                autoBoostActive = true,
                trvIds = listOf("study"),
                target = 25.0,
            ),
            zone("study", working = true, hod = "ENABLED", autoBoostActive = true),
            zone("lounge", working = true),
        )
        assertEquals(ZoneActivity.HOD_BOOST, a["thermo"])
        assertEquals(ZoneActivity.HOD_SOURCE, a["study"])
        // Boiler is on, so a non-source calling TRV is heating, not waiting.
        assertEquals(ZoneActivity.HEATING, a["lounge"])
    }

    @Test
    fun hodSourceIdsIgnoredWhenNotActive() {
        val a = activities(
            thermostat(autoBoostActive = false, trvIds = listOf("study")),
            zone("study", working = false),
        )
        assertEquals(ZoneActivity.IDLE, a["study"])
    }

    @Test
    fun workingNullFallsBackToTemperatureHeuristic() {
        val a = activities(
            thermostat(working = true),
            zone("cold", working = null, current = 18.0, target = 21.0),
            zone("warm", working = null, current = 21.0, target = 21.0),
        )
        assertEquals(ZoneActivity.HEATING, a["cold"])
        assertEquals(ZoneActivity.IDLE, a["warm"])
    }

    @Test
    fun workingOverridesTemperatureHeuristic() {
        val a = activities(thermostat(working = true), zone("trv", working = false, current = 10.0, target = 21.0))
        assertEquals(ZoneActivity.IDLE, a["trv"])
    }

    @Test
    fun householdBoilerFallsBackToDomainRulesWhenThermostatWorkingNull() {
        val cold = thermostat(working = null, current = 18.0, target = 21.0)
        assertEquals(true, householdHeating(listOf(cold)).boilerOn)

        val off = thermostat(mode = HeatingMode.OFF, working = null, current = 18.0, target = 21.0)
        assertEquals(false, householdHeating(listOf(off)).boilerOn)

        val satisfied = thermostat(working = null)
        val hodTrv = zone("trv", working = true, hod = "ENABLED")
        val plainTrv = zone("plain", working = true)
        assertEquals(true, householdHeating(listOf(satisfied, hodTrv)).boilerOn)
        assertEquals(false, householdHeating(listOf(satisfied, plainTrv)).boilerOn)
    }

    @Test
    fun householdWithoutThermostatIsUnknown() {
        val h = householdHeating(listOf(zone("trv", working = true)))
        assertNull(h.boilerOn)
        assertEquals(false, h.hodActive)
        assertEquals(emptySet(), h.hodSourceIds)
        // Unknown boiler must not be reported as WAITING.
        assertEquals(ZoneActivity.HEATING, deriveActivities(listOf(zone("trv", working = true)))["trv"])
    }

    @Test
    fun householdHodSourcesOnlyWhileActive() {
        val active = thermostat(autoBoostActive = true, trvIds = listOf("a", "b"))
        assertEquals(setOf("a", "b"), householdHeating(listOf(active)).hodSourceIds)
        val ended = thermostat(autoBoostActive = false, trvIds = listOf("a"))
        assertEquals(emptySet(), householdHeating(listOf(ended)).hodSourceIds)
    }

    @Test
    fun twoSourceTrvsBothHodSourceAndThermostatBoost() {
        val a = activities(
            thermostat(working = true, autoBoostActive = true, trvIds = listOf("study", "kitchen"), target = 25.0),
            zone("study", working = true, hod = "ENABLED", autoBoostActive = true),
            zone("kitchen", working = true, hod = "ENABLED", autoBoostActive = true),
            zone("lounge", working = true),
        )
        assertEquals(ZoneActivity.HOD_BOOST, a["thermo"])
        assertEquals(ZoneActivity.HOD_SOURCE, a["study"])
        assertEquals(ZoneActivity.HOD_SOURCE, a["kitchen"])
        assertEquals(ZoneActivity.HEATING, a["lounge"])
    }

    private fun waiting(vararg zones: HeatingZone) =
        roomsWaitingForBoiler(zones.toList(), householdHeating(zones.toList()))

    @Test
    fun boostedTrvCallingWithBoilerOffStillCountsAsWaiting() {
        val zones = arrayOf(
            thermostat(working = false),
            zone("boosted", mode = HeatingMode.BOOST, current = 17.0, target = 21.0, working = true),
            zone("plain", current = 17.0, target = 21.0, working = true),
        )
        // Boost keeps its own activity (and chip), but the banner must still count it.
        assertEquals(ZoneActivity.BOOST, activities(*zones)["boosted"])
        assertEquals(ZoneActivity.WAITING_FOR_BOILER, activities(*zones)["plain"])
        assertEquals(2, waiting(*zones))
    }

    @Test
    fun targetAboveTemperatureAloneIsNotWaitingWhenHiveSaysNotWorking() {
        assertEquals(0, waiting(thermostat(working = false), zone("trv", mode = HeatingMode.BOOST, current = 17.0, target = 21.0, working = false)))
    }

    @Test
    fun nothingWaitsWhileBoilerRunsOrIsUnknown() {
        val trv = zone("trv", mode = HeatingMode.BOOST, current = 17.0, target = 21.0, working = true)
        assertEquals(0, waiting(thermostat(working = true), trv))
        assertEquals(0, waiting(trv)) // no thermostat: boiler state unknown
    }

    @Test
    fun offOfflineAndThermostatAreNeverWaiting() {
        assertEquals(
            0,
            waiting(
                thermostat(working = false, current = 17.0, target = 21.0),
                zone("off", mode = HeatingMode.OFF, working = true),
                zone("offline", online = false, working = true),
            ),
        )
    }
}
