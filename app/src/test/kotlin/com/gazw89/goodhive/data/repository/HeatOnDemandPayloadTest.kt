package com.gazw89.goodhive.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.jsonPrimitive

class HeatOnDemandPayloadTest {
    @Test
    fun enableRequiresThermostatZoneAndSetsBothFields() {
        val payload = buildHeatOnDemandPayload(enabled = true, thermostatHiveZoneId = "zone-abc")
        assertEquals("ENABLED", payload["autoBoost"]?.jsonPrimitive?.content)
        assertEquals("zone-abc", payload["zone"]?.jsonPrimitive?.content)
    }

    @Test
    fun enableWithoutZoneThrows() {
        assertFailsWith<IllegalArgumentException> {
            buildHeatOnDemandPayload(enabled = true, thermostatHiveZoneId = null)
        }
    }

    @Test
    fun disableClearsZoneOnly() {
        val payload = buildHeatOnDemandPayload(enabled = false)
        assertEquals("", payload["zone"]?.jsonPrimitive?.content)
        assertEquals(null, payload["autoBoost"])
    }
}
