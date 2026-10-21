package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.WeekSchedule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class ZoneCacheMappingTest {
    private val json = Json

    private fun zone(autoBoostTrvIds: List<String>, historySourceIds: List<String> = emptyList()) = HeatingZone(
        id = "z",
        productType = ProductType.HEATING,
        name = "Thermostat",
        online = true,
        currentTemperatureC = 20.0,
        targetTemperatureC = 25.0,
        mode = "BOOST",
        frostProtectionC = 7.0,
        maxEventsPerDay = 6,
        schedule = WeekSchedule(emptyMap()),
        heatOnDemand = null,
        working = true,
        autoBoostActive = true,
        autoBoostTrvIds = autoBoostTrvIds,
        historySourceIds = historySourceIds,
        previousMode = "MANUAL",
        previousTargetC = 18.0,
        boostMinutesLeft = 30,
    )

    @Test
    fun activityFieldsSurviveCacheRoundTrip() {
        val original = zone(listOf("a", "b"))
        val restored = original.toCacheEntity(0L, json).toDomain(json)
        assertEquals(original.working, restored.working)
        assertEquals(original.autoBoostActive, restored.autoBoostActive)
        assertEquals(original.autoBoostTrvIds, restored.autoBoostTrvIds)
        assertEquals(original.previousMode, restored.previousMode)
        assertEquals(original.previousTargetC, restored.previousTargetC)
        assertEquals(original.boostMinutesLeft, restored.boostMinutesLeft)
    }

    @Test
    fun emptyIdListRoundTrips() {
        val restored = zone(emptyList()).toCacheEntity(0L, json).toDomain(json)
        assertEquals(emptyList(), restored.autoBoostTrvIds)
    }

    @Test
    fun historySourceIdsSurviveCacheRoundTrip() {
        val restored = zone(emptyList(), listOf("v1", "v2")).toCacheEntity(0L, json).toDomain(json)
        assertEquals(listOf("v1", "v2"), restored.historySourceIds)
        assertEquals(emptyList(), zone(emptyList()).toCacheEntity(0L, json).toDomain(json).historySourceIds)
    }
}
