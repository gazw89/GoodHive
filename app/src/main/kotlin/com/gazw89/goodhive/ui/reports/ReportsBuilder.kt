package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.reports.DailyStats
import com.gazw89.goodhive.data.model.reports.Insight
import com.gazw89.goodhive.data.model.reports.InsightConfig
import com.gazw89.goodhive.data.model.reports.InsightType
import com.gazw89.goodhive.data.model.reports.compareWindows
import com.gazw89.goodhive.data.model.reports.dailyStats
import com.gazw89.goodhive.data.model.reports.templateText
import com.gazw89.goodhive.data.model.reports.timeOnTarget
import com.gazw89.goodhive.data.model.reports.wholeHomeDaily
import com.gazw89.goodhive.ui.components.chart.ChartPoint
import com.gazw89.goodhive.util.GoodHiveTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
/** Share of a window's days that must be observed before it is called complete rather than partial. */
private const val COMPLETE_SHARE = 1.0

internal fun List<TemperatureReading>.within(fromMs: Long, toMs: Long): List<TemperatureReading> =
    filter { it.epochMillis in fromMs until toMs }

internal fun insightCard(insight: Insight, forRoomScreen: Boolean): InsightCardState {
    val action = when {
        insight.type == InsightType.SLOW_WARM_UP -> InsightAction.OPEN_SCHEDULE
        forRoomScreen -> null
        else -> InsightAction.OPEN_ROOM
    }
    return InsightCardState(
        type = insight.type,
        zoneId = insight.zoneId,
        zoneName = insight.zoneName,
        title = "${insight.type.title()} · ${insight.zoneName}",
        text = if (insight.type == InsightType.COLD_NIGHTS) {
            "${insight.zoneName} had observed readings below ${deg(insight.numbers.getValue("thresholdC"))}C on " +
                "${insight.numbers.getValue("nights").toInt()} of ${insight.numbers.getValue("observedNights").toInt()} reasonably sampled nights. " +
                "The lowest returned reading was ${deg(insight.numbers.getValue("coldestC"))}C."
        } else templateText(insight),
        action = action,
        actionLabel = when (action) {
            InsightAction.OPEN_SCHEDULE -> "Open schedule"
            InsightAction.OPEN_ROOM -> "See room report"
            null -> null
        },
    )
}

/**
 * Assembles the Reports overview from loaded [rooms]. Pure: no Android, no I/O, deterministic for a
 * given [nowMs]. Summary text always comes from deterministic templates.
 */
fun buildReportsState(
    rooms: List<RoomInputs>,
    window: PeriodWindow,
    nowMs: Long,
    sync: SyncProgress? = null,
    config: InsightConfig = InsightConfig(),
): ReportsState {
    val base = ReportsState(
        kind = window.kind,
        canGoNext = window.canGoNext,
        periodLabel = window.label,
        comparisonLabel = window.comparisonLabel,
        sync = sync,
        offset = window.offset,
        roomLinks = rooms.map { it.zoneId to it.name },
    )
    if (rooms.isEmpty()) return base.copy(status = ReportsStatus.NO_DATA)

    val slices = rooms.map { RoomSlice.of(it, window, nowMs) }
    if (slices.all { it.current.isEmpty() }) return base.copy(status = ReportsStatus.NO_DATA)

    val homeCurrent = observedHomeDaily(slices.map { it.dailyCurrent })
    val homePrevious = observedHomeDaily(slices.map { it.dailyPrevious })
    val homeCmp = matchedComparison(homeCurrent, homePrevious, window)
    val homeObserved = compareWindows(homeCurrent, emptyList()).current
    val homeAverage = homeObserved.avgC ?: compareWindows(homeCurrent, emptyList(), true).current.avgC

    val roomAverages = slices.mapNotNull { s ->
        val cmp = compareWindows(s.dailyCurrent, emptyList())
        val avg = cmp.current.avgC ?: compareWindows(s.dailyCurrent, emptyList(), true).current.avgC
        avg?.let { RoomTemp(s.room.zoneId, s.room.name, it) }
    }
    val expectedDays = (window.dayCount - if (window.isOpen) 1 else 0).coerceAtLeast(0)
    val hero = homeAverage?.let { avg ->
        HeroState(
            avgC = avg,
            deltaC = homeCmp.avgDifferenceC,
            warmest = roomAverages.maxByOrNull { it.avgC },
            coolest = roomAverages.minByOrNull { it.avgC },
            daysCounted = homeObserved.days,
            periodDays = expectedDays,
            coverageNote = when {
                expectedDays == 0 -> "Today is partial; no completed days yet."
                homeObserved.days < expectedDays -> "${homeObserved.days} of $expectedDays completed days cover every room. " +
                    "Partial days and missing rooms affect coverage; averages use available readings."
                window.isOpen -> "${homeObserved.days} completed days; today is excluded."
                else -> null
            },
            comparisonNote = homeCmp.avgDifferenceC?.let { "Compared over ${homeCmp.current.days} matched complete days." },
        )
    }

    val insights = descriptiveInsights(rooms, window, nowMs, config)
    val cards = insights.take(3).map { insightCard(it, forRoomScreen = false) }
    val brief = briefState(window, hero, insights)

    val (totRows, totNote) = timeOnTargetRows(slices)

    val year = if (window.kind == ReportPeriodKind.YEAR) yearState(slices, window, nowMs, config.coldNightThresholdC) else null
    val daily = if (window.kind == ReportPeriodKind.YEAR) null else dailyChart(homeCurrent, homePrevious, window)

    return base.copy(
        status = ReportsStatus.READY,
        hero = hero,
        brief = brief,
        insights = cards,
        timeOnTarget = totRows,
        timeOnTargetNote = totNote,
        dailyChart = daily,
        year = year,
    )
}

