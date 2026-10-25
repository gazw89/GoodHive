package com.gazw89.goodhive.ui.routines

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChildFriendly
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.ui.graphics.vector.ImageVector
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.routines.RoutineIcons
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.data.repository.RoutineRunner
import com.gazw89.goodhive.ui.components.formatBoostDuration
import kotlin.math.roundToInt

/** Material icon and spoken label for each [RoutineIcons] key. */
internal fun routineIcon(key: String): ImageVector = when (key) {
    "sunrise" -> Icons.Filled.WbTwilight
    "home" -> Icons.Filled.Home
    "away" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "bath" -> Icons.Filled.Bathtub
    "bedtime" -> Icons.Filled.Bedtime
    "baby" -> Icons.Filled.ChildFriendly
    "snow" -> Icons.Filled.AcUnit
    else -> Icons.Filled.Bolt
}

internal fun routineIconLabel(key: String): String = when (key) {
    "sunrise" -> "Morning"
    "home" -> "Home"
    "away" -> "Out"
    "bath" -> "Bath"
    "bedtime" -> "Bedtime"
    "baby" -> "Baby"
    "snow" -> "Cold"
    else -> "Boost"
}

/** The thermostat is the only room that fires the boiler, so it says so wherever rooms are picked. */
internal fun HeatingZone.routineLabel(): String = if (productType == ProductType.HEATING) "$name (boiler)" else name

/** "21°" for whole values, "21.5°" otherwise. */
internal fun formatRoutineTemp(valueC: Double): String {
    val tenths = (valueC * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}°" else "%.1f°".format(tenths / 10.0)
}

internal fun modeLabel(mode: String): String = when (mode) {
    HeatingMode.SCHEDULE -> "Schedule"
    HeatingMode.MANUAL -> "Manual"
    HeatingMode.OFF -> "Off"
    else -> mode
}

internal fun stepTitle(step: RoutineStep): String = when (step) {
    is RoutineStep.Boost -> "Boost"
    is RoutineStep.SetMode -> "Set mode"
    is RoutineStep.CancelBoost -> "Cancel boost"
}

/**
 * One line describing a step, rooms grouped by value: "Thermostat, Bedroom: 21° for 1h" or, when
 * rooms differ, "Thermostat, Bedroom: 21° for 1h · Nursery: 20° for 45m". [nameOf] returns null for
 * a removed room.
 */
internal fun stepSummary(step: RoutineStep, nameOf: (String) -> String?): String {
    fun name(id: String) = nameOf(id) ?: "Missing room"
    return when (step) {
        is RoutineStep.Boost -> {
            fun value(targetC: Double, minutes: Int) = "${formatRoutineTemp(targetC)} for ${formatBoostDuration(minutes)}"
            groupedSummary(step.rooms.map { name(it.zoneId) to value(it.targetC, it.minutes) }, ": ")
        }
        is RoutineStep.SetMode -> {
            fun value(mode: String, targetC: Double?) =
                if (mode == HeatingMode.MANUAL && targetC != null) "Manual ${formatRoutineTemp(targetC)}" else modeLabel(mode)
            groupedSummary(step.rooms.map { name(it.zoneId) to value(it.mode, it.targetC) }, " → ")
        }
        is RoutineStep.CancelBoost -> step.zoneIds.joinToString { name(it) }
    }
}

/** "A, B: x · C: y", keeping the order each value first appears in. */
private fun groupedSummary(rooms: List<Pair<String, String>>, separator: String): String =
    rooms.groupBy({ it.second }, { it.first }).entries.joinToString(" · ") { (value, names) ->
        "${names.joinToString()}$separator$value"
    }

/** Snackbar text for a finished run, e.g. "Morning boost: 3 of 3 done" or "2 of 3 done — Nursery failed". */
internal fun runSummary(finished: RoutineRunner.RunState.Finished): String {
    val prefix = if (finished.isRetry) "${finished.routineName} retry" else finished.routineName
    val skipped = finished.results.size - finished.attempted
    val skippedNote = if (skipped > 0) " · $skipped skipped" else ""
    val failed = finished.failed
    return when {
        finished.attempted == 0 -> "$prefix: nothing to change$skippedNote"
        failed.isEmpty() -> "$prefix: ${finished.done} of ${finished.attempted} done$skippedNote"
        else -> "$prefix: ${finished.done} of ${finished.attempted} done — ${failed.map { it.zoneName }.distinct().joinToString()} failed"
    }
}
