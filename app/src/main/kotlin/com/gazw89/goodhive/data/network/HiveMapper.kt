package com.gazw89.goodhive.data.network

import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val HEATING_PRODUCT_TYPES = setOf("heating", "trvcontrol")

/** Maps raw /nodes/all products to [HeatingZone]s, skipping non-heating device types (lights, plugs, sensors). */
fun HiveNodesResponse.toHeatingZones(): List<HeatingZone> {
    val devicesByZoneId = devices.groupBy { it.state["control"].primitiveOrNull()?.contentOrNull() }
    return products.filter { it.type in HEATING_PRODUCT_TYPES }
        .mapNotNull { product ->
            // state.control is the owning product, not the physical hub (device.parent).
            // TRVs also expose their physical device ids via props.trvs.
            val trvIds = (product.props["trvs"] as? JsonArray)
                ?.mapNotNull { it.primitiveOrNull()?.contentOrNull() }.orEmpty()
            val linkedDevices = (devicesByZoneId[product.id].orEmpty() + devices.filter { it.id in trvIds })
                .distinctBy { it.id }
            product.toHeatingZoneOrNull(linkedDevices, trvIds)
        }
}

private fun HiveNode.batteryPercent(): Int? =
    sequenceOf("battery", "batteryLevel")
        .mapNotNull { props[it].primitiveOrNull()?.intOrNull?.takeIf { level -> level in 0..100 } }
        .firstOrNull()

private val HEAT_ON_DEMAND_CAPABILITIES = setOf("TRV_AUTO_BOOST_READY", "TRV_AUTO_BOOST")

private fun HiveNode.toHeatingZoneOrNull(devices: List<HiveNode>, trvIds: List<String> = emptyList()): HeatingZone? {
    val name = state["name"]?.jsonPrimitive?.contentOrNull() ?: return null
    val schedule = state["schedule"]?.jsonObject?.toWeekSchedule() ?: WeekSchedule(emptyMap())
    val capabilities = props["capabilities"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull() }
        ?.toSet()
        ?: emptySet()
    val isTrv = type == ProductType.TRV_CONTROL
    val isHeating = type == ProductType.HEATING
    val hodAvailable = isTrv && capabilities.any { it in HEAT_ON_DEMAND_CAPABILITIES }
    // HoD enable links a TRV to the primary thermostat's zone id. Confirmed: heating
    // exposes it as props.zone; a HoD-ENABLED TRV mirrors it as state.zone (cleared when off).
    val hiveZoneId = when {
        isHeating -> props["zone"]?.jsonPrimitive?.contentOrNull()
        isTrv -> state["zone"]?.jsonPrimitive?.contentOrNull()
        else -> null
    }
    // New activity fields are read defensively (as? / OrNull): a wrong-typed or missing value
    // must yield null/empty, never throw and drop the whole zone.
    val autoBoost = props["autoBoost"] as? JsonObject
    val previous = props["previous"] as? JsonObject

    return HeatingZone(
        id = id,
        productType = type,
        name = name,
        // The cloud product can remain online after its physical valve has stopped responding.
        // Explicit offline evidence from either source wins over a stale online flag.
        online = props["online"].primitiveOrNull()?.booleanOrNull != false &&
            devices.none { it.props["online"].primitiveOrNull()?.booleanOrNull == false },
        currentTemperatureC = props["temperature"]?.jsonPrimitive?.doubleOrNull,
        targetTemperatureC = state["target"]?.jsonPrimitive?.doubleOrNull,
        mode = state["mode"]?.jsonPrimitive?.contentOrNull() ?: "OFF",
        frostProtectionC = state["frostProtection"]?.jsonPrimitive?.doubleOrNull ?: 7.0,
        maxEventsPerDay = props["maxEvents"]?.jsonPrimitive?.intOrNull ?: 6,
        schedule = schedule,
        // HoD / autoBoost is TRV-only; ignore any stray state on heating nodes.
        heatOnDemand = if (isTrv) state["autoBoost"]?.jsonPrimitive?.contentOrNull() else null,
        heatOnDemandAvailable = hodAvailable,
        hiveZoneId = hiveZoneId,
        batteryLevel = devices.mapNotNull { it.batteryPercent() }.minOrNull(),
        working = props["working"].primitiveOrNull()?.booleanOrNull,
        autoBoostActive = autoBoost?.get("active").primitiveOrNull()?.booleanOrNull,
        historySourceIds = when {
            isHeating -> listOf(id)
            isTrv -> trvIds
            else -> emptyList()
        },
        autoBoostTrvIds = (autoBoost?.get("trvs") as? JsonArray)
            ?.mapNotNull { it.primitiveOrNull()?.takeIf { p -> p.isString }?.content }
            .orEmpty(),
        previousMode = previous?.get("mode").primitiveOrNull()?.contentOrNull()?.takeIf { it.isNotBlank() },
        previousTargetC = previous?.get("target").primitiveOrNull()?.doubleOrNull,
        // Hive sends whole minutes, but tolerate 30.0 as well as 30.
        boostMinutesLeft = state["boost"].primitiveOrNull()?.doubleOrNull?.toInt(),
    )
}

internal fun JsonObject.toWeekSchedule(): WeekSchedule {
    val days = WeekSchedule.DAY_KEYS.associateWith { day ->
        this[day]?.jsonArray?.mapNotNull { slotElement ->
            val slot = slotElement.jsonObject
            val start = slot["start"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val target = slot["value"]?.jsonObject?.get("target")?.jsonPrimitive?.doubleOrNull
                ?: return@mapNotNull null
            ScheduleSlot(startMinutes = start, targetTemperatureC = target)
        }.orEmpty().sortedBy { it.startMinutes }
    }
    return WeekSchedule(days)
}

/** The element as a non-null [JsonPrimitive], or null if missing, JSON null, an object or an array. */
private fun JsonElement?.primitiveOrNull(): JsonPrimitive? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

private fun JsonPrimitive.contentOrNull(): String? =
    if (this.isString || this.content.isNotEmpty()) this.content else null
