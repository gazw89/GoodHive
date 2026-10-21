package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import java.time.LocalDate

/**
 * Low / average / high for one London calendar day.
 *
 * [avgC] is the plain mean of the readings, not time-weighted, so a day with a long gap leans
 * towards the periods that were sampled. [partial] is true when the day is not fully observed.
 */
data class DailyStats(
    val date: LocalDate,
    val lowC: Double,
    val avgC: Double,
    val highC: Double,
    val count: Int,
    val partial: Boolean,
)

/**
 * Per London calendar day stats from raw [readings] (any order; non-finite values ignored).
 *
 * Days are bucketed with Europe/London wall-clock dates, so 25 Oct 2026 is a 25-hour day and 29 Mar
 * 2026 a 23-hour day. A day is [DailyStats.partial] when fewer than [minHourCoverage] of its
 * clock-hours (23, 24 or 25) contain a reading, or when [nowMs] is given and falls before the end
 * of the day (today). Limitation: days with no readings are simply absent from the result.
 */
fun dailyStats(
    readings: List<TemperatureReading>,
    minHourCoverage: Double = 0.75,
    nowMs: Long? = null,
): List<DailyStats> =
    readings.cleaned().groupBy { it.epochMillis.londonDate() }.toSortedMap().map { (date, rs) ->
        val start = londonMillis(date, 0)
        val end = londonMillis(date, 1440)
        val hoursInDay = ((end - start) / HOUR_MS).toInt()
        val covered = rs.map { ((it.epochMillis - start) / HOUR_MS).toInt() }.toSet().size
        val partial = covered < Math.ceil(minHourCoverage * hoursInDay) || (nowMs != null && nowMs < end)
        val temps = rs.map { it.temperatureC }
        DailyStats(date, temps.min(), temps.average(), temps.max(), rs.size, partial)
    }

/** Mean low / average / high across the days of one comparison window. */
data class WindowSummary(val days: Int, val avgC: Double?, val meanLowC: Double?, val meanHighC: Double?)

/** Current window versus a previous (or last-year) window; differences are current minus previous. */
data class WindowComparison(
    val current: WindowSummary,
    val previous: WindowSummary,
    val avgDifferenceC: Double?,
    val lowDifferenceC: Double?,
    val highDifferenceC: Double?,
)

/**
 * Compares two windows of daily stats. Each window's average is the mean of its daily averages (so
 * every day counts once). Partial days are excluded unless [includePartialDays]. A difference is
 * null when either window has no days.
 */
fun compareWindows(
    current: List<DailyStats>,
    previous: List<DailyStats>,
    includePartialDays: Boolean = false,
): WindowComparison {
    fun summarise(days: List<DailyStats>): WindowSummary {
        val used = if (includePartialDays) days else days.filterNot { it.partial }
        return WindowSummary(
            days = used.size,
            avgC = used.takeIf { it.isNotEmpty() }?.map { it.avgC }?.average(),
            meanLowC = used.takeIf { it.isNotEmpty() }?.map { it.lowC }?.average(),
            meanHighC = used.takeIf { it.isNotEmpty() }?.map { it.highC }?.average(),
        )
    }
    val c = summarise(current)
    val p = summarise(previous)
    fun diff(a: Double?, b: Double?) = if (a != null && b != null) a - b else null
    return WindowComparison(c, p, diff(c.avgC, p.avgC), diff(c.meanLowC, p.meanLowC), diff(c.meanHighC, p.meanHighC))
}

/**
 * Whole-home daily stats: for each date, the mean of the rooms' daily averages (thermostat room
 * included, each room weighted equally). Low and high are likewise the means of the rooms' lows and
 * highs, not the hottest or coldest room. [DailyStats.count] is the sum of readings and
 * [DailyStats.partial] is true if a room-day is missing or any contributing room-day is partial.
 */
fun wholeHomeDaily(rooms: Collection<List<DailyStats>>): List<DailyStats> =
    rooms.flatten().groupBy { it.date }.toSortedMap().map { (date, days) ->
        DailyStats(
            date = date,
            lowC = days.map { it.lowC }.average(),
            avgC = days.map { it.avgC }.average(),
            highC = days.map { it.highC }.average(),
            count = days.sumOf { it.count },
            partial = days.size != rooms.size || days.any { it.partial },
        )
    }
