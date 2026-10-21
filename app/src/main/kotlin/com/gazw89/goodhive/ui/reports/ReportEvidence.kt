package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.reports.*
import com.gazw89.goodhive.util.GoodHiveTime
import java.time.Instant
import java.time.LocalDate

/** Compare only complete days present on both sides; never substitute a zero for missing data. */
internal fun matchedComparison(current: List<DailyStats>, previous: List<DailyStats>, window: PeriodWindow): WindowComparison {
    val prev = previous.filterNot { it.partial }.associateBy { it.date }
    val pairs = current.filterNot { it.partial }.mapNotNull { day ->
        val previousDate = if (window.kind == ReportPeriodKind.YEAR) day.date.minusYears(1)
            else window.prevFrom.plusDays(day.date.toEpochDay() - window.from.toEpochDay())
        // Feb 29 has no equivalent in a non-leap year. Do not count Feb 28 twice.
        if (window.kind == ReportPeriodKind.YEAR && previousDate.dayOfMonth != day.date.dayOfMonth) null
        else prev[previousDate]?.let { day to it }
    }
    return compareWindows(pairs.map { it.first }, pairs.map { it.second })
}

/** Missing rooms make a whole-home day partial, even when every contributing room is complete. */
internal fun observedHomeDaily(rooms: List<List<DailyStats>>): List<DailyStats> {
    val counts = rooms.flatten().groupingBy { it.date }.eachCount()
    return wholeHomeDaily(rooms).map { it.copy(partial = it.partial || counts[it.date] != rooms.size) }
}

/** Completed, reasonably sampled 22:00–07:00 nights only; open nights are not cold-night evidence. */
internal fun observedNights(readings: List<TemperatureReading>, fromMs: Long, toMs: Long): List<NightMinimum> =
    nightMinima(readings).filter { night ->
        val start = night.nightOf.atTime(22, 0).atZone(GoodHiveTime.londonZone).toInstant().toEpochMilli()
        val end = night.nightOf.plusDays(1).atTime(7, 0).atZone(GoodHiveTime.londonZone).toInstant().toEpochMilli()
        val hours = readings.filter { it.epochMillis in start until end }
            .map { (it.epochMillis - start) / 3_600_000 }.toSet().size
        start >= fromMs && end <= toMs && hours >= 7
    }

/** Only calibrated descriptive findings ship. No warm-up advice or generated prose. */
internal fun descriptiveInsights(rooms: List<RoomInputs>, window: PeriodWindow, nowMs: Long, config: InsightConfig): List<Insight> =
    rooms.flatMap { room ->
        val end = minOf(window.toMs, nowMs)
        val rs = room.readings.within(window.fromMs, end)
        val nights = observedNights(room.readings, window.fromMs, end)
        val cold = nights.filter { it.minC < config.coldNightThresholdC }
        val gaps = readingGaps(rs, windowFromMs = window.fromMs, windowToMs = end)
        buildList {
            if (cold.size >= config.minColdNights) add(Insight(
                InsightType.COLD_NIGHTS, room.zoneId, room.name, InsightSeverity.NOTICE,
                mapOf("nights" to cold.size.toDouble(), "observedNights" to nights.size.toDouble(),
                    "thresholdC" to config.coldNightThresholdC, "coldestC" to cold.minOf { it.minC }),
                window.fromMs, end))
            if (gaps.sumOf { it.durationMs } >= config.missingTotalHours * 3_600_000) add(Insight(
                InsightType.MISSING_READINGS, room.zoneId, room.name, InsightSeverity.NOTICE,
                mapOf("totalHours" to gaps.sumOf { it.durationMs } / 3_600_000.0,
                    "gaps" to gaps.size.toDouble(), "longestHours" to gaps.maxOf { it.durationMs } / 3_600_000.0),
                window.fromMs, end))
        }
    }.sortedWith(compareBy({ it.type.ordinal }, { it.zoneName }, { it.zoneId }))
