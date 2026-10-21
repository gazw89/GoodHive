package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import java.time.LocalDate

/** The coldest reading of the night that began on the evening of [nightOf] (London date). */
data class NightMinimum(
    val nightOf: LocalDate,
    val minC: Double,
    val minAtMs: Long,
    val readings: Int,
)

private const val NIGHT_START_MIN = 22 * 60
private const val NIGHT_END_MIN = 7 * 60

/**
 * Minimum per night, where a night is 22:00 to 07:00 London wall-clock and is labelled by the date
 * on which it starts (so a 03:00 reading belongs to the previous date). Nights with fewer than
 * [minReadings] readings are dropped so a stray 06:55 sample cannot masquerade as a night.
 * Night length follows the clock: 10 h at the autumn change, 8 h at the spring change.
 */
fun nightMinima(readings: List<TemperatureReading>, minReadings: Int = 6): List<NightMinimum> =
    readings.cleaned().mapNotNull { r ->
        val t = r.epochMillis.toLondon()
        val minute = t.hour * 60 + t.minute
        when {
            minute >= NIGHT_START_MIN -> t.toLocalDate() to r
            minute < NIGHT_END_MIN -> t.toLocalDate().minusDays(1) to r
            else -> null
        }
    }.groupBy({ it.first }, { it.second }).toSortedMap().mapNotNull { (date, rs) ->
        if (rs.size < minReadings) return@mapNotNull null
        val coldest = rs.minBy { it.temperatureC }
        NightMinimum(date, coldest.temperatureC, coldest.epochMillis, rs.size)
    }

/** Nights whose minimum is strictly below [thresholdC] (default 16.0), oldest first. */
fun coldNights(
    readings: List<TemperatureReading>,
    thresholdC: Double = 16.0,
    minReadings: Int = 6,
): List<NightMinimum> = nightMinima(readings, minReadings).filter { it.minC < thresholdC }

/** A run with no reading, from the last reading before it ([startMs]) to the next ([endMs]). */
data class ReadingGap(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Runs with no reading for strictly more than [minGapMs] (default 2 h). When [windowFromMs] /
 * [windowToMs] are given, the time before the first reading and after the last are also treated as
 * gaps (an empty input is then one whole-window gap); without them, edges are not examined and
 * fewer than two readings give no gaps. Limitation: a gap says the cloud has no samples, not why
 * (flat battery, lost signal, or a Hive-side hole).
 */
fun readingGaps(
    readings: List<TemperatureReading>,
    minGapMs: Long = 2 * HOUR_MS,
    windowFromMs: Long? = null,
    windowToMs: Long? = null,
): List<ReadingGap> {
    val times = readings.cleaned().map { it.epochMillis }
        .filter { (windowFromMs == null || it >= windowFromMs) && (windowToMs == null || it < windowToMs) }
    val points = buildList {
        windowFromMs?.let { add(it) }
        addAll(times)
        windowToMs?.let { add(it) }
    }
    return points.zipWithNext { a, b -> ReadingGap(a, b) }.filter { it.durationMs > minGapMs }
}
