package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HeatingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.jsonPrimitive

class ModePayloadTest {
    @Test
    fun manualKeepsTargetInPayloadAndExpectedState() {
        val payload = buildModePayload(HeatingMode.MANUAL, 19.5)
        assertEquals("MANUAL", payload["mode"]?.jsonPrimitive?.content)
        assertEquals("19.5", payload["target"]?.jsonPrimitive?.content)
        assertEquals(mapOf("mode" to "MANUAL", "target" to "19.5"), expectedStateForMode(HeatingMode.MANUAL, 19.5))
    }

    @Test
    fun offSendsFrostTargetNotCurrentTargetAndExpectsModeOnly() {
        // Mid-boost the UI's target is the boost's 25°; OFF must send the frost value instead.
        val payload = buildModePayload(HeatingMode.OFF, 25.0, offTargetC = 5.0)
        assertEquals(setOf("mode", "target"), payload.keys)
        assertEquals("OFF", payload["mode"]?.jsonPrimitive?.content)
        assertEquals("5.0", payload["target"]?.jsonPrimitive?.content)
        assertEquals(mapOf("mode" to "OFF"), expectedStateForMode(HeatingMode.OFF, 25.0))
    }

    @Test
    fun offTargetDefaultsToSeven() {
        val payload = buildModePayload(HeatingMode.OFF, 25.0)
        assertEquals("7.0", payload["target"]?.jsonPrimitive?.content)
    }

    @Test
    fun scheduleOmitsTarget() {
        val payload = buildModePayload(HeatingMode.SCHEDULE, 25.0) // UI target is never sent
        assertEquals(setOf("mode"), payload.keys)
        assertEquals(mapOf("mode" to "SCHEDULE"), expectedStateForMode(HeatingMode.SCHEDULE, 25.0))
    }

    @Test
    fun manualWithoutTargetSendsModeOnly() {
        assertEquals(setOf("mode"), buildModePayload(HeatingMode.MANUAL).keys)
        assertFalse(expectedStateForMode(HeatingMode.MANUAL).containsKey("target"))
    }

    @Test
    fun cancelBoostRestoresPreviousManualWithTarget() {
        val payload = buildCancelBoostPayload("MANUAL", 18.0)
        assertEquals("MANUAL", payload["mode"]?.jsonPrimitive?.content)
        assertEquals("18.0", payload["target"]?.jsonPrimitive?.content)
        assertEquals(mapOf("mode" to "MANUAL", "target" to "18.0"), expectedStateForCancelBoost("MANUAL", 18.0))
    }

    @Test
    fun cancelBoostRestoresPreviousOffWithTargetOrFrostDefault() {
        val withTarget = buildCancelBoostPayload("OFF", 7.0)
        assertEquals("OFF", withTarget["mode"]?.jsonPrimitive?.content)
        assertEquals("7.0", withTarget["target"]?.jsonPrimitive?.content)

        val noTarget = buildCancelBoostPayload("OFF", null)
        assertEquals("7.0", noTarget["target"]?.jsonPrimitive?.content)
        assertEquals(mapOf("mode" to "OFF", "target" to "7.0"), expectedStateForCancelBoost("OFF", null))
    }

    @Test
    fun cancelBoostFromScheduleOrUnknownReturnsToScheduleWithSlotTargetNotBoostTarget() {
        listOf("SCHEDULE" to 21.0, null to null, "BOOST" to 25.0, "" to 10.0).forEach { (mode, target) ->
            val payload = buildCancelBoostPayload(mode, target, scheduleTargetC = 19.0)
            assertEquals(setOf("mode", "target"), payload.keys)
            assertEquals("SCHEDULE", payload["mode"]?.jsonPrimitive?.content)
            assertEquals("19.0", payload["target"]?.jsonPrimitive?.content)
            assertEquals(
                mapOf("mode" to "SCHEDULE", "target" to "19.0"),
                expectedStateForCancelBoost(mode, target, scheduleTargetC = 19.0),
            )
        }
    }

    @Test
    fun cancelBoostToScheduleWithoutSlotTargetIsModeOnly() {
        val payload = buildCancelBoostPayload("BOOST", 25.0)
        assertEquals(setOf("mode"), payload.keys)
        assertEquals(mapOf("mode" to "SCHEDULE"), expectedStateForCancelBoost("BOOST", 25.0))
    }

    @Test
    fun scheduleModeSendsAndExpectsSlotTarget() {
        val payload = buildModePayload(HeatingMode.SCHEDULE, 7.0, scheduleTargetC = 19.0)
        assertEquals(setOf("mode", "target"), payload.keys)
        assertEquals("19.0", payload["target"]?.jsonPrimitive?.content)
        assertEquals(
            mapOf("mode" to "SCHEDULE", "target" to "19.0"),
            expectedStateForMode(HeatingMode.SCHEDULE, 7.0, scheduleTargetC = 19.0),
        )
    }
}
