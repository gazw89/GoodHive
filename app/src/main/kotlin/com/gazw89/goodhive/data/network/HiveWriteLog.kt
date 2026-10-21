package com.gazw89.goodhive.data.network

import com.gazw89.goodhive.util.GoodHiveTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Beekeeper host. Cognito and SSO are different hosts and are not write-logged. */
const val HIVE_BEEKEEPER_HOST = "beekeeper.hivehome.com"

/** Per-body cap before a write is stored. Heating payloads are far smaller. */
const val HIVE_WRITE_BODY_CAP = 64 * 1024

private const val REDACTED = "[redacted]"
private const val TRUNCATED = "\n… truncated"

private val compactJson = Json { prettyPrint = false }
private val prettyJson = Json { prettyPrint = true }

private val sensitiveKey = Regex(
    "(?i)password|token|secret|session|authorization|address|postcode|latitude|longitude",
)
private val authorizationHeader = Regex("""(?i)(?<!")authorization\s*:\s*\S+""")
private val cookieHeader = Regex("""(?i)(?<!")(?:set-)?cookie\s*:\s*\S+""")
private val bearer = Regex("""(?i)bearer\s+\S+""")
private val jwt = Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")

private val clockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayClockFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
private val fullClockFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

enum class HiveWriteKind {
    Temperature,
    Mode,
    Boost,
    Schedule,
    HeatOnDemand,
}

enum class HiveWriteSort { Newest, Oldest }

enum class HiveWriteResultFilter { All, Succeeded, Failed }

enum class HiveWriteKindFilter { All, Temperature, Mode, Boost, Schedule, HeatOnDemand }

/** True only for a Beekeeper POST. GET polls and any other host stay out of the log. */
fun shouldLogHiveWrite(method: String, host: String): Boolean =
    method.equals("POST", ignoreCase = true) && host.equals(HIVE_BEEKEEPER_HOST, ignoreCase = true)

/** Path and query, without scheme, host, or the `/1.0/` prefix. */
fun hiveRequestPath(encodedPath: String, encodedQuery: String?): String {
    val path = encodedPath.trimStart('/').removePrefix("1.0/")
    return if (encodedQuery.isNullOrEmpty()) path else "$path?$encodedQuery"
}

/** Product id from `nodes/{type}/{id}`. Null when the path is not a node write. */
fun zoneIdFromHivePath(path: String): String? {
    val parts = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
    val nodes = parts.indexOf("nodes")
    return if (nodes >= 0 && nodes + 2 < parts.size) parts[nodes + 2] else null
}

/**
 * Drops tokens, auth headers, and address-like JSON values. A body with none of those is returned
 * unchanged apart from the length cap.
 */
fun sanitizeHiveBody(raw: String): String {
    val clipped = if (raw.length <= HIVE_WRITE_BODY_CAP) raw else raw.take(HIVE_WRITE_BODY_CAP) + TRUNCATED
    val text = clipped
        .replace(authorizationHeader, "authorization: $REDACTED")
        .replace(cookieHeader, "cookie: $REDACTED")
        .replace(bearer, REDACTED)
        .replace(jwt, REDACTED)
    val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return text
    var changed = false
    val redacted = redactJson(element) { changed = true }
    return if (changed) compactJson.encodeToString(JsonElement.serializer(), redacted) else text
}

fun prettyHiveBody(raw: String): String {
    if (raw.isBlank()) return ""
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
    return prettyJson.encodeToString(JsonElement.serializer(), element)
}

/**
 * Change type used by the Settings filter. When a body carries more than one field, Heat on Demand
 * wins, then schedule, boost, any other mode, then a target-only temperature write.
 */
fun classifyHiveWrite(requestBody: String): HiveWriteKind? {
    val obj = requestObject(requestBody) ?: return null
    val mode = obj["mode"]?.jsonPrimitive?.contentOrNull
    val zoneOnly = "zone" in obj && obj.keys.all { it == "zone" }
    return when {
        "autoBoost" in obj || zoneOnly -> HiveWriteKind.HeatOnDemand
        "schedule" in obj -> HiveWriteKind.Schedule
        mode.equals("BOOST", ignoreCase = true) -> HiveWriteKind.Boost
        "mode" in obj -> HiveWriteKind.Mode
        "target" in obj -> HiveWriteKind.Temperature
        else -> null
    }
}