/** Per-room cut of the loaded readings for the current and comparison windows. */
internal class RoomSlice(
    val room: RoomInputs,
    val current: List<TemperatureReading>,
    val previous: List<TemperatureReading>,
    val dailyCurrent: List<DailyStats>,
    val dailyPrevious: List<DailyStats>,
) {
    companion object {
        fun of(room: RoomInputs, window: PeriodWindow, nowMs: Long): RoomSlice {
            val cur = room.readings.within(window.fromMs, minOf(window.toMs, nowMs))
            val prev = room.readings.within(window.prevFromMs, window.prevToMs)
            return RoomSlice(room, cur, prev, dailyStats(cur, nowMs = nowMs), dailyStats(prev))
        }
    }
}

private fun briefState(window: PeriodWindow, hero: HeroState?, insights: List<Insight>): BriefState {
    val noun = window.kind.noun
    val title = if (window.offset == 0) "This $noun in brief" else "${noun.replaceFirstChar { it.uppercase() }} in brief"
    val text = if (insights.isNotEmpty()) {
        insights.take(3).joinToString(" ") { insightCard(it, false).text }
    } else {
        quietSummary(hero, window)
    }
    return BriefState(title, text)
}

private fun quietSummary(hero: HeroState?, window: PeriodWindow): String {
    val noun = window.kind.noun
    val avg = hero?.avgC ?: return "More complete days are needed to summarise this $noun."
    val delta = hero.deltaC
    val compare = if (delta != null) ", ${comparisonPhrase(delta, window.comparisonLabel).replaceFirstChar { it.lowercase() }}" else ""
    return "Available readings averaged about ${deg(avg)}$compare. Missing readings can hide changes."
}

private fun timeOnTargetRows(slices: List<RoomSlice>): Pair<List<TimeOnTargetRow>, String?> {
    var anyEstimate = false
    val rows = slices.map { s ->
        val r = s.room
        if (!r.mode.equals("SCHEDULE", true)) {
            return@map TimeOnTargetRow(r.zoneId, r.name, r.isOff, null, false, "${modeName(r.mode)} now · not scored")
        }
        val tot = timeOnTarget(s.current, r.schedule, r.scheduleSnapshots, r.modeIntervals)
        val share = tot.share
        val estimate = share != null
        if (estimate) anyEstimate = true
        TimeOnTargetRow(
            zoneId = r.zoneId,
            name = r.name,
            isOff = r.isOff,
            share = share,
            isEstimate = estimate,
            detail = when {
                share == null -> "Not enough scheduled heating to score"
                estimate -> "Estimate"
                else -> null
            },
        )
    }
    val sorted = rows.sortedWith(
        compareBy<TimeOnTargetRow> { if (it.isOff) 2 else if (it.share == null) 1 else 0 }
            .thenByDescending { it.share ?: 0.0 }
            .thenBy { it.name },
    )
    val note = if (anyEstimate) {
        "Estimate: percentage of sampled readings within ±0.5° of scheduled comfort targets (17° or above), " +
            "not elapsed time. Historical schedules and modes may be incomplete; current schedules fill gaps."
    } else {
        null
    }
    return sorted to note
}

