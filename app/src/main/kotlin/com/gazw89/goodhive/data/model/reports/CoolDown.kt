package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.util.carryOverTarget
import java.time.LocalDate

/**
 * One night's cool-down. [slopeCPerHour] is negative when the room cooled; [coolingRateCPerHour]
 * is the same number as a positive rate.
 */
data class CoolDownNight(
    val nightOf: LocalDate,
    val slopeCPerHour: Double,
    val spanHours: Double,
    val readings: Int,
) {
    val coolingRateCPerHour: Double get() = -slopeCPerHour
}

/** Median and inter-quartile range of the cooling rate (positive = cooling) across [nights]. */
data class CoolDownSummary(
    val nights: List<CoolDownNight>,
    val medianRateCPerHour: Double?,
    val iqrRateCPerHour: Double?,
)

fun summariseCoolDown(nights: List<CoolDownNight>): CoolDownSummary {
    val rates = nights.map { it.coolingRateCPerHour }.sorted()
    return CoolDownSummary(
        nights = nights,
        medianRateCPerHour = rates.takeIf { it.isNotEmpty() }?.let { quantile(it, 0.5) },
        iqrRateCPerHour = rates.takeIf { it.isNotEmpty() }?.let { quantile(it, 0.75) - quantile(it, 0.25) },
    )
}

/**
 * Overnight cool-down per night, as a least-squares slope of temperature against time.
 *
 * Window: from the start of the evening's last schedule slot (when its target drops by at least
 * [minDropC] from the slot before, and it starts at or after 18:00) until 05:30 next morning, or the
 * next morning's first slot if that is a target rise and earlier. Only readings in the window count.
 * A night needs at least [minReadings] readings spanning at least [minSpanHours] hours, else it is
 * skipped. Nights are skipped when any [boostIntervals] (boost / Heat on Demand) overlap the window,
 * or the state log shows a non-SCHEDULE mode in it.
 *
 * Limitations: a slope of a curve that is really exponential, so it depends on how warm the room
 * started; the radiator valve sensor cools faster than a wall thermostat, so only compare a valve
 * with other valves. Uses [scheduleHistory] snapshots, falling back to [current].
 */
fun coolDownNights(
    readings: List<TemperatureReading>,
    current: WeekSchedule,
    scheduleHistory: List<ScheduleAt> = emptyList(),
    modeIntervals: List<ModeInterval> = emptyList(),
    boostIntervals: List<TimeInterval> = emptyList(),
    minDropC: Double = 1.0,
    minReadings: Int = 6,
    minSpanHours: Double = 4.0,
    endMinuteOfMorning: Int = 5 * 60 + 30,
): List<CoolDownNight> {
    val rs = readings.cleaned()
    if (rs.isEmpty()) return emptyList()
    val timeline = ScheduleTimeline(current, scheduleHistory)
    val modes = ModeLog(modeIntervals)
    val out = mutableListOf<CoolDownNight>()
    var date = rs.first().epochMillis.londonDate()
    val lastDate = rs.last().epochMillis.londonDate()
    while (!date.isAfter(lastDate)) {
        val schedule = timeline.scheduleAt(londonMillis(date, 0)).schedule
        val dayIndex = date.dayOfWeek.value - 1
        val slots = schedule.days[WeekSchedule.DAY_KEYS[dayIndex]].orEmpty().sortedBy { it.startMinutes }
        val last = slots.lastOrNull()
        val before = if (slots.size >= 2) slots[slots.size - 2].targetTemperatureC else schedule.carryOverTarget(dayIndex)
        if (last != null && before != null && last.startMinutes >= 18 * 60 &&
            before - last.targetTemperatureC >= minDropC - EPS
        ) {
            val start = londonMillis(date, last.startMinutes)
            var end = londonMillis(date.plusDays(1), endMinuteOfMorning)
            val nextSchedule = timeline.scheduleAt(londonMillis(date.plusDays(1), 0)).schedule
            val firstNext = nextSchedule.days[WeekSchedule.DAY_KEYS[date.plusDays(1).dayOfWeek.value - 1]]
                .orEmpty().minByOrNull { it.startMinutes }
            if (firstNext != null && firstNext.targetTemperatureC > last.targetTemperatureC) {
                end = minOf(end, londonMillis(date.plusDays(1), firstNext.startMinutes))
            }
            val skip = boostIntervals.any { it.overlaps(start, end) } || modes.hasNonScheduleIn(start, end)
            if (!skip) {
                val window = rs.filter { it.epochMillis in start..end }
                if (window.size >= minReadings) {
                    val t0 = window.first().epochMillis
                    val span = (window.last().epochMillis - t0) / HOUR_MS.toDouble()
                    val slope = leastSquaresSlope(window, t0)
                    if (span >= minSpanHours - EPS && slope != null) {
                        out += CoolDownNight(date, slope, span, window.size)
                    }
                }
            }
        }
        date = date.plusDays(1)
    }
    return out
}

/** Least-squares slope in degrees C per hour; null when all readings share one timestamp. */
internal fun leastSquaresSlope(rs: List<TemperatureReading>, originMs: Long): Double? {
    val xs = rs.map { (it.epochMillis - originMs) / HOUR_MS.toDouble() }
    val ys = rs.map { it.temperatureC }
    val mx = xs.average()
    val my = ys.average()
    val denom = xs.sumOf { (it - mx) * (it - mx) }
    if (denom < EPS) return null
    return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / denom
}
