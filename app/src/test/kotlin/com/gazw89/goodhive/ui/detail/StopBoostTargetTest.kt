package com.gazw89.goodhive.ui.detail

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StopBoostTargetTest {
    // 2026-10-14 is a Wednesday.
    private val now = LocalDateTime.of(2026, 10, 14, 12, 0)

    private fun schedule(target: Double?) = WeekSchedule(
        if (target == null) emptyMap() else mapOf("wednesday" to listOf(ScheduleSlot(360, target))),
    )

    private fun trv(
        current: Double?,
        scheduled: Double?,
        frost: Double = 7.0,
        min: Double = 5.0,
    ) = HeatingZone(
        id = "trv",
        productType = ProductType.TRV_CONTROL,
        name = "Bedroom",
        online = true,
        currentTemperatureC = current,
        targetTemperatureC = 21.0,
        mode = HeatingMode.SCHEDULE,
        frostProtectionC = frost,
        maxEventsPerDay = 6,
        minTemperatureC = min,
        schedule = schedule(scheduled),
        heatOnDemand = "ENABLED",
    )

    @Test
    fun usesScheduleTargetWhenBelowCurrent() {
        assertEquals(16.0, stopBoostTargetFor(trv(current = 18.0, scheduled = 16.0), now))
    }

    @Test
    fun usesScheduleTargetWhenCurrentUnknown() {
        assertEquals(16.0, stopBoostTargetFor(trv(current = null, scheduled = 16.0), now))
    }

    @Test
    fun fallsBackWhenScheduleNotBelowCurrent() {
        // 18.3 - 1.0 = 17.3 -> 17.0
        assertEquals(17.0, stopBoostTargetFor(trv(current = 18.3, scheduled = 19.0), now))
        assertEquals(17.5, stopBoostTargetFor(trv(current = 18.5, scheduled = 18.5), now))
    }

    @Test
    fun fallsBackWhenNoSchedule() {
        assertEquals(17.0, stopBoostTargetFor(trv(current = 18.0, scheduled = null), now))
    }

    @Test
    fun fallbackIsClampedToFrostAndMinimum() {
        assertEquals(7.0, stopBoostTargetFor(trv(current = 7.5, scheduled = null), now))
        assertEquals(9.0, stopBoostTargetFor(trv(current = 7.5, scheduled = null, frost = 7.0, min = 9.0), now))
    }

    @Test
    fun skipsWhenNothingKnown() {
        assertNull(stopBoostTargetFor(trv(current = null, scheduled = null), now))
    }

    @Test
    fun choosesTargetPerSourceIndependently() {
        val sources = listOf(
            trv(current = 18.0, scheduled = 16.0),
            trv(current = 18.3, scheduled = 19.0),
            trv(current = null, scheduled = null),
        )
        assertEquals(listOf(16.0, 17.0, null), sources.map { stopBoostTargetFor(it, now) })
    }
}
