package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.reports.InsightType
import com.gazw89.goodhive.data.model.reports.ModeInterval
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.util.stepsFor
import com.gazw89.goodhive.data.model.WeekSchedule
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun d1(v: Double): String = String.format(Locale.UK, "%.1f", v)

internal fun deg(v: Double): String = "${d1(v)}°"

internal fun pct(share: Double): String = "${(share * 100).roundToInt()}%"

private val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val weekdayFormatter = DateTimeFormatter.ofPattern("EEE", Locale.UK)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.UK)

internal fun LocalDate.dayLabel(): String = format(dayFormatter)

internal fun LocalDate.weekdayLabel(): String = format(weekdayFormatter)

internal fun Long.londonDayTime(): String =
    Instant.ofEpochMilli(this).atZone(GoodHiveTime.londonZone).format(dayTimeFormatter)

internal fun Long.londonTime(): String =
    Instant.ofEpochMilli(this).atZone(GoodHiveTime.londonZone).format(timeFormatter)

/** "0.3° warmer", "0.3° colder", or "no change" when under 0.05°. */
internal fun deltaWords(deltaC: Double): String = when {
    abs(deltaC) < 0.05 -> "no change"
    deltaC > 0 -> "${d1(deltaC)}° warmer"
    else -> "${d1(-deltaC)}° colder"
}

/** Everything in [InsightType] gets a short title for its card. */
internal fun InsightType.title(): String = when (this) {
    InsightType.SLOW_WARM_UP -> "Slow morning warm-up"
    InsightType.COLD_NIGHTS -> "Cold nights"
    InsightType.VALVE_COOLS_FASTER -> "Cools faster than your other valves"
    InsightType.MISSING_READINGS -> "Missing readings"
    InsightType.COLDER_THAN_LAST_YEAR -> "Colder than last year"
    InsightType.LOW_TIME_ON_TARGET -> "Often off target"
}

/**
 * The target a room spends most of its scheduled week at, among comfort targets (17° and above).
 * Used as the "day target" reference line. Null for an empty schedule or one with only setbacks.
 */
internal fun dominantDayTarget(schedule: WeekSchedule, minTargetC: Double = 17.0): Double? {
    val monday = LocalDate.of(2026, 1, 5) // any Monday; targets do not depend on the date
    val minutesByTarget = HashMap<Double, Int>()
    for (i in 0 until 7) {
        schedule.stepsFor(monday.plusDays(i.toLong()))
            .filter { it.targetC >= minTargetC }
            .forEach { minutesByTarget.merge(it.targetC, it.endMinute - it.startMinute, Int::plus) }
    }
    return minutesByTarget.maxByOrNull { it.value }?.key
}

/**
 * "Off since Tue 7 Oct" when the state log shows the current [mode] began after a different mode,
 * otherwise just the mode name (the log only starts when GoodHive first saw the room, so the first
 * logged run says nothing about when the mode really began).
 */
internal fun modeLine(mode: String, intervals: List<ModeInterval>): String {
    val name = modeName(mode)
    val sorted = intervals.sortedBy { it.fromMs }
    if (sorted.isEmpty() || !sorted.last().mode.equals(mode, ignoreCase = true)) return name
    var i = sorted.lastIndex
    while (i > 0 && sorted[i - 1].mode.equals(mode, ignoreCase = true) && sorted[i - 1].toMs == sorted[i].fromMs) i--
    if (i == 0 || sorted[i - 1].toMs != sorted[i].fromMs) return name
    val since = sorted[i].fromMs
    val date = Instant.ofEpochMilli(since).atZone(GoodHiveTime.londonZone).toLocalDate()
    return "$name since ${date.dayLabel()}"
}

internal fun modeName(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
    "SCHEDULE" -> "On schedule"
    "OFF" -> "Off"
    "MANUAL" -> "Manual"
    "BOOST" -> "Boost"
    "HOLIDAY" -> "Holiday"
    else -> mode.lowercase(Locale.UK).replaceFirstChar { it.uppercase() }
}

/** Mean of two readings lists' sample spacing is not needed; this is the oldest reading time or null. */
internal fun List<TemperatureReading>.firstTimeMs(): Long? = firstOrNull()?.epochMillis

/** Grammatical comparison copy, including no measurable change after rounding. */
internal fun comparisonPhrase(delta: Double, period: String): String =
    if (abs(delta) < 0.05) "About the same as $period" else "${deltaWords(delta)} than $period"
