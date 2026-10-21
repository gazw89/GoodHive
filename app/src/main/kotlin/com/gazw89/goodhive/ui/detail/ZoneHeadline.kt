package com.gazw89.goodhive.ui.detail

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.ZoneActivity
import com.gazw89.goodhive.util.ScheduleNowNextLater
import java.util.Locale
import kotlin.math.abs

/** Plain-language status for the top of the room screen. [supporting] is null when there is nothing useful to add. */
data class ZoneHeadline(val title: String, val supporting: String?)

/** Within this many degrees of target counts as "on target". */
private const val ON_TARGET_MARGIN_C = 0.1

/** Beyond this gap the wording moves from "a little above/below" to "above/below". */
private const val NOTABLE_GAP_C = 1.5

/**
 * Temperature formatting rule (used for every reading and target): one decimal place, but a value
 * that is whole at that precision drops the decimal — "19°", "20.2°". Never produces "null".
 */
internal fun formatTemp(c: Double): String {
    val oneDp = String.format(Locale.UK, "%.1f", c)
    return if (oneDp.endsWith(".0")) oneDp.dropLast(2) + "°" else "$oneDp°"
}

private fun join(vararg parts: String?): String? =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }

/**
 * Pure copy for the room headline. Only states what Hive reports: Heat on Demand is never inferred
 * from temperature, and a TRV's `working` is "valve calling for heat", which is why HEATING for a
 * TRV (boiler already on) and WAITING_FOR_BOILER are worded differently.
 */
fun zoneHeadline(
    zone: HeatingZone,
    activity: ZoneActivity?,
    schedule: ScheduleNowNextLater?,
): ZoneHeadline {
    val isTrv = zone.productType == ProductType.TRV_CONTROL
    val current = zone.currentTemperatureC
    val manual = zone.mode == HeatingMode.MANUAL
    val scheduled = zone.mode == HeatingMode.SCHEDULE
    val target = zone.targetTemperatureC ?: if (scheduled) schedule?.currentTargetC else null

    val roomVsTarget = roomVsTargetSentence(current, target, zone.mode)
    val manualHint = if (manual) "Switch to Schedule to let it take over again." else null
    val roomOnly = current?.let { "Room is ${formatTemp(it)}." }

    return when (activity) {
        ZoneActivity.OFF -> if (isTrv) {
            ZoneHeadline("Radiator is off", join(roomOnly, "The valve won't open until you turn it on."))
        } else {
            ZoneHeadline(
                "Heating is off",
                join(roomOnly, "Frost protection keeps it above ${formatTemp(zone.frostProtectionC)}."),
            )
        }

        ZoneActivity.OFFLINE -> ZoneHeadline(
            "Can't reach it",
            join(
                "${zone.name} isn't responding to Hive right now.",
                current?.let { "Last reading was ${formatTemp(it)}." },
            ),
        )

        ZoneActivity.IDLE -> {
            val onOrAbove = current != null && target != null && current >= target - ON_TARGET_MARGIN_C
            val title = when {
                onOrAbove -> "Warm enough"
                isTrv -> "Valve is resting"
                else -> "Not heating right now"
            }
            ZoneHeadline(title, join(roomVsTarget, manualHint))
        }

        ZoneActivity.HEATING -> ZoneHeadline("Heating up", join(roomVsTarget, manualHint))

        ZoneActivity.WAITING_FOR_BOILER -> ZoneHeadline(
            "Waiting for the boiler",
            join(
                if (zone.heatOnDemand == "ENABLED") {
                    "The valve wants heat. Heat on Demand usually starts within ~4 min."
                } else {
                    "The valve wants heat, but the boiler is off right now."
                },
                roomVsTarget,
            ),
        )

        ZoneActivity.BOOST -> ZoneHeadline(
            "Boosting",
            join(zone.boostMinutesLeft?.let { "$it min left." } ?: "Boost is running.", roomVsTarget),
        )

        ZoneActivity.HOD_BOOST -> ZoneHeadline(
            "Heat on Demand boost",
            join("A radiator valve asked for heat.", zone.boostMinutesLeft?.let { "$it min left." }, roomOnly),
        )

        ZoneActivity.HOD_SOURCE -> ZoneHeadline(
            "Firing the boiler",
            join("Heat on Demand: this valve asked for heat.", roomVsTarget),
        )

        null -> ZoneHeadline(
            current?.let { "Room is ${formatTemp(it)}" } ?: "Checking status",
            join(target?.let { "Target is ${formatTemp(it)}." }, manualHint),
        )
    }
}

private fun roomVsTargetSentence(current: Double?, target: Double?, mode: String): String? {
    val manual = mode == HeatingMode.MANUAL
    val source = when (mode) {
        HeatingMode.SCHEDULE -> "the schedule asks for"
        HeatingMode.MANUAL -> "you set"
        else -> "set"
    }
    if (target == null) return current?.let { "Room is ${formatTemp(it)}." }
    val targetText = formatTemp(target)
    if (current == null) return if (manual) "Held at $targetText manually." else "Target is $targetText."
    val tail = if (manual) "held at $targetText manually" else "the $targetText $source"
    val diff = current - target
    val gap = if (abs(diff) < NOTABLE_GAP_C) "a little " else ""
    return when {
        abs(diff) <= ON_TARGET_MARGIN_C -> "Room is ${formatTemp(current)}, right on $tail."
        diff > 0 -> "Room is ${formatTemp(current)}, ${gap}above $tail."
        else -> "Room is ${formatTemp(current)}, ${gap}below $tail."
    }
}
