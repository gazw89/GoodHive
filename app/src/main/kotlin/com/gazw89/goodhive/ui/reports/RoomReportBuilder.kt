package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.data.model.reports.DailyStats
import com.gazw89.goodhive.data.model.reports.InsightConfig
import com.gazw89.goodhive.data.model.reports.compareWindows
import com.gazw89.goodhive.data.model.reports.hourOfWeekGrid
import com.gazw89.goodhive.data.model.reports.readingGaps
import com.gazw89.goodhive.data.model.reports.timeOnTarget
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MAX_GAP_ROWS = 5
private const val HEATMAP_WEEKS = 4

/** Assembles one room's report. Pure; see [buildReportsState] for the overview. */
fun buildRoomReportState(
    zoneId: String,
    rooms: List<RoomInputs>,
    window: PeriodWindow,
    nowMs: Long,
    config: InsightConfig = InsightConfig(),
): RoomReportState {
    val base = RoomReportState(
        zoneId = zoneId,
        offset = window.offset,
        kind = window.kind,
        canGoNext = window.canGoNext,
        periodLabel = window.label,
        comparisonLabel = window.comparisonLabel,
    )
    val room = rooms.firstOrNull { it.zoneId == zoneId } ?: return base.copy(status = ReportsStatus.NO_DATA)
    val slice = RoomSlice.of(room, window, nowMs)
    val header = base.copy(
        name = room.name,
        typeLabel = if (room.isValve) "Radiator valve" else "Thermostat",
        modeLine = modeLine(room.mode, room.modeIntervals),
    )
    if (slice.current.isEmpty()) return header.copy(status = ReportsStatus.NO_DATA, missing = missingState(room, slice, window, nowMs))

    val tiles = listOf(averageTile(slice, window), timeOnTargetTile(slice), coldestNightTile(slice, config, window, nowMs))
    val (bars, byMonth) = rangeRows(slice.dailyCurrent, window)
    val insights = descriptiveInsights(rooms, window, nowMs, config)
        .filter { it.zoneId == zoneId }.map { insightCard(it, forRoomScreen = true) }

    return header.copy(
        status = ReportsStatus.READY,
        tiles = tiles,
        rangeBars = bars,
        rangeBarsAreMonths = byMonth,
        dayTargetC = if (room.isOff) null else dominantDayTarget(room.schedule),
        rangeXLabels = rangeLabels(bars, byMonth, window),
        heatmap = heatmap(room, window, nowMs),
        insights = insights,
        missing = missingState(room, slice, window, nowMs),
    )
}

private fun averageTile(slice: RoomSlice, window: PeriodWindow): StatTileState {
    val cmp = matchedComparison(slice.dailyCurrent, slice.dailyPrevious, window)
    val avg = compareWindows(slice.dailyCurrent, emptyList()).current.avgC
        ?: compareWindows(slice.dailyCurrent, emptyList(), true).current.avgC
    val delta = cmp.avgDifferenceC
    return StatTileState(
        title = "Average",
        value = avg?.let { deg(it) } ?: "–",
        detail = when {
            avg == null -> "Not enough complete days"
            delta == null -> "Available readings · no matched comparison"
            else -> "${comparisonPhrase(delta, window.comparisonLabel)} · ${cmp.current.days} matched days"
        },
        spoken = if (avg == null) "Average: not enough complete days"
        else "Average ${d1(avg)} degrees" + (delta?.let { ", ${comparisonPhrase(it, window.comparisonLabel)}" } ?: ""),
    )
}

private fun timeOnTargetTile(slice: RoomSlice): StatTileState {
    val room = slice.room
    if (!room.mode.equals("SCHEDULE", true)) {
        return StatTileState("Readings near scheduled target", modeName(room.mode), "Not scored in current mode", "Readings near scheduled target: not scored in current mode")
    }
    val tot = timeOnTarget(slice.current, room.schedule, room.scheduleSnapshots, room.modeIntervals)
    val share = tot.share ?: return StatTileState(
        "Readings near scheduled target", "–", "Not enough scheduled heating", "Time on target: not enough scheduled heating",
    )
        return StatTileState(
        title = "Readings near scheduled target",
        value = pct(share),
        detail = "Estimate · ±0.5° of scheduled target",
        spoken = "Readings near scheduled target ${pct(share)}, an estimate",
    )
}

