package com.gazw89.goodhive.data.model.reports

import java.util.Locale

private fun d1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)

private fun whole(v: Double): String = Math.round(v).toString()

private fun clock(minutes: Double): String {
    val m = Math.floorMod(Math.round(minutes).toInt(), 1440)
    return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
}

private fun Insight.n(key: String): Double = numbers[key] ?: 0.0

/**
 * Deterministic UK English sentence for [insight]. Estimates say "about"; it never speculates about
 * causes such as draughts, and valve comparisons are always "your other valves".
 */
fun templateText(insight: Insight): String {
    val room = insight.zoneName
    return when (insight.type) {
        InsightType.SLOW_WARM_UP ->
            "$room takes about ${whole(insight.n("medianMinutes"))} minutes to reach its target after " +
                "the ${clock(insight.n("slotStartMinutes"))} step up. Starting about " +
                "${whole(insight.n("leadMinutes"))} minutes earlier, at ${clock(insight.n("suggestedStartMinutes"))}, " +
                "should get it there on time."
        InsightType.COLD_NIGHTS ->
            "$room dropped below ${whole(insight.n("thresholdC"))}°C on ${whole(insight.n("nights"))} " +
                "of ${whole(insight.n("observedNights"))} nights, down to ${d1(insight.n("coldestC"))}°C at the coldest."
        InsightType.VALVE_COOLS_FASTER ->
            "$room cools overnight about ${d1(insight.n("ratio"))} times faster than your other valves " +
                "(about ${d1(insight.n("rateCPerHour"))}°C an hour compared with " +
                "${d1(insight.n("otherValvesRateCPerHour"))}°C)."
        InsightType.MISSING_READINGS ->
            "$room has no readings for about ${whole(insight.n("totalHours"))} hours in total, over " +
                "${whole(insight.n("gaps"))} ${if (insight.n("gaps") == 1.0) "gap" else "gaps"}. " +
                "It may be worth checking that it is online and its battery is fine."
        InsightType.COLDER_THAN_LAST_YEAR ->
            "$room averaged about ${d1(insight.n("avgC"))}°C, which is " +
                "${d1(-insight.n("differenceC"))}°C colder than the same period last year."
        InsightType.LOW_TIME_ON_TARGET ->
            "$room spent about ${whole(insight.n("sharePercent"))}% of its scheduled heating time within " +
                "0.5°C of target" + if (insight.n("coveredByLogPercent") < 90) " (an estimate)." else "."
    }
}
