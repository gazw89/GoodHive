package com.gazw89.goodhive.data.model.routines

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user-built list of heating actions run with one tap. Values are fixed when saved: a run always
 * sends exactly what is stored, never a change relative to the room's state at run time.
 */
data class Routine(
    val id: Long = 0,
    val name: String,
    val iconKey: String = RoutineIcons.DEFAULT,
    val position: Int = 0,
    val steps: List<RoutineStep>,
)

/** One action applied to one or more rooms (Hive zones: the thermostat and any TRV). */
@Serializable
sealed interface RoutineStep {
    val zoneIds: List<String>

    @Serializable
    @SerialName("boost")
    data class Boost(val rooms: List<BoostRoom>) : RoutineStep {
        override val zoneIds get() = rooms.map { it.zoneId }
    }

    @Serializable
    @SerialName("setMode")
    data class SetMode(val rooms: List<ModeRoom>) : RoutineStep {
        override val zoneIds get() = rooms.map { it.zoneId }
    }

    @Serializable
    @SerialName("cancelBoost")
    data class CancelBoost(override val zoneIds: List<String>) : RoutineStep
}

@Serializable
data class BoostRoom(val zoneId: String, val targetC: Double, val minutes: Int)

/** [targetC] is only used for [HeatingMode.MANUAL]. */
@Serializable
data class ModeRoom(val zoneId: String, val mode: String, val targetC: Double? = null)

/** Icon keys stored with a routine; the UI maps each to a Material icon. Unknown keys fall back to [DEFAULT]. */
object RoutineIcons {
    const val DEFAULT = "bolt"
    val ALL = listOf("sunrise", "bolt", "home", "away", "bath", "bedtime", "baby", "snow")
}

/** A non-blocking editor hint. [stepIndex] is null for a routine-level warning. */
data class RoutineWarning(val stepIndex: Int?, val message: String)

/**
 * Editor warnings. [nameOf] returns a room's display name, or null when the room is no longer on the
 * account (its actions are skipped at run time).
 */
fun Routine.validate(nameOf: (String) -> String?): List<RoutineWarning> = buildList {
    if (steps.isEmpty()) add(RoutineWarning(null, "Add at least one step"))
    val boostedBefore = mutableSetOf<String>()
    steps.forEachIndexed { index, step ->
        val missing = step.zoneIds.count { nameOf(it) == null }
        if (missing > 0) {
            add(RoutineWarning(index, if (missing == 1) "Room not found" else "$missing rooms not found"))
        }
        if (step.zoneIds.isEmpty()) add(RoutineWarning(index, "No rooms chosen"))
        if (step is RoutineStep.SetMode) {
            val replaced = step.zoneIds.filter { it in boostedBefore }.mapNotNull(nameOf)
            if (replaced.isNotEmpty()) {
                add(RoutineWarning(index, "Replaces the earlier boost in ${replaced.joinToString()}"))
            }
        }
        if (step is RoutineStep.Boost) boostedBefore += step.zoneIds
        if (step is RoutineStep.CancelBoost) boostedBefore -= step.zoneIds.toSet()
    }
}

const val MORNING_BOOST_NAME = "Morning boost"
const val MORNING_BOOST_TARGET_C = 21.0
const val MORNING_BOOST_MINUTES = 60

/**
 * The routine created once for a new account: boost the thermostat (so the boiler fires even with
 * Heat on Demand off) and every TRV for an hour, ahead of the schedule. People untick the rooms they
 * don't need. Rooms missing from Hive's latest snapshot are left out.
 */
fun morningBoostRoutine(zones: List<HeatingZone>): Routine? {
    val rooms = zones.filter { it.reportedByHive }
        .sortedBy { if (it.productType == ProductType.HEATING) 0 else 1 }
        .map { zone ->
            BoostRoom(zone.id, snapHalfDegree(MORNING_BOOST_TARGET_C.coerceIn(zone.minTemperatureC, zone.maxTemperatureC)), MORNING_BOOST_MINUTES)
        }
    if (rooms.isEmpty()) return null
    return Routine(name = MORNING_BOOST_NAME, iconKey = "sunrise", steps = listOf(RoutineStep.Boost(rooms)))
}

internal fun snapHalfDegree(valueC: Double): Double = (valueC * 2).roundToInt() / 2.0
