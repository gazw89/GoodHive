package com.gazw89.goodhive.data.model

/**
 * What a zone is *doing* right now, derived from Hive's own flags (never from HoD inference by
 * temperature alone). See docs/heat-on-demand-and-status-ui.md §6 and
 * docs/hive-domain-reference.md §5.
 */
enum class ZoneActivity {
    /** Mode OFF (or HOLIDAY on the thermostat) — nothing will heat. */
    OFF,
    /** Device unreachable; its other fields are stale. */
    OFFLINE,
    /** Online, on, and not calling for heat. */
    IDLE,
    /** A TRV is calling for heat but the boiler is not running (Heat on Demand off or thermostat OFF). */
    WAITING_FOR_BOILER,
    /** Calling for heat and (for a TRV) the boiler is on, or the thermostat itself is firing. */
    HEATING,
    /** A user-started boost (`mode=BOOST` without an active Heat on Demand event). */
    BOOST,
    /** The thermostat is boosted by Heat on Demand — NOT a user boost even though its mode is BOOST. */
    HOD_BOOST,
    /** A TRV that triggered the running Heat on Demand boost (listed in `props.autoBoost.trvs`). */
    HOD_SOURCE,
}

/**
 * Household-wide heating state, read from the primary thermostat.
 *
 * @property boilerOn thermostat `props.working`; null when there is no thermostat (unknown).
 * @property hodActive `props.autoBoost.active == true` on the thermostat.
 * @property hodSourceIds TRV zone ids that triggered the running HoD boost; empty unless [hodActive].
 */
data class HouseholdHeating(
    val boilerOn: Boolean?,
    val hodActive: Boolean,
    val hodSourceIds: Set<String>,
)

/** A temperature this far (°C) below target counts as "calling" when Hive gives no `working` flag. */
private const val CALLING_MARGIN_C = 0.1

/** Fallback only: Hive's `props.working` is authoritative whenever present. */
private fun HeatingZone.callingByTemperature(): Boolean {
    val current = currentTemperatureC ?: return false
    val target = targetTemperatureC ?: return false
    return current < target - CALLING_MARGIN_C
}

private fun HeatingZone.isOffMode(): Boolean =
    mode == HeatingMode.OFF || (productType == ProductType.HEATING && mode == "HOLIDAY")

/**
 * Derives [HouseholdHeating]. Assumes one primary thermostat (productType [ProductType.HEATING]);
 * if several are present the first is used.
 *
 * Boiler on = thermostat `working`. When Hive omits it, fall back to the domain rules
 * (hive-domain-reference §5): thermostat OFF/HOLIDAY → off; else thermostat's own demand → on;
 * else any Heat-on-Demand-ENABLED TRV with demand → on; else off. No thermostat → unknown (null).
 */
fun householdHeating(zones: List<HeatingZone>): HouseholdHeating {
    val thermostat = zones.firstOrNull { it.productType == ProductType.HEATING && it.reportedByHive }
        ?: return HouseholdHeating(boilerOn = null, hodActive = false, hodSourceIds = emptySet())
    if (!thermostat.canControl) {
        return HouseholdHeating(boilerOn = null, hodActive = false, hodSourceIds = emptySet())
    }
    val hodActive = thermostat.autoBoostActive == true
    val boilerOn = thermostat.working ?: when {
        thermostat.isOffMode() -> false
        thermostat.callingByTemperature() -> true
        else -> zones.any {
            it.canControl && it.productType == ProductType.TRV_CONTROL && it.heatOnDemand == "ENABLED" &&
                (it.working ?: it.callingByTemperature())
        }
    }
    return HouseholdHeating(
        boilerOn = boilerOn,
        hodActive = hodActive,
        hodSourceIds = if (hodActive) thermostat.autoBoostTrvIds.toSet() else emptySet(),
    )
}

/**
 * Activity of one zone given [household], first match wins:
 * offline → off → thermostat under HoD ([ZoneActivity.HOD_BOOST], checked before BOOST because HoD
 * sets `mode=BOOST`) → HoD-source TRV → user boost → calling (`working`, else the temperature
 * heuristic only when `working` is null) → TRV calling with boiler known-off = waiting → heating → idle.
 */
fun HeatingZone.activity(household: HouseholdHeating): ZoneActivity {
    val isThermostat = productType == ProductType.HEATING
    return when {
        !canControl -> ZoneActivity.OFFLINE
        isOffMode() -> ZoneActivity.OFF
        isThermostat && household.hodActive -> ZoneActivity.HOD_BOOST
        !isThermostat && id in household.hodSourceIds -> ZoneActivity.HOD_SOURCE
        mode == HeatingMode.BOOST -> ZoneActivity.BOOST
        else -> {
            val calling = working ?: callingByTemperature()
            when {
                !calling -> ZoneActivity.IDLE
                !isThermostat && household.boilerOn == false -> ZoneActivity.WAITING_FOR_BOILER
                else -> ZoneActivity.HEATING
            }
        }
    }
}

/**
 * A TRV that is calling for heat while the boiler is known to be off, so nothing will warm it until
 * the boiler fires (Heat on Demand off, or the thermostat not calling). Independent of mode: a
 * boosted TRV is still waiting, even though its [ZoneActivity] is [ZoneActivity.BOOST]. A target above
 * the room temperature is not enough: this needs Hive's own `working` flag (temperature heuristic
 * only when Hive omits it).
 */
fun HeatingZone.isWaitingForBoiler(household: HouseholdHeating): Boolean =
    productType != ProductType.HEATING &&
        canControl &&
        !isOffMode() &&
        household.boilerOn == false &&
        id !in household.hodSourceIds &&
        (working ?: callingByTemperature())

/** Number of rooms [isWaitingForBoiler], boosted or not. */
fun roomsWaitingForBoiler(zones: List<HeatingZone>, household: HouseholdHeating): Int =
    zones.count { it.isWaitingForBoiler(household) }

/** Activity per zone, keyed by [HeatingZone.id]. */
fun deriveActivities(zones: List<HeatingZone>): Map<String, ZoneActivity> {
    val household = householdHeating(zones)
    return zones.associate { it.id to it.activity(household) }
}
