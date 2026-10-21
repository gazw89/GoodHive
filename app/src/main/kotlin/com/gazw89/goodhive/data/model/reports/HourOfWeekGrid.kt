package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import java.time.DayOfWeek

/**
 * 7 x 24 "typical week": mean temperature by London-local weekday (0 = Monday) and clock hour.
 * [meanC] is null where no data fell in that cell. [sampleCount] is the number of distinct
 * (date, hour) buckets that contributed, i.e. roughly the number of weeks observed.
 */
data class HourOfWeekGrid(val meanC: List<List<Double?>>, val sampleCount: List<List<Int>>) {
    fun mean(day: DayOfWeek, hour: Int): Double? = meanC[day.value - 1][hour]
}

/**
 * Builds the grid from readings in the [weeks] calendar weeks ending at [endMs] (exclusive).
 *
 * Method: readings are first averaged per (London date, clock hour), then those hourly means are
 * averaged per cell, so each week counts equally however densely it was sampled. On the autumn
 * change the repeated 01:00 hour is merged into one bucket; on the spring change the missing hour
 * simply has no sample for that date. Limitation: the schedule and mode are not considered.
 */
fun hourOfWeekGrid(readings: List<TemperatureReading>, endMs: Long, weeks: Int): HourOfWeekGrid {
    require(weeks >= 1)
    val startMs = endMs.toLondon().minusWeeks(weeks.toLong()).toInstant().toEpochMilli()
    val hourly = readings.cleaned()
        .filter { it.epochMillis in startMs until endMs }
        .groupBy { r -> r.epochMillis.toLondon().let { Triple(it.toLocalDate(), it.dayOfWeek.value - 1, it.hour) } }
        .map { (k, rs) -> k to rs.map { it.temperatureC }.average() }
    val means = List(7) { d ->
        List(24) { h ->
            hourly.filter { it.first.second == d && it.first.third == h }.map { it.second }
                .takeIf { it.isNotEmpty() }?.average()
        }
    }
    val counts = List(7) { d -> List(24) { h -> hourly.count { it.first.second == d && it.first.third == h } } }
    return HourOfWeekGrid(means, counts)
}
