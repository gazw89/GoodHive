package com.gazw89.goodhive.ui.components

import com.gazw89.goodhive.ui.theme.clampToTemperatureStep

/** Boost lengths the Hive app offers: 15 minutes to 6 hours (hivehome.com support, 2026-10). */
const val BOOST_MIN_MINUTES = 15
const val BOOST_MAX_MINUTES = 360
const val BOOST_STEP_MINUTES = 15
const val BOOST_DEFAULT_MINUTES = 30

/** Every selectable boost length, in minutes. */
val boostDurationsMinutes: List<Int> = (BOOST_MIN_MINUTES..BOOST_MAX_MINUTES step BOOST_STEP_MINUTES).toList()

/** "45m", "1h", "2h 15m" — short enough for the wheel picker and dashboard chip. */
fun formatBoostDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "${rest}m"
        rest == 0 -> "${hours}h"
        else -> "${hours}h ${rest}m"
    }
}

/** Degrees above the measured room temperature that a new boost opens on. */
const val BOOST_SUGGESTED_RISE_C = 3.0

/** Room readings outside this window are treated as invalid, not as a basis for a suggestion. */
private val PLAUSIBLE_ROOM_RANGE_C = -10.0..45.0

/**
 * Boost target suggested when the sheet opens. Pressing Boost means the room is not warm enough, so
 * the suggestion is [BOOST_SUGGESTED_RISE_C] over the measured room temperature, snapped to the
 * thermostat's 0.5° steps and held inside [[minC], [maxC]]. Without a usable reading (missing,
 * stale because the device is offline, or implausible) it falls back to two degrees over the
 * current target, or 20° when that is unknown too.
 */
fun defaultBoostTargetC(
    currentTemperatureC: Double?,
    currentTargetC: Double?,
    minC: Double,
    maxC: Double,
): Double {
    val reading = currentTemperatureC?.takeIf { it.isFinite() && it in PLAUSIBLE_ROOM_RANGE_C }
    val raw = if (reading != null) {
        reading + BOOST_SUGGESTED_RISE_C
    } else {
        (currentTargetC ?: 18.0) + 2.0
    }
    return clampToTemperatureStep(raw.coerceIn(minC, maxC), minC, maxC)
}

/**
 * Hive reports only the minutes left (`state.boost`), not the length the boost was started with, so
 * the dashboard's remaining-time bar estimates the total as the smallest common length that still
 * covers what is left.
 */
fun estimatedBoostTotalMinutes(minutesLeft: Int): Int =
    BOOST_LENGTH_GUESSES.firstOrNull { it >= minutesLeft } ?: BOOST_MAX_MINUTES

private val BOOST_LENGTH_GUESSES = listOf(30, 60, 120, 180, 240, 300, BOOST_MAX_MINUTES)
