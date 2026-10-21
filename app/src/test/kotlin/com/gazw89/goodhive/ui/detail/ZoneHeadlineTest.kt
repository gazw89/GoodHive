package com.gazw89.goodhive.ui.detail

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.model.ZoneActivity
import com.gazw89.goodhive.util.ScheduleNowNextLater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneHeadlineTest {
    private fun zone(
        type: String = ProductType.TRV_CONTROL,
        mode: String = HeatingMode.SCHEDULE,
        current: Double? = 20.2,
        target: Double? = 19.0,
        hod: String? = null,
        boostMinutes: Int? = null,
    ) = HeatingZone(
        id = "z",
        productType = type,
        name = "Study",
        online = true,
        currentTemperatureC = current,
        targetTemperatureC = target,
        mode = mode,
        frostProtectionC = 7.0,
        maxEventsPerDay = 6,
        schedule = WeekSchedule(emptyMap()),
        heatOnDemand = hod,
        boostMinutesLeft = boostMinutes,
    )

    private val nnl = ScheduleNowNextLater(currentTargetC = 19.0, nextChangeMinutes = 22 * 60, nextTargetC = 13.0)

    private fun assertClean(h: ZoneHeadline) {
        assertFalse(h.title.contains("null"))
        assertFalse(h.supporting.orEmpty().contains("null"))
        assertTrue(h.title.length <= 24, "title too long: ${h.title}")
    }

    @Test fun formatTempDropsWholeDecimal() {
        assertEquals("19°", formatTemp(19.0))
        assertEquals("20.2°", formatTemp(20.2))
        assertEquals("19.5°", formatTemp(19.5))
    }

    @Test fun offThermostatMentionsFrostProtection() {
        val h = zoneHeadline(zone(type = ProductType.HEATING, mode = HeatingMode.OFF), ZoneActivity.OFF, null)
        assertEquals("Heating is off", h.title)
        assertTrue(h.supporting!!.contains("7°"))
        assertClean(h)
    }

    @Test fun offTrvDiffersFromThermostat() {
        val h = zoneHeadline(zone(mode = HeatingMode.OFF), ZoneActivity.OFF, null)
        assertEquals("Radiator is off", h.title)
        assertFalse(h.supporting!!.contains("Frost"))
    }

    @Test fun offlineShowsLastReading() {
        val h = zoneHeadline(zone(), ZoneActivity.OFFLINE, null)
        assertEquals("Can't reach it", h.title)
        assertTrue(h.supporting!!.contains("20.2°"))
        val noReading = zoneHeadline(zone(current = null), ZoneActivity.OFFLINE, null)
        assertFalse(noReading.supporting!!.contains("reading"))
        assertClean(noReading)
    }

    @Test fun idleScheduleAboveTarget() {
        val h = zoneHeadline(zone(), ZoneActivity.IDLE, nnl)
        assertEquals("Warm enough", h.title)
        assertEquals(
            "Room is 20.2°, a little above the 19° the schedule asks for.",
            h.supporting,
        )
    }

    @Test fun neverMentionsNextChange() {
        assertFalse(zoneHeadline(zone(), ZoneActivity.IDLE, nnl).supporting!!.contains("Next:"))
        assertFalse(zoneHeadline(zone(), null, nnl).supporting.orEmpty().contains("Next:"))
    }

    @Test fun idleManualHoldsAndHintsSchedule() {
        val h = zoneHeadline(zone(mode = HeatingMode.MANUAL), ZoneActivity.IDLE, nnl)
        assertEquals("Warm enough", h.title)
        assertTrue(h.supporting!!.contains("held at 19° manually"))
        assertTrue(h.supporting!!.contains("Switch to Schedule"))
    }

    @Test fun idleBelowTargetIsNeutral() {
        val trv = zoneHeadline(zone(current = 17.0), ZoneActivity.IDLE, null)
        assertEquals("Valve is resting", trv.title)
        assertTrue(trv.supporting!!.contains("below"))
        val thermo = zoneHeadline(zone(type = ProductType.HEATING, current = 17.0), ZoneActivity.IDLE, null)
        assertEquals("Not heating right now", thermo.title)
    }

    @Test fun nullTemperatureAndTarget() {
        val h = zoneHeadline(zone(current = null, target = null), ZoneActivity.IDLE, null)
        assertClean(h)
        assertNull(h.supporting)
        for (a in ZoneActivity.entries + listOf(null)) {
            assertClean(zoneHeadline(zone(current = null, target = null), a, null))
            assertClean(zoneHeadline(zone(current = 20.0, target = null, mode = HeatingMode.MANUAL), a, null))
            assertClean(zoneHeadline(zone(current = null, target = 19.0, mode = HeatingMode.MANUAL), a, null))
        }
    }

    @Test fun scheduleTargetUsedWhenZoneTargetMissing() {
        val h = zoneHeadline(zone(target = null), ZoneActivity.IDLE, nnl)
        assertTrue(h.supporting!!.contains("the 19° the schedule asks for"))
    }

    @Test fun heating() {
        val h = zoneHeadline(zone(current = 17.5), ZoneActivity.HEATING, nnl)
        assertEquals("Heating up", h.title)
        assertTrue(h.supporting!!.contains("17.5°"))
        assertTrue(h.supporting!!.contains("below"))
    }

    @Test fun waitingForBoilerWithAndWithoutHod() {
        val on = zoneHeadline(zone(current = 17.0, hod = "ENABLED"), ZoneActivity.WAITING_FOR_BOILER, null)
        assertEquals("Waiting for the boiler", on.title)
        assertTrue(on.supporting!!.contains("Heat on Demand usually starts within ~4 min"))
        val off = zoneHeadline(zone(current = 17.0, hod = null), ZoneActivity.WAITING_FOR_BOILER, null)
        assertTrue(off.supporting!!.contains("the boiler is off right now"))
        assertFalse(off.supporting!!.contains("Heat on Demand"))
    }

    @Test fun boostWithMinutes() {
        val h = zoneHeadline(zone(type = ProductType.HEATING, mode = HeatingMode.BOOST, target = 22.0, boostMinutes = 25), ZoneActivity.BOOST, null)
        assertEquals("Boosting", h.title)
        assertTrue(h.supporting!!.contains("25 min left"))
    }

    @Test fun boostWithoutMinutes() {
        val h = zoneHeadline(zone(type = ProductType.HEATING, mode = HeatingMode.BOOST, target = 22.0), ZoneActivity.BOOST, null)
        assertEquals("Boosting", h.title)
        assertFalse(h.supporting!!.contains("min left"))
        assertClean(h)
    }

    @Test fun hodBoostIsNotAUserBoost() {
        val h = zoneHeadline(zone(type = ProductType.HEATING, mode = HeatingMode.BOOST, target = 25.0, boostMinutes = 18), ZoneActivity.HOD_BOOST, null)
        assertTrue(h.title.contains("Heat on Demand"))
        assertTrue(h.supporting!!.contains("18 min left"))
        val noMin = zoneHeadline(zone(type = ProductType.HEATING, mode = HeatingMode.BOOST), ZoneActivity.HOD_BOOST, null)
        assertFalse(noMin.supporting!!.contains("min left"))
    }

    @Test fun hodSource() {
        val h = zoneHeadline(zone(hod = "ENABLED", current = 17.0), ZoneActivity.HOD_SOURCE, null)
        assertEquals("Firing the boiler", h.title)
        assertTrue(h.supporting!!.contains("Heat on Demand"))
    }

    @Test fun nullActivityFallback() {
        val h = zoneHeadline(zone(), null, null)
        assertEquals("Room is 20.2°", h.title)
        assertNotNull(h.supporting)
        assertEquals("Checking status", zoneHeadline(zone(current = null), null, null).title)
    }
}
