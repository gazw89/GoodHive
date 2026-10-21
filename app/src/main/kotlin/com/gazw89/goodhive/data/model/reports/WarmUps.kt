package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.util.carryOverTarget
import java.time.LocalDate

/**
 * One qualifying slot start. [minutes] is the time from [slotStartMs] to the first reading within
 * the slot at or above target - 0.3; null (and [reached] false) means "not reached" in the slot.
 */
data class WarmUpSample(
    val date: LocalDate,
    val slotStartMs: Long,
    val slotStartMinutes: Int,
    val targetC: Double,
    val startTempC: Double,
    val minutes: Double?,
) {
    val reached: Boolean get() = minutes != null
}

/** Aggregate over the samples that reached target; quartiles by linear interpolation. */
data class WarmUpSummary(
    val samples: List<WarmUpSample>,
    val reachedCount: Int,
    val notReachedCount: Int,
    val medianMinutes: Double?,
    val q1Minutes: Double?,
    val q3Minutes: Double?,
) {
    val iqrMinutes: Double? get() = if (q1Minutes != null && q3Minutes != null) q3Minutes - q1Minutes else null
}

fun summariseWarmUps(samples: List<WarmUpSample>): WarmUpSummary {
    val mins = samples.mapNotNull { it.minutes }.sorted()
    return WarmUpSummary(
        samples = samples,
        reachedCount = mins.size,
        notReachedCount = samples.size - mins.size,
        medianMinutes = mins.takeIf { it.isNotEmpty() }?.let { quantile(it, 0.5) },
        q1Minutes = mins.takeIf { it.isNotEmpty() }?.let { quantile(it, 0.25) },
        q3Minutes = mins.takeIf { it.isNotEmpty() }?.let { quantile(it, 0.75) },
    )
}

/**
 * Warm-up samples for every schedule slot start whose target rises by at least [minRiseC] over the
 * previous slot (for the first slot of a day, the last slot of the nearest previous day) and where
 * the room's latest reading at or before the slot start, no more than [maxLookbackMs] old, is at
 * least [minShortfallC] below target.
 *
 * Minutes run from slot start to the first later reading within the slot (to the next slot or
 * midnight) at or above target - [reachedWithinC]; no such reading gives a "not reached" sample.
 * Samples are dropped if the state log shows a non-SCHEDULE mode at the slot start or at any reading
 * inspected before the target was reached (we cannot tell whether heating was the schedule's doing).
 * Schedules come from [scheduleHistory] or fall back to [current].
 *
 * Limitation: accuracy is about one reading interval (5 min thermostat, 15-20 min valve), and a
 * valve's sensor sits on the radiator, so valve warm-ups run short; compare rooms of the same kind.
 */
fun warmUps(
    readings: List<TemperatureReading>,
    current: WeekSchedule,
    scheduleHistory: List<ScheduleAt> = emptyList(),
    modeIntervals: List<ModeInterval> = emptyList(),
    minRiseC: Double = 1.0,
    minShortfallC: Double = 0.5,
    reachedWithinC: Double = 0.3,
    maxLookbackMs: Long = 30 * MINUTE_MS,
): List<WarmUpSample> {
    val rs = readings.cleaned()
    if (rs.isEmpty()) return emptyList()
    val timeline = ScheduleTimeline(current, scheduleHistory)
    val modes = ModeLog(modeIntervals)
    val out = mutableListOf<WarmUpSample>()
    var date = rs.first().epochMillis.londonDate()
    val lastDate = rs.last().epochMillis.londonDate()
    while (!date.isAfter(lastDate)) {
        val dayStart = londonMillis(date, 0)
        val schedule = timeline.scheduleAt(dayStart).schedule
        val slots = schedule.days[WeekSchedule.DAY_KEYS[date.dayOfWeek.value - 1]].orEmpty().sortedBy { it.startMinutes }
        slots.forEachIndexed { i, slot ->
            val prevTarget = if (i == 0) schedule.carryOverTarget(date.dayOfWeek.value - 1)
            else slots[i - 1].targetTemperatureC
            if (prevTarget == null || slot.targetTemperatureC - prevTarget < minRiseC - EPS) return@forEachIndexed
            val start = londonMillis(date, slot.startMinutes)
            val end = londonMillis(date, slots.getOrNull(i + 1)?.startMinutes ?: 1440)
            val target = slot.targetTemperatureC
            val before = rs.lastIndexAtOrBefore(start)
            if (before < 0 || start - rs[before].epochMillis > maxLookbackMs) return@forEachIndexed
            val startTemp = rs[before].temperatureC
            if (target - startTemp < minShortfallC - EPS) return@forEachIndexed
            modes.modeAt(start)?.let { if (!isScheduleMode(it)) return@forEachIndexed }
            var minutes: Double? = null
            var excluded = false
            var idx = before + 1
            while (idx < rs.size && rs[idx].epochMillis < end) {
                val r = rs[idx]
                val m = modes.modeAt(r.epochMillis)
                if (m != null && !isScheduleMode(m)) {
                    excluded = true
                    break
                }
                if (r.temperatureC >= target - reachedWithinC - EPS && r.epochMillis > start) {
                    minutes = (r.epochMillis - start) / MINUTE_MS.toDouble()
                    break
                }
                idx++
            }
            if (!excluded) {
                out += WarmUpSample(date, start, slot.startMinutes, target, startTemp, minutes)
            }
        }
        date = date.plusDays(1)
    }
    return out
}
