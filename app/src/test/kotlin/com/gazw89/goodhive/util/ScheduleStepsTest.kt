package com.gazw89.goodhive.util

import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleStepsTest {
    // 2026-10-12 is a Monday.
    private val monday = LocalDate.of(2026, 10, 12)
    private val tuesday = monday.plusDays(1)
    private val wednesday = monday.plusDays(2)

    private fun week(vararg days: Pair<String, List<ScheduleSlot>>) = WeekSchedule(mapOf(*days))
    private fun slot(minute: Int, target: Double) = ScheduleSlot(minute, target)

    @Test
    fun carriesOverFromYesterday() {
        val w = week(
            "monday" to listOf(slot(0, 16.0), slot(1200, 12.0)),
            "tuesday" to listOf(slot(360, 20.0)),
        )
        assertEquals(
            listOf(ScheduleStep(0, 360, 12.0), ScheduleStep(360, 1440, 20.0)),
            w.stepsFor(tuesday),
        )
    }

    @Test
    fun carriesOverAcrossEmptyDay() {
        val w = week(
            "monday" to listOf(slot(0, 16.0), slot(1200, 12.0)),
            "tuesday" to emptyList(),
            "wednesday" to listOf(slot(420, 21.0)),
        )
        assertEquals(
            listOf(ScheduleStep(0, 420, 12.0), ScheduleStep(420, 1440, 21.0)),
            w.stepsFor(wednesday),
        )
        // A wholly empty day carries a single full-day step.
        assertEquals(listOf(ScheduleStep(0, 1440, 12.0)), w.stepsFor(tuesday))
    }

    @Test
    fun slotAtMidnightHasNoLeadingStep() {
        val w = week("monday" to listOf(slot(0, 16.0), slot(600, 18.0)))
        val steps = w.stepsFor(monday)
        assertEquals(listOf(ScheduleStep(0, 600, 16.0), ScheduleStep(600, 1440, 18.0)), steps)
    }

    @Test
    fun lastStepEndsAtMidnightAndStepsAreContiguous() {
        val w = week("monday" to listOf(slot(0, 16.0), slot(360, 20.0), slot(1320, 14.0)))
        val steps = w.stepsFor(monday)
        assertEquals(1440, steps.last().endMinute)
        steps.zipWithNext().forEach { (a, b) -> assertEquals(a.endMinute, b.startMinute) }
    }

    @Test
    fun emptyWeekGivesNoSteps() {
        assertTrue(WeekSchedule(emptyMap()).stepsFor(monday).isEmpty())
    }

    @Test
    fun mondayBeforeFirstSlotInheritsSundaysLastTargetNotMondays() {
        val w = week(
            "sunday" to listOf(slot(420, 18.0), slot(1350, 13.0)),
            "monday" to listOf(
                slot(405, 15.0), slot(420, 15.0), slot(465, 10.0), slot(1005, 15.0), slot(1320, 13.0),
            ),
        )
        val steps = w.stepsFor(monday)
        // 00:00-06:45 is Sunday's final 13 (a Sunday slot at 22:30), not Monday's own final slot.
        assertEquals(ScheduleStep(0, 405, 13.0), steps.first())
        assertEquals(ScheduleStep(1320, 1440, 13.0), steps.last())
        assertEquals(steps.first().targetC, steps.last().targetC)
        steps.zipWithNext().forEach { (a, b) -> assertEquals(a.endMinute, b.startMinute) }
    }

    @Test
    fun carryOverUsesLatestSlotEvenWhenListIsUnsorted() {
        val w = week(
            "sunday" to listOf(slot(1350, 13.0), slot(420, 18.0)),
            "monday" to listOf(slot(405, 15.0)),
        )
        assertEquals(13.0, w.stepsFor(monday).first().targetC)
    }

    @Test
    fun stepsForDayMatchesStepsForDate() {
        val w = week("monday" to listOf(slot(360, 20.0)), "sunday" to listOf(slot(600, 9.0)))
        assertEquals(w.stepsFor(monday), w.stepsForDay("monday"))
        assertTrue(w.stepsForDay("someday").isEmpty())
    }

    @Test
    fun mergedByTargetJoinsConsecutiveEqualTargets() {
        val w = week(
            "sunday" to listOf(slot(1350, 13.0)),
            "monday" to listOf(slot(405, 15.0), slot(420, 15.0), slot(465, 10.0)),
        )
        assertEquals(
            listOf(ScheduleStep(0, 405, 13.0), ScheduleStep(405, 465, 15.0), ScheduleStep(465, 1440, 10.0)),
            w.stepsFor(monday).mergedByTarget(),
        )
    }

    // Regression tests for scheduleNowNextLater.
    private fun zone(w: WeekSchedule) = HeatingZone(
        id = "z", productType = "heating", name = "z", online = true,
        currentTemperatureC = 20.0, targetTemperatureC = 20.0, mode = "SCHEDULE",
        frostProtectionC = 7.0, maxEventsPerDay = 6, schedule = w, heatOnDemand = null,
    )

    @Test
    fun nowNextLaterWithinDay() {
        val w = week("monday" to listOf(slot(360, 20.0), slot(1320, 14.0)))
        val r = zone(w).scheduleNowNextLater(LocalDateTime.of(2026, 10, 12, 7, 0))!!
        assertEquals(ScheduleNowNextLater(20.0, 1320, 14.0), r)
    }

    @Test
    fun nowNextLaterCarriesOverBeforeFirstSlot() {
        val w = week(
            "monday" to listOf(slot(360, 20.0), slot(1320, 14.0)),
            "tuesday" to listOf(slot(420, 21.0)),
        )
        val r = zone(w).scheduleNowNextLater(LocalDateTime.of(2026, 10, 13, 3, 0))!!
        assertEquals(ScheduleNowNextLater(14.0, 420, 21.0), r)
    }

    @Test
    fun nowNextLaterNullForEmptySchedule() {
        assertNull(zone(WeekSchedule(emptyMap())).scheduleNowNextLater(LocalDateTime.of(2026, 10, 12, 7, 0)))
    }
}
