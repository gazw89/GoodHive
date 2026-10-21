package com.gazw89.goodhive.util

import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.WeekSchedule
import java.time.LocalDateTime

data class ScheduleNowNextLater(
    val currentTargetC: Double,
    val nextChangeMinutes: Int?,
    val nextTargetC: Double?,
)

/**
 * Target in force at the start of the day at [dayIndex] (0 = Monday) when nothing has fired
 * yet: the last slot of the nearest previous non-empty day, searching back up to 7 days.
 */
internal fun WeekSchedule.carryOverTarget(dayIndex: Int): Double? {
    for (offset in 1..7) {
        val prev = (dayIndex - offset + 7) % 7
        val last = days[WeekSchedule.DAY_KEYS[prev]].orEmpty().maxByOrNull { it.startMinutes } ?: continue
        return last.targetTemperatureC
    }
    return null
}

/**
 * Mirrors Hive's own `get_schedule_now_next_later` (see Pyhive) — cheap to compute client-side
 * since we already have the full week's schedule cached locally.
 */
fun HeatingZone.scheduleNowNextLater(
    now: LocalDateTime = GoodHiveTime.londonDateTimeNow(),
): ScheduleNowNextLater? {
    val todayIndex = now.dayOfWeek.value - 1
    val nowMinutes = now.hour * 60 + now.minute

    val todaySlots = schedule.days[WeekSchedule.DAY_KEYS[todayIndex]].orEmpty()
    val current = todaySlots.lastOrNull { it.startMinutes <= nowMinutes }
    val next = todaySlots.firstOrNull { it.startMinutes > nowMinutes }

    if (current != null) {
        return ScheduleNowNextLater(
            currentTargetC = current.targetTemperatureC,
            nextChangeMinutes = next?.startMinutes,
            nextTargetC = next?.targetTemperatureC,
        )
    }

    // Nothing has fired yet today — current target carries over from the previous non-empty day.
    val carried = schedule.carryOverTarget(todayIndex) ?: return null
    return ScheduleNowNextLater(
        currentTargetC = carried,
        nextChangeMinutes = todaySlots.firstOrNull()?.startMinutes,
        nextTargetC = todaySlots.firstOrNull()?.targetTemperatureC,
    )
}

fun Int.toTimeLabel(): String {
    val hours = this / 60
    val minutes = this % 60
    return "%02d:%02d".format(hours, minutes)
}