private fun dailyChart(current: List<DailyStats>, previous: List<DailyStats>, window: PeriodWindow): LineChartState? {
    if (current.isEmpty()) return null
    val curPts = current.map { ChartPoint((it.date.toEpochDay() - window.from.toEpochDay()).toDouble(), it.avgC) }
        .filter { it.x >= 0 && it.x < window.dayCount }
    val prevPts = previous.map { ChartPoint((it.date.toEpochDay() - window.prevFrom.toEpochDay()).toDouble(), it.avgC) }
        .filter { it.x >= 0 && it.x < window.dayCount }
    if (curPts.isEmpty()) return null
    val xLabels = if (window.kind == ReportPeriodKind.WEEK) {
        (0 until window.dayCount).map { it.toDouble() to window.from.plusDays(it.toLong()).weekdayLabel() }
    } else {
        (0 until window.dayCount step 7).map { it.toDouble() to window.from.plusDays(it.toLong()).shortLabel() }
    }
    val all = curPts + prevPts
    val lo = all.minOf { it.y }
    val hi = all.maxOf { it.y }
    val prevByDay = previous.associateBy { it.date.toEpochDay() - window.prevFrom.toEpochDay() }
    val curByDate = current.associateBy { it.date }
    val rows = (0 until window.dayCount).map { i ->
        val date = window.from.plusDays(i.toLong())
        val d = curByDate[date]
        val p = prevByDay[i.toLong()]
        listOf(date.dayLabel() + if (d?.partial == true) " (partial)" else "",
            d?.let { deg(it.avgC) } ?: "No readings",
            p?.let { deg(it.avgC) + if (it.partial) " (partial)" else "" } ?: "No readings")
    }
    return LineChartState(
        current = curPts,
        previous = prevPts,
        xMax = (window.dayCount - 1).toDouble().coerceAtLeast(1.0),
        xLabels = xLabels,
        currentLabel = window.label,
        previousLabel = "Previous ${window.kind.noun}",
        rangeCaption = "Daily averages from ${deg(lo)} to ${deg(hi)}",
        tableHeaders = listOf("Day", "This ${window.kind.noun}", "Previous"),
        tableRows = rows,
        description = "Whole-home daily average temperature for ${window.label}, " +
            "from ${deg(curPts.minOf { it.y })} to ${deg(curPts.maxOf { it.y })}, " +
            "compared with the ${window.comparisonLabel}.",
    )
}

// --- Year ---------------------------------------------------------------------------------

private data class MonthAvg(val avgC: Double, val days: Int, val partial: Boolean)

private fun monthlyAverages(home: List<DailyStats>, year: Int, nowDate: LocalDate): Map<Int, MonthAvg> =
    (1..12).mapNotNull { m ->
        val days = home.filter { it.date.year == year && it.date.monthValue == m }
        if (days.isEmpty()) return@mapNotNull null
        val complete = days.filterNot { it.partial }
        val used = complete
        if (used.isEmpty()) return@mapNotNull null
        val length = YearMonth.of(year, m).lengthOfMonth()
        val monthEnded = YearMonth.of(year, m).atEndOfMonth() < nowDate
        val partial = used.size < length * COMPLETE_SHARE || !monthEnded
        m to MonthAvg(used.map { it.avgC }.average(), used.size, partial)
    }.toMap()

private fun monthShort(m: Int) = java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.UK)

