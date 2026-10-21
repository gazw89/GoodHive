package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule
import kotlin.math.abs

/**
 * Result of [timeOnTarget].
 *
 * [share] is [onTargetReadings] / [countedReadings] (null when nothing was counted).
 * [coveredByModeLog] is the fraction of counted readings whose mode was positively known to be
 * SCHEDULE from the state log (0.0 when no log was supplied); the UI should label the share an
 * "estimate" unless this is close to 1. [usedScheduleFallback] is true if any counted reading was
 * judged against the current schedule because no snapshot covered its time.
 */
data class TimeOnTargetResult(
    val countedReadings: Int,
    val onTargetReadings: Int,
    val share: Double?,
    val excludedNotOnSchedule: Int,
    val excludedLowTarget: Int,
    val excludedNoSchedule: Int,
    val coveredByModeLog: Double,
    val usedScheduleFallback: Boolean,
)

/**
 * Share of readings within +/- [toleranceC] (inclusive) of the schedule target.
 *
 * Method: each reading is compared with the target of the schedule slot in force at its London
 * wall-clock time. Readings are skipped when the target is below [minTargetC] (setbacks and
 * frost-protection slots are not comfort targets) or when the state log says the room was not on
 * SCHEDULE (OFF, MANUAL, BOOST, HOLIDAY...). Readings with no mode coverage are counted, which is
 * why [TimeOnTargetResult.coveredByModeLog] is reported. Schedules come from [scheduleHistory]
 * snapshots, falling back to [current] before the first snapshot.
 *
 * Limitations: it counts readings, not time, so uneven sampling skews it; and Hive only exposes the
 * current schedule, so before snapshots exist this is an estimate.
 */
fun timeOnTarget(
    readings: List<TemperatureReading>,
    current: WeekSchedule,
    scheduleHistory: List<ScheduleAt> = emptyList(),
    modeIntervals: List<ModeInterval> = emptyList(),
    toleranceC: Double = 0.5,
    minTargetC: Double = 17.0,
): TimeOnTargetResult {
    val timeline = ScheduleTimeline(current, scheduleHistory)
    val modes = ModeLog(modeIntervals)
    var counted = 0
    var on = 0
    var notSchedule = 0
    var lowTarget = 0
    var noSchedule = 0
    var covered = 0
    var fallback = false
    for (r in readings.cleaned()) {
        val mode = modes.modeAt(r.epochMillis)
        if (mode != null && !isScheduleMode(mode)) {
            notSchedule++
            continue
        }
        val resolved = timeline.targetAt(r.epochMillis)
        if (resolved == null) {
            noSchedule++
            continue
        }
        val (target, usedFallback) = resolved
        if (target < minTargetC - EPS) {
            lowTarget++
            continue
        }
        counted++
        if (mode != null) covered++
        if (usedFallback) fallback = true
        if (abs(r.temperatureC - target) <= toleranceC + EPS) on++
    }
    return TimeOnTargetResult(
        countedReadings = counted,
        onTargetReadings = on,
        share = if (counted > 0) on.toDouble() / counted else null,
        excludedNotOnSchedule = notSchedule,
        excludedLowTarget = lowTarget,
        excludedNoSchedule = noSchedule,
        coveredByModeLog = if (counted > 0) covered.toDouble() / counted else 0.0,
        usedScheduleFallback = fallback,
    )
}
