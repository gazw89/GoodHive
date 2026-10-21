package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCanonicalizeTest {
    @Test
    fun canonicalizeIsStableAcrossSlotOrder() {
        val a = WeekSchedule(
            mapOf(
                "monday" to listOf(
                    ScheduleSlot(600, 19.0),
                    ScheduleSlot(480, 18.0),
                ),
            ),
        )
        val b = WeekSchedule(
            mapOf(
                "monday" to listOf(
                    ScheduleSlot(480, 18.0),
                    ScheduleSlot(600, 19.0),
                ),
            ),
        )
        assertEquals(canonicalizeWeekSchedule(a), canonicalizeWeekSchedule(b))
    }

    @Test
    fun expectedStateForScheduleKeyPresent() {
        val schedule = WeekSchedule(
            mapOf("monday" to listOf(ScheduleSlot(480, 18.0))),
        )
        val expected = expectedStateForSchedule(schedule)
        assertEquals(canonicalizeWeekSchedule(schedule), expected["schedule"])
    }

    @Test
    fun scheduleModeOmitsTargetFromExpectedState() {
        val expected = expectedStateForMode(HeatingMode.SCHEDULE, 19.0)
        assertEquals(HeatingMode.SCHEDULE, expected["mode"])
        assertFalse(expected.containsKey("target"))
    }

    @Test
    fun manualModeIncludesTarget() {
        val expected = expectedStateForMode(HeatingMode.MANUAL, 19.0)
        assertEquals("19.0", expected["target"])
        assertTrue(expected.containsKey("mode"))
    }
}
