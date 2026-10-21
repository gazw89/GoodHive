package com.gazw89.goodhive.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Raw shapes from GET /1.0/nodes/all — deliberately loose (props/state as JsonObject)
 * since the real payload varies by device type (heating, trvcontrol, light, sensor, ...)
 * and phase 1 only needs to interpret the heating-relevant fields. Verified against a
 * real account dump on 2026-08-07 (see plan doc) rather than guessed from docs.
 */
@Serializable
data class HiveNodesResponse(
    // Omission is an invalid/partial response, not authoritative evidence that devices vanished.
    val products: List<HiveNode>,
    val devices: List<HiveNode> = emptyList(),
    val actions: List<HiveNode> = emptyList(),
)

@Serializable
data class HiveNode(
    val id: String,
    val type: String,
    val parent: String? = null,
    val props: JsonObject = JsonObject(emptyMap()),
    val state: JsonObject = JsonObject(emptyMap()),
)

/** GET /1.0/history/heating/{id} — see [HiveApiService.getTemperatureHistory]. */
@Serializable
data class HiveHistoryResponse(val data: List<HiveHistoryPoint> = emptyList())

/** [date] is epoch millis serialized as a string; both fields are optional to tolerate partial points. */
@Serializable
data class HiveHistoryPoint(
    val date: String? = null,
    val temperature: Double? = null,
)
