package com.gazw89.goodhive.ui.detail

import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.util.scheduleNowNextLater
import java.time.LocalDateTime
import kotlin.math.floor

/**
 * Target to write to a Heat on Demand source TRV so it stops calling for heat (ending the boost)
 * while HoD stays enabled.
 *
 * - Preferred: the TRV's current schedule slot target, when it is below the room's current temperature
 *   (or when the current temperature is unknown).
 * - Otherwise: one degree below the current temperature, rounded down to a 0.5 step, clamped to at
 *   least the TRV's frost protection and minimum temperature.
 * - Null (skip this source) when the current temperature is unknown and there is no schedule.
 */
fun stopBoostTargetFor(
    zone: HeatingZone,
    now: LocalDateTime = GoodHiveTime.londonDateTimeNow(),
): Double? {
    val current = zone.currentTemperatureC
    val scheduled = zone.scheduleNowNextLater(now)?.currentTargetC
    if (scheduled != null && (current == null || scheduled < current)) return scheduled
    if (current == null) return null
    val floorC = maxOf(zone.frostProtectionC, zone.minTemperatureC)
    return (floor((current - 1.0) * 2) / 2).coerceAtLeast(floorC)
}