private fun coldestNightTile(slice: RoomSlice, config: InsightConfig, window: PeriodWindow, nowMs: Long): StatTileState {
    val nights = observedNights(slice.room.readings, window.fromMs, minOf(window.toMs, nowMs))
    val coldest = nights.minByOrNull { it.minC }
        ?: return StatTileState("Coldest night", "–", "No reasonably sampled finished nights", "Coldest night: no complete nights yet")
    val below = nights.count { it.minC < config.coldNightThresholdC }
    return StatTileState(
        title = "Coldest night",
        value = deg(coldest.minC),
        detail = "${coldest.nightOf.dayLabel()} · $below ${if (below == 1) "night" else "nights"} below ${deg(config.coldNightThresholdC)}",
        spoken = "Coldest night ${d1(coldest.minC)} degrees on ${coldest.nightOf.dayLabel()}; " +
            "$below nights below ${d1(config.coldNightThresholdC)}",
    )
}

private fun rangeRows(daily: List<DailyStats>, window: PeriodWindow): Pair<List<DayRangeRow>, Boolean> {
    if (window.kind != ReportPeriodKind.YEAR) {
        return daily.filter { it.date >= window.from && it.date < window.toExclusive }.map {
            DayRangeRow(it.date.dayLabel(), it.lowC, it.avgC, it.highC, it.partial)
        } to false
    }
    val months = daily.groupBy { it.date.monthValue }.toSortedMap().map { (m, days) ->
        DayRangeRow(
            label = Month.of(m).getDisplayName(TextStyle.SHORT, Locale.UK),
            lowC = days.minOf { it.lowC },
            avgC = days.map { it.avgC }.average(),
            highC = days.maxOf { it.highC },
            dimmed = days.any { it.partial } || days.size < java.time.YearMonth.of(window.from.year, m).lengthOfMonth(),
        )
    }
    return months to true
}

private fun rangeLabels(bars: List<DayRangeRow>, byMonth: Boolean, window: PeriodWindow): List<Pair<Int, String>> =
    when {
        bars.isEmpty() -> emptyList()
        byMonth -> bars.mapIndexedNotNull { i, b -> if (i % 3 == 0) i to b.label else null }
        window.kind == ReportPeriodKind.WEEK -> bars.mapIndexed { i, b -> i to b.label.substringBefore(' ') }
        else -> bars.mapIndexedNotNull { i, b -> if (i % 7 == 0) i to b.label.substringAfter(' ') else null }
    }

private fun heatmap(room: RoomInputs, window: PeriodWindow, nowMs: Long): HeatmapState? {
    val end = minOf(window.toMs, nowMs)
    val grid = hourOfWeekGrid(room.readings, end, HEATMAP_WEEKS)
    val cells = grid.meanC.flatMapIndexed { d, row -> row.mapIndexedNotNull { h, v -> v?.let { Triple(d, h, it) } } }
    if (cells.isEmpty()) return null
    val sorted = cells.map { it.third }.sorted()
    val median = sorted[sorted.size / 2]
    val neutral = (median * 2).roundToInt() / 2.0
    val cold = floor(minOf(neutral - 2.0, sorted[(sorted.size * 0.05).toInt()]))
    val warm = ceil(maxOf(neutral + 2.0, sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]))
    val days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val lo = cells.minBy { it.third }
    val hi = cells.maxBy { it.third }
    fun at(c: Triple<Int, Int, Double>) = "${days[c.first]} at ${c.second.toString().padStart(2, '0')}:00"
    return HeatmapState(
        values = grid.meanC,
        coldC = cold,
        neutralC = neutral,
        warmC = warm,
        weeks = HEATMAP_WEEKS,
        description = "Typical temperature by weekday and hour in the four weeks ending ${window.toExclusive.minusDays(1).dayLabel()}. " +
            "Coolest around ${at(lo)} at ${deg(lo.third)}, warmest around ${at(hi)} at ${deg(hi.third)}.",
    )
}

private fun missingState(room: RoomInputs, slice: RoomSlice, window: PeriodWindow, nowMs: Long): MissingReadingsState? {
    val gaps = readingGaps(
            slice.current,
            windowFromMs = window.fromMs,
            windowToMs = minOf(window.toMs, nowMs),
        )
    val notes = buildList {
        if (room.battery == 0) add("The battery reads 0%. Replacing it may fix the missing readings.")
        if (!room.online) add("Hive currently reports this device as offline.")
    }
    if (gaps.isEmpty() && notes.isEmpty()) return null
    val rows = gaps.sortedByDescending { it.durationMs }.take(MAX_GAP_ROWS).sortedBy { it.startMs }.map {
        val hours = it.durationMs / 3_600_000.0
        GapRow("${it.startMs.londonDayTime()} to ${it.endMs.londonDayTime()} · about ${hours.roundToInt()} h")
    }
    val extra = gaps.size - rows.size
    return MissingReadingsState(
        totalHours = gaps.sumOf { it.durationMs } / 3_600_000.0,
        gaps = rows + if (extra > 0) listOf(GapRow("and $extra more")) else emptyList(),
        note = notes.joinToString(" ").ifEmpty { null },
    )
}
