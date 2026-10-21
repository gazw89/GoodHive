package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.util.ScheduleStep
import com.gazw89.goodhive.util.stepsFor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal val LONDON: ZoneId = GoodHiveTime.londonZone
internal const val MINUTE_MS = 60_000L
internal const val HOUR_MS = 3_600_000L
internal const val EPS = 1e-9

internal fun Long.toLondon(): ZonedDateTime = Instant.ofEpochMilli(this).atZone(LONDON)
internal fun Long.londonDate(): LocalDate = toLondon().toLocalDate()
internal fun Long.londonMinuteOfDay(): Int = toLondon().let { it.hour * 60 + it.minute }

/**
 * The instant at which London's wall clock on [date] reads [minuteOfDay] (0..1440+; 1440 means the
 * next midnight). Wall-clock based, so DST-correct. A time inside the spring gap resolves forward;
 * a time in the autumn overlap resolves to the first (BST) occurrence.
 */
internal fun londonMillis(date: LocalDate, minuteOfDay: Int): Long {
    val day = date.plusDays((minuteOfDay / 1440).toLong())
    val m = minuteOfDay % 1440
    return ZonedDateTime.of(day, LocalTime.of(m / 60, m % 60), LONDON).toInstant().toEpochMilli()
}

internal fun quantile(sorted: List<Double>, p: Double): Double {
    require(sorted.isNotEmpty())
    val pos = p * (sorted.size - 1)
    val lo = pos.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
}

internal fun median(values: List<Double>): Double? =
    if (values.isEmpty()) null else quantile(values.sorted(), 0.5)

/** Readings with finite temperatures, ascending by time, de-duplicated on timestamp. */
internal fun List<TemperatureReading>.cleaned(): List<TemperatureReading> =
    filter { it.temperatureC.isFinite() }.sortedBy { it.epochMillis }.distinctBy { it.epochMillis }

/** Index of the last element with epochMillis <= [ms], or -1. Receiver must be ascending. */
internal fun List<TemperatureReading>.lastIndexAtOrBefore(ms: Long): Int {
    var lo = 0
    var hi = size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].epochMillis <= ms) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

/** A half-open time span `[fromMs, toMs)`. Used for boost / Heat on Demand evidence. */
data class TimeInterval(val fromMs: Long, val toMs: Long) {
    fun overlaps(startMs: Long, endMs: Long): Boolean = fromMs < endMs && toMs > startMs
}

/**
 * A stretch during which a room was in [mode] (Hive mode string, e.g. "SCHEDULE", "OFF", "MANUAL",
 * "BOOST", "HOLIDAY"), from our own state log. Half-open `[fromMs, toMs)`.
 */
data class ModeInterval(val fromMs: Long, val toMs: Long, val mode: String)

/** A schedule that was in force from [fromMs] until the next snapshot. */
data class ScheduleAt(val fromMs: Long, val schedule: WeekSchedule)

internal const val MODE_SCHEDULE = "SCHEDULE"

internal fun isScheduleMode(mode: String) = mode.equals(MODE_SCHEDULE, ignoreCase = true)

/**
 * Lookup over [ModeInterval]s. Intervals are assumed not to overlap; if they do, the one starting
 * latest at or before the query wins. Gaps give `null` (no coverage).
 */
internal class ModeLog(intervals: List<ModeInterval>) {
    private val sorted = intervals.filter { it.toMs > it.fromMs }.sortedBy { it.fromMs }
    val isEmpty: Boolean get() = sorted.isEmpty()

    fun modeAt(ms: Long): String? {
        var lo = 0
        var hi = sorted.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].fromMs <= ms) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (ans < 0) return null
        return sorted[ans].takeIf { ms < it.toMs }?.mode
    }

    /** True if any logged non-SCHEDULE mode overlaps `[fromMs, toMs)`. */
    fun hasNonScheduleIn(fromMs: Long, toMs: Long): Boolean =
        sorted.any { it.fromMs < toMs && it.toMs > fromMs && !isScheduleMode(it.mode) }
}

internal data class ResolvedSchedule(val schedule: WeekSchedule, val isFallback: Boolean)

/**
 * Picks the schedule in force at any instant. Snapshots apply from their [ScheduleAt.fromMs]; for
 * instants before the first snapshot (or with no snapshots at all) the [current] schedule is used
 * and flagged as a fallback, because Hive only exposes today's schedule and past changes are
 * unknown. Targets are interpreted in London wall-clock time via [stepsFor].
 */
internal class ScheduleTimeline(
    private val current: WeekSchedule,
    snapshots: List<ScheduleAt> = emptyList(),
) {
    private val sorted = snapshots.sortedBy { it.fromMs }
    private val stepCache = HashMap<Pair<Int, LocalDate>, List<ScheduleStep>>()

    private fun indexAt(ms: Long): Int {
        var ans = -1
        for (i in sorted.indices) if (sorted[i].fromMs <= ms) ans = i else break
        return ans
    }

    fun scheduleAt(ms: Long): ResolvedSchedule {
        val i = indexAt(ms)
        return if (i < 0) ResolvedSchedule(current, true) else ResolvedSchedule(sorted[i].schedule, false)
    }

    private fun stepsAt(ms: Long): Pair<List<ScheduleStep>, Boolean> {
        val i = indexAt(ms)
        val date = ms.londonDate()
        val schedule = if (i < 0) current else sorted[i].schedule
        val steps = stepCache.getOrPut(i to date) { schedule.stepsFor(date) }
        return steps to (i < 0)
    }

    /** Target in force at [ms] and whether the fallback schedule supplied it; null with no schedule. */
    fun targetAt(ms: Long): Pair<Double, Boolean>? {
        val (steps, fallback) = stepsAt(ms)
        val minute = ms.londonMinuteOfDay()
        val step = steps.firstOrNull { minute >= it.startMinute && minute < it.endMinute } ?: return null
        return step.targetC to fallback
    }
}
