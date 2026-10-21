package com.gazw89.goodhive.data.network

import com.gazw89.goodhive.data.model.ProductType
import kotlinx.serialization.json.JsonObject

/**
 * Debug-only diagnostics for the unconfirmed live-activity fields documented in
 * docs/hive-domain-reference.md §5 (`props.working`, thermostat `props.autoBoost`, TRV `props.trvs`).
 * Emits a fixed whitelist of heating fields per zone — never tokens, account or address data —
 * so a real Heat on Demand event can be captured with `adb logcat -s HiveProbe`.
 */
fun HiveNodesResponse.activityProbeLines(): List<String> =
    products
        .filter { it.type == ProductType.HEATING || it.type == ProductType.TRV_CONTROL }
        .map { product ->
            val fields = listOf(
                "name" to product.state["name"],
                "mode" to product.state["mode"],
                "temp" to product.props["temperature"],
                "target" to product.state["target"],
                "working" to product.props["working"],
                "props.autoBoost" to product.props["autoBoost"],
                "state.autoBoost" to product.state["autoBoost"],
                "props.zone" to product.props["zone"],
                "state.zone" to product.state["zone"],
                "props.trvs" to product.props["trvs"],
                "state.boost" to product.state["boost"],
                "online" to product.props["online"],
            )
            val physicalDevices = devices.filter { device ->
                device.state["control"] == kotlinx.serialization.json.JsonPrimitive(product.id) ||
                    (product.props["trvs"] as? kotlinx.serialization.json.JsonArray)
                        ?.contains(kotlinx.serialization.json.JsonPrimitive(device.id)) == true
            }
            "${product.type} ${product.id}: " +
                fields.joinToString(", ") { (key, value) -> "$key=$value" } +
                " | deviceHealth=[" + physicalDevices.joinToString { device ->
                    "online=${device.props["online"]}, battery=${device.props["battery"]}, batteryLevel=${device.props["batteryLevel"]}"
                } + "]" +
                " | propsKeys=[${product.props.keySummary()}] stateKeys=[${product.state.keySummary()}]"
        }

/** Every top-level key Hive sent for a node, to spot fields we don't know about yet. */
fun JsonObject.keySummary(): String = keys.sorted().joinToString(",")
