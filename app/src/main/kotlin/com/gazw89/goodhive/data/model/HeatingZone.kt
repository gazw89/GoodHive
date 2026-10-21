package com.gazw89.goodhive.data.model

/** Hive's own mode names — kept as the raw strings from the API, not re-mapped, to avoid drift. */
object HeatingMode {
    const val SCHEDULE = "SCHEDULE"
    const val MANUAL = "MANUAL"
    const val OFF = "OFF"
    const val BOOST = "BOOST"
}

/** Hive's own product type names — see [HeatingZone.productType]. */
object ProductType {
    const val HEATING = "heating"
    const val TRV_CONTROL = "trvcontrol"
}

/**
 * A single controllable heating zone: either the central thermostat/boiler relay
 * (product type "heating") or an individually-scheduled TRV (product type "trvcontrol").
 * Display name always comes from `state.name` — NOT `props.zoneName`, which can read
 * "Thermostat 1" for a TRV that's relay-linked to the boiler (confirmed against a real
 * account: two different TRVs both had zoneName "Thermostat 1").
 */
data class HeatingZone(
    val id: String,
    val productType: String,
    val name: String,
    /** False if either the cloud product or any linked physical device explicitly reports offline. */
    val online: Boolean,
    val currentTemperatureC: Double?,
    val targetTemperatureC: Double?,
    val mode: String,
    val frostProtectionC: Double,
    val maxEventsPerDay: Int,
    val minTemperatureC: Double = 5.0,
    val maxTemperatureC: Double = 32.0,
    val schedule: WeekSchedule,
    /**
     * Hive's "Heat on demand" / TRV Auto Boost setting — raw "ENABLED"/"DISABLED" string
     * from `state.autoBoost`, confirmed against a real account. Only meaningful for
     * [ProductType.TRV_CONTROL]; always null on the main thermostat. Hive omits
     * `state.autoBoost` when the setting is disabled, so null is an authoritative OFF value,
     * not an unknown value to be replaced from cache. Support is represented separately by
     * [heatOnDemandAvailable].
     */
    val heatOnDemand: String?,
    /**
     * True only for [ProductType.TRV_CONTROL] nodes whose `props.capabilities` includes
     * "TRV_AUTO_BOOST_READY" (or the fully-active "TRV_AUTO_BOOST"). Confirmed against a
     * real account: every TRV reports *_READY even when `heatOnDemand` is null — the
     * hardware/firmware supports the feature even while it is disabled for that TRV.
     * The UI should offer the toggle whenever this is true, not only when
     * [heatOnDemand] is already non-null. Always false for the main thermostat.
     */
    val heatOnDemandAvailable: Boolean = false,
    /**
     * Hive's zone identity used for Heat on Demand linking:
     * - primary thermostat: `props.zone`
     * - TRV with HoD ENABLED: `state.zone` (the thermostat zone it may boost)
     * - TRV with HoD off: null (Hive clears `state.zone` when HoD is disabled)
     */
    val hiveZoneId: String? = null,
    /**
     * TRV/device battery, 0-100. Null when this zone has no distinct battery-powered device
     * report (e.g. the mains-powered thermostat) or Hive omitted the field. Sourced from the
     * `devices` collection via `state.control` or the product's `props.trvs` device ids,
     * not `props` on this zone's own product node. Lowest valid percent wins for multiple devices —
     * see [com.gazw89.goodhive.data.network.toHeatingZones].
     */
    val batteryLevel: Int? = null,
    /**
     * `props.working`, present on both [ProductType.HEATING] and [ProductType.TRV_CONTROL]
     * (confirmed live 2026-10-15). On the thermostat it is "the boiler is firing" (true while a
     * Heat on Demand boost runs, false when idle or OFF). On a TRV it means "this valve is
     * calling for heat", **not** "receiving heat" — a HoD-off TRV showed true while the thermostat
     * was OFF. Null when Hive omitted it; see `HeatingActivity.kt` for how it is combined.
     */
    val working: Boolean? = null,
    /**
     * `props.autoBoost.active` — true while a Heat on Demand boost is running. This is the
     * `props.autoBoost` **object**, distinct from the TRV's `state.autoBoost` string that feeds
     * [heatOnDemand]. Present on the thermostat and on HoD-enabled TRVs; null when absent.
     * A thermostat with `mode=BOOST` and this true is a HoD boost, not a user boost.
     */
    val autoBoostActive: Boolean? = null,
    /**
     * `props.autoBoost.trvs` — `trvcontrol` product ids that triggered the HoD boost, equal to
     * [HeatingZone.id] of those TRVs. Can still list a trigger right after a boost ends, so key
     * off [autoBoostActive], not this list. Empty when absent.
     */
    val autoBoostTrvIds: List<String> = emptyList(),
    /**
     * Ids to query `history/heating/{id}` with: heating -> listOf(product id); trvcontrol ->
     * the physical TRV device ids from `props.trvs` (the product id 404s). Empty when unknown.
     */
    val historySourceIds: List<String> = emptyList(),
    /**
     * `props.previous.mode` — the mode Hive will restore when a boost ends (e.g. "OFF",
     * "MANUAL", "SCHEDULE"). Null when `props.previous` is missing or `{}`.
     */
    val previousMode: String? = null,
    /** `props.previous.target` — the setpoint paired with [previousMode], when Hive reports one. */
    val previousTargetC: Double? = null,
    /** `state.boost` — whole minutes of an active boost remaining; null when not boosting. */
    val boostMinutesLeft: Int? = null,
    /** False only when a complete refresh omitted this cached product; retained until manual removal. */
    val reportedByHive: Boolean = true,
)

/** Monday-first week, matching the raw API's day keys. */
data class WeekSchedule(val days: Map<String, List<ScheduleSlot>>) {
    companion object {
        val DAY_KEYS = listOf(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        )
    }
}

/**
 * One breakpoint in a day's schedule: holds [targetTemperatureC] from [startMinutes]
 * (minutes since midnight, 0-1439) until the next slot. Confirmed exact shape against a
 * real account: `{"start": 360, "value": {"target": 19}}`.
 */
data class ScheduleSlot(
    val startMinutes: Int,
    val targetTemperatureC: Double,
)
