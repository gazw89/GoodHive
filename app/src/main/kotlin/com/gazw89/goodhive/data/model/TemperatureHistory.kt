package com.gazw89.goodhive.data.model

/** One temperature sample from Hive's `history/heating/{id}` endpoint. */
data class TemperatureReading(val epochMillis: Long, val temperatureC: Double)

/**
 * Bucket size requested from the history endpoint (`timeUnit` + `rate` query params).
 *
 * [FIVE_MINUTES] is effectively the raw readings: thermostats report every 5-10 minutes and
 * TRVs every 10-15 minutes, so a 5-minute bucket rarely merges two samples.
 */
enum class HistoryResolution(val timeUnit: String, val rate: Int) {
    FIVE_MINUTES("MINUTES", 5),
    HOURLY("HOURS", 1),
    DAILY("DAYS", 1),
}

/**
 * Bucket aggregation (`operation` query param). AVG, MAX and MIN are all confirmed live
 * (MIN 2026-10-15: daily MIN 19.0 < AVG 19.49 < MAX 20.0 over the same 7 days).
 */
enum class HistoryAggregate(val wire: String) {
    AVG("AVG"),
    MAX("MAX"),
    MIN("MIN"),
}

data class ReadingSummary(val minC: Double, val maxC: Double, val latest: TemperatureReading)

/** Min/max/latest over the readings, or null when empty. Latest is the highest epochMillis. */
fun List<TemperatureReading>.summary(): ReadingSummary? {
    val latest = maxByOrNull { it.epochMillis } ?: return null
    return ReadingSummary(
        minC = minOf { it.temperatureC },
        maxC = maxOf { it.temperatureC },
        latest = latest,
    )
}

/** Width of the raw-reading bucket that [mergeSourceReadings] averages across sources. */
const val READING_BUCKET_MS = 5L * 60 * 1000

/**
 * Combines the series of one room's history sources (a multi-valve room has several) into one:
 * the mean temperature per 5-minute bucket, stamped at the bucket start, sorted by time. A single
 * source is returned as-is, and empty sources are ignored.
 */
fun mergeSourceReadings(series: List<List<TemperatureReading>>): List<TemperatureReading> {
    val nonEmpty = series.filter { it.isNotEmpty() }
    if (nonEmpty.size <= 1) return nonEmpty.firstOrNull().orEmpty().sortedBy { it.epochMillis }
    val sums = HashMap<Long, DoubleArray>() // bucket start -> [sum, count]
    for (reading in nonEmpty.flatten()) {
        val bucket = Math.floorDiv(reading.epochMillis, READING_BUCKET_MS) * READING_BUCKET_MS
        val acc = sums.getOrPut(bucket) { DoubleArray(2) }
        acc[0] += reading.temperatureC
        acc[1] += 1.0
    }
    return sums.entries.sortedBy { it.key }.map { (bucket, acc) -> TemperatureReading(bucket, acc[0] / acc[1]) }
}
