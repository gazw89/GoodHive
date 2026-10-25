package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.canControl
import com.gazw89.goodhive.data.model.householdHeating
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.data.model.routines.snapHalfDegree
import com.gazw89.goodhive.util.scheduleNowNextLater
import java.time.LocalDateTime
import kotlinx.serialization.json.JsonObject

/** What one room in a routine step resolves to, given that room's state right before it is sent. */
sealed interface PlannedAction {
    data class Send(
        val zoneId: String,
        val zoneName: String,
        val productType: String,
        val description: String,
        val payload: JsonObject,
        val expectedState: Map<String, String>,
    ) : PlannedAction

    data class Skip(val reason: String) : PlannedAction
}

/**
 * Turns one room of a routine step into a write, using the same payloads and rules as the room screen
 * (`DeviceDetailViewModel`). [zones] is the displayed state (pending intent applied), read just before
 * this room's turn, so an earlier step on the same room is already reflected.
 */
fun planRoutineAction(
    step: RoutineStep,
    zoneId: String,
    zones: List<HeatingZone>,
    now: LocalDateTime? = null,
): PlannedAction {
    val zone = zones.firstOrNull { it.id == zoneId } ?: return PlannedAction.Skip("Room not found")
    if (!zone.canControl) return PlannedAction.Skip("${zone.name} is offline")
    fun send(description: String, payload: JsonObject, expected: Map<String, String>) =
        PlannedAction.Send(zone.id, zone.name, zone.productType, description, payload, expected)
    fun slotTarget() = (if (now == null) zone.scheduleNowNextLater() else zone.scheduleNowNextLater(now))?.currentTargetC

    return when (step) {
        is RoutineStep.Boost -> {
            val room = step.rooms.first { it.zoneId == zoneId }
            val targetC = clampTarget(room.targetC, zone)
            val minutes = room.minutes.coerceIn(BOOST_MIN_MINUTES, BOOST_MAX_MINUTES)
            send(
                "Boost ${zone.name} to ${"%.1f".format(targetC)}°C for $minutes min",
                buildBoostPayload(minutes, targetC),
                expectedStateForBoost(targetC),
            )
        }
        is RoutineStep.SetMode -> {
            val room = step.rooms.first { it.zoneId == zoneId }
            when (room.mode) {
                HeatingMode.MANUAL -> {
                    val targetC = clampTarget(room.targetC ?: zone.targetTemperatureC ?: DEFAULT_MANUAL_C, zone)
                    send(
                        "Set ${zone.name} to MANUAL at ${"%.1f".format(targetC)}°C",
                        buildModePayload(HeatingMode.MANUAL, targetC),
                        expectedStateForMode(HeatingMode.MANUAL, targetC),
                    )
                }
                HeatingMode.OFF -> send(
                    "Set ${zone.name} to OFF",
                    buildModePayload(HeatingMode.OFF, offTargetC = zone.frostProtectionC),
                    expectedStateForMode(HeatingMode.OFF),
                )
                HeatingMode.SCHEDULE -> {
                    val scheduleTarget = slotTarget()
                    send(
                        "Set ${zone.name} to SCHEDULE",
                        buildModePayload(HeatingMode.SCHEDULE, scheduleTargetC = scheduleTarget),
                        expectedStateForMode(HeatingMode.SCHEDULE, scheduleTargetC = scheduleTarget),
                    )
                }
                else -> PlannedAction.Skip("Unknown mode ${room.mode}")
            }
        }
        is RoutineStep.CancelBoost -> {
            // Heat on Demand also sets the thermostat to BOOST; only a user boost is cancelled here.
            val hodBoost = zone.productType == ProductType.HEATING && householdHeating(zones).hodActive
            if (zone.mode != HeatingMode.BOOST || hodBoost) return PlannedAction.Skip("${zone.name} has no boost running")
            val scheduleTarget = slotTarget()
            send(
                "Cancel boost on ${zone.name}",
                buildCancelBoostPayload(zone.previousMode, zone.previousTargetC, scheduleTarget),
                expectedStateForCancelBoost(zone.previousMode, zone.previousTargetC, scheduleTarget),
            )
        }
    }
}

private fun clampTarget(valueC: Double, zone: HeatingZone): Double =
    snapHalfDegree(valueC.coerceIn(zone.minTemperatureC, zone.maxTemperatureC)).coerceIn(zone.minTemperatureC, zone.maxTemperatureC)

private const val BOOST_MIN_MINUTES = 15
private const val BOOST_MAX_MINUTES = 360
private const val DEFAULT_MANUAL_C = 20.0