/** Short list label, such as "Manual 21°" or "Heat on Demand on". */
fun hiveWriteSummary(requestBody: String): String {
    val obj = requestObject(requestBody)
    return when (classifyHiveWrite(requestBody)) {
        HiveWriteKind.HeatOnDemand ->
            if (obj?.get("autoBoost")?.jsonPrimitive?.contentOrNull.equals("ENABLED", ignoreCase = true)) {
                "Heat on Demand on"
            } else {
                "Heat on Demand off"
            }
        HiveWriteKind.Schedule -> "Schedule"
        HiveWriteKind.Boost -> boostSummary(obj)
        HiveWriteKind.Mode -> modeSummary(obj)
        HiveWriteKind.Temperature -> obj?.get("target")?.jsonPrimitive?.doubleOrNull?.let(::formatDegrees) ?: "Temperature"
        null -> "Write"
    }
}

fun HiveWriteKindFilter.matches(kind: HiveWriteKind?): Boolean = when (this) {
    HiveWriteKindFilter.All -> true
    HiveWriteKindFilter.Temperature -> kind == HiveWriteKind.Temperature
    HiveWriteKindFilter.Mode -> kind == HiveWriteKind.Mode
    HiveWriteKindFilter.Boost -> kind == HiveWriteKind.Boost
    HiveWriteKindFilter.Schedule -> kind == HiveWriteKind.Schedule
    HiveWriteKindFilter.HeatOnDemand -> kind == HiveWriteKind.HeatOnDemand
}

/** 2xx is success. A missing code (no HTTP response) is a failure. */
fun hiveWriteSucceeded(responseCode: Int?): Boolean = responseCode != null && responseCode in 200..299

fun HiveWriteResultFilter.matches(responseCode: Int?): Boolean = when (this) {
    HiveWriteResultFilter.All -> true
    HiveWriteResultFilter.Succeeded -> hiveWriteSucceeded(responseCode)
    HiveWriteResultFilter.Failed -> !hiveWriteSucceeded(responseCode)
}

/** "Today 19:04", otherwise a London date. [nowEpochMillis] is injectable for tests. */
fun formatHiveWriteListTime(epochMillis: Long, nowEpochMillis: Long): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(GoodHiveTime.londonZone)
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(GoodHiveTime.londonZone).toLocalDate()
    val date = time.toLocalDate()
    val clock = time.format(clockFormatter)
    return when {
        date == today -> "Today $clock"
        date == today.minusDays(1) -> "Yesterday $clock"
        date.year == today.year -> time.format(dayClockFormatter)
        else -> time.format(fullClockFormatter)
    }
}

fun formatHiveWriteDetailTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(GoodHiveTime.londonZone).format(fullClockFormatter)

fun hiveWriteTitle(path: String, zoneNames: Map<String, String>): String {
    val name = zoneIdFromHivePath(path)?.let { zoneNames[it] }
    return name?.takeIf { it.isNotBlank() } ?: path
}

private fun requestObject(requestBody: String): JsonObject? =
    runCatching { Json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull()

private fun boostSummary(obj: JsonObject?): String {
    val minutes = obj?.get("boost")?.jsonPrimitive?.intOrNull
    val target = obj?.get("target")?.jsonPrimitive?.doubleOrNull
    return buildString {
        append("Boost")
        if (minutes != null) append(" $minutes min")
        if (target != null) append(" · ${formatDegrees(target)}")
    }
}

private fun modeSummary(obj: JsonObject?): String {
    val mode = modeLabel(obj?.get("mode")?.jsonPrimitive?.contentOrNull.orEmpty())
    val target = obj?.get("target")?.jsonPrimitive?.doubleOrNull
    return if (target != null) "$mode ${formatDegrees(target)}" else mode
}

private fun modeLabel(mode: String): String = when (mode.uppercase(Locale.ROOT)) {
    "MANUAL" -> "Manual"
    "OFF" -> "Off"
    "SCHEDULE" -> "Schedule"
    "HOLIDAY" -> "Holiday"
    "BOOST" -> "Boost"
    else -> mode.lowercase(Locale.UK).replaceFirstChar { it.titlecase(Locale.UK) }
}

private fun formatDegrees(value: Double): String {
    val text = if (abs(value % 1.0) < 0.001) value.toInt().toString() else String.format(Locale.UK, "%.1f", value)
    return "$text°"
}

private fun redactJson(element: JsonElement, onChange: () -> Unit): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.mapValues { (key, value) ->
            if (sensitiveKey.containsMatchIn(key)) {
                onChange()
                JsonPrimitive(REDACTED)
            } else {
                redactJson(value, onChange)
            }
        },
    )
    is JsonArray -> JsonArray(element.map { redactJson(it, onChange) })
    else -> element
}