private fun yearState(slices: List<RoomSlice>, window: PeriodWindow, nowMs: Long, thresholdC: Double): YearState {
    val nowDate = java.time.Instant.ofEpochMilli(nowMs).atZone(GoodHiveTime.londonZone).toLocalDate()
    val homeCur = observedHomeDaily(slices.map { it.dailyCurrent })
    val homePrev = observedHomeDaily(slices.map { it.dailyPrevious })
    val year = window.from.year
    val descriptiveCur = monthlyAverages(homeCur, year, nowDate)
    val matchedCurrent = homeCur.filterNot { it.partial }.associateBy { it.date }
    val matchedPrev = homePrev.filterNot { it.partial }.filter { day ->
        val currentDate = day.date.plusYears(1)
        currentDate.dayOfMonth == day.date.dayOfMonth && matchedCurrent[currentDate] != null
    }
    val prev = monthlyAverages(matchedPrev, year - 1, nowDate)
    val previousDates = matchedPrev.map { it.date.plusYears(1) }.toSet()
    val comparableCur = monthlyAverages(homeCur.filter { it.date in previousDates }, year, nowDate)
    // When a prior-year month exists, both series use exactly the same observed dates.
    val cur = descriptiveCur.mapValues { (month, avg) -> if (month in prev) comparableCur.getValue(month) else avg }

    val monthly = if (cur.isEmpty()) {
        null
    } else {
        val curPts = cur.map { (m, a) -> ChartPoint((m - 1).toDouble(), a.avgC) }.sortedBy { it.x }
        val prevPts = prev.map { (m, a) -> ChartPoint((m - 1).toDouble(), a.avgC) }.sortedBy { it.x }
        val rows = (1..12).filter { it in cur || it in prev }.map { m ->
            fun cell(a: MonthAvg?) = a?.let { deg(it.avgC) + if (it.partial) " (partial)" else "" } ?: "–"
            listOf(monthShort(m), cell(cur[m]), cell(prev[m]))
        }
        val all = curPts + prevPts
        LineChartState(
            current = curPts,
            previous = prevPts,
            xMax = 11.0,
            xLabels = listOf(0, 2, 4, 6, 8, 10).map { it.toDouble() to monthShort(it + 1) },
            currentLabel = year.toString(),
            previousLabel = (year - 1).toString(),
            rangeCaption = "Monthly averages from ${deg(all.minOf { it.y })} to ${deg(all.maxOf { it.y })}",
            tableHeaders = listOf("Month", year.toString(), (year - 1).toString()),
            tableRows = rows,
            description = "Whole-home monthly sample averages for $year; compared months use matching reasonably sampled dates" +
                (if (prevPts.isNotEmpty()) ", with the same months of ${year - 1}" else "") +
                ", from ${deg(all.minOf { it.y })} to ${deg(all.maxOf { it.y })}.",
        )
    }

    val expected = window.dayCount.coerceAtLeast(1)
    val rooms = slices.map { s ->
        val cmp = matchedComparison(s.dailyCurrent, s.dailyPrevious, window)
        RoomYearRow(
            zoneId = s.room.zoneId,
            name = s.room.name,
            avgC = compareWindows(s.dailyCurrent, emptyList()).current.avgC,
            lastYearAvgC = cmp.previous.avgC,
            deltaC = cmp.avgDifferenceC,
            partial = cmp.current.days < expected * COMPLETE_SHARE || cmp.previous.days < expected * COMPLETE_SHARE,
        )
    }

    return YearState(
        monthly = monthly,
        rooms = rooms,
        winter = winterState(slices, minOf(nowDate, window.toExclusive.minusDays(1)), thresholdC),
        partialNote = "Partial years: averages use available completed days. Differences use only matching calendar dates with complete readings in both years. Today is excluded. " + (lastYearNote(slices, window) ?: ""),
    )
}

private fun lastYearNote(slices: List<RoomSlice>, window: PeriodWindow): String? {
    val earliest = slices.mapNotNull { s -> s.room.readings.firstOrNull()?.epochMillis }.minOrNull()
        ?: return null
    val start = java.time.Instant.ofEpochMilli(earliest).atZone(GoodHiveTime.londonZone).toLocalDate()
    return if (start > window.prevFrom.plusDays(14)) {
        "Readings go back to ${start.dayOfMonth} ${monthShort(start.monthValue)} ${start.year}, so " +
            "${window.prevFrom.year} figures are partial. Hive keeps about 15 months of history."
    } else {
        null
    }
}

/** The most recent finished December to February, from whichever readings are loaded. */
private fun winterState(slices: List<RoomSlice>, nowDate: LocalDate, threshold: Double): WinterState? {
    val endYear = if (nowDate >= LocalDate.of(nowDate.year, 3, 1)) nowDate.year else nowDate.year - 1
    val from = LocalDate.of(endYear - 1, 12, 1)
    val to = LocalDate.of(endYear, 3, 1)
    val fromMs = GoodHiveTime.londonStartOfDay(from).toEpochMilli()
    val toMs = GoodHiveTime.londonStartOfDay(to).toEpochMilli()
    val days = (to.toEpochDay() - from.toEpochDay()).toInt()
    val rows = slices.map { s ->
        val rs = s.room.readings.within(fromMs, toMs)
        val daily = dailyStats(rs)
        val cmp = compareWindows(daily, emptyList())
        val nights = observedNights(rs, fromMs, toMs)
        WinterRow(
            name = s.room.name,
            avgC = cmp.current.avgC,
            nightsBelow = nights.count { it.minC < threshold },
            observedNights = nights.size,
            partial = cmp.current.days < days * COMPLETE_SHARE,
        )
    }
    if (rows.all { it.avgC == null }) return null
    return WinterState("Winter ${endYear - 1}–${(endYear % 100).toString().padStart(2, '0')}", threshold, rows)
}
