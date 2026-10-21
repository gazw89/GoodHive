package com.gazw89.goodhive.util

import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import java.time.LocalDate

/**
 * A constant-target stretch of a day, in wall-clock minutes since London midnight.
 * [endMinute] is exclusive; the last step of a day ends at 1440.
 */
data class ScheduleStep(val startMinute: Int, val endMinute: Int, val targetC: Double)

private const val MINUTES_PER_DAY = 1440

/**
 * The schedule for [date] as steps covering 0..1440. If the first slot starts after midnight, a
 * leading step carries over the previous non-empty day's last target. Adjacent steps with the
 * same target are not merged. An empty week gives an empty list.
 */
fun WeekSchedule.stepsFor(date: LocalDate): List<ScheduleStep> = stepsForDayIndex(date.dayOfWeek.value - 1)

/** [stepsFor] for a schedule day key such as `"monday"`; an unknown key gives an empty list. */
fun WeekSchedule.stepsForDay(dayKey: String): List<ScheduleStep> {
    val dayIndex = WeekSchedule.DAY_KEYS.indexOf(dayKey)
    return if (dayIndex < 0) emptyList() else stepsForDayIndex(dayIndex)
}

private fun WeekSchedule.stepsForDayIndex(dayIndex: Int): List<ScheduleStep> {
    val slots = days[WeekSchedule.DAY_KEYS[dayIndex]].orEmpty().sortedBy { it.startMinutes }
    val steps = mutableListOf<ScheduleStep>()

    val firstStart = slots.firstOrNull()?.startMinutes ?: MINUTES_PER_DAY
    if (firstStart > 0) {
        val carried = carryOverTarget(dayIndex) ?: return emptyList()
        steps += ScheduleStep(0, firstStart, carried)
    }
    steps += slots.toSteps()
    return steps
}

/**
 * Steps for just these slots: each runs until the next slot's start (the last until 1440). Anything
 * before the first slot is left uncovered, because a bare slot list does not know what carries over.
 */
fun List<ScheduleSlot>.toSteps(): List<ScheduleStep> {
    val sorted = sortedBy { it.startMinutes }
    return sorted.mapIndexed { i, slot ->
        ScheduleStep(slot.startMinutes, sorted.getOrNull(i + 1)?.startMinutes ?: MINUTES_PER_DAY, slot.targetTemperatureC)
    }
}

/** Joins neighbouring steps that hold the same target, so one stretch is one step. */
fun List<ScheduleStep>.mergedByTarget(): List<ScheduleStep> {
    val merged = ArrayList<ScheduleStep>(size)
    for (step in this) {
        val last = merged.lastOrNull()
        if (last != null && last.targetC == step.targetC && last.endMinute == step.startMinute) {
            merged[merged.lastIndex] = last.copy(endMinute = step.endMinute)
        } else {
            merged += step
        }
    }
    return merged
}
