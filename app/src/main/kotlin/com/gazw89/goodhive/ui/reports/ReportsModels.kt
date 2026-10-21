package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.model.reports.InsightType
import com.gazw89.goodhive.data.model.reports.ModeInterval
import com.gazw89.goodhive.data.model.reports.ScheduleAt
import com.gazw89.goodhive.data.model.reports.TimeInterval
import com.gazw89.goodhive.ui.components.chart.ChartPoint
import java.time.LocalDate

/** Everything the report calculations need to know about one room, already loaded from Room. */
data class RoomInputs(
    val zoneId: String,
    val name: String,
    val isValve: Boolean,
    /** Hive mode now ("SCHEDULE", "MANUAL", "OFF", "BOOST"...). */
    val mode: String,
    val online: Boolean,
    val battery: Int?,
    val schedule: WeekSchedule,
    /** Raw readings from the start of the comparison window to the end of the current window. */
    val readings: List<TemperatureReading>,
    /** The current window one year earlier, for week and month; null when not loaded. */
    val lastYearReadings: List<TemperatureReading>? = null,
    val scheduleSnapshots: List<ScheduleAt> = emptyList(),
    val modeIntervals: List<ModeInterval> = emptyList(),
    val boostIntervals: List<TimeInterval> = emptyList(),
) {
    val isOff: Boolean get() = mode.equals("OFF", ignoreCase = true)
}

/** Backfill progress shown at the top of Reports. */
data class SyncProgress(val roomsDone: Int, val roomsTotal: Int, val issueCount: Int = 0, val unsupportedCount: Int = 0) {
    val active: Boolean get() = roomsDone < roomsTotal
    val fraction: Float get() = if (roomsTotal == 0) 0f else roomsDone.toFloat() / roomsTotal
}

enum class ReportsStatus {
    /** Nothing to show yet: no rooms known or the first load is running. */
    LOADING,

    /** Rooms exist but there are no readings at all in the period. */
    NO_DATA,

    READY,
    ERROR,
}

data class BriefState(val title: String, val text: String)

data class RoomTemp(val zoneId: String, val name: String, val avgC: Double)

data class HeroState(
    val avgC: Double,
    /** Current minus previous; null when the previous window has no complete days. */
    val deltaC: Double?,
    val warmest: RoomTemp?,
    val coolest: RoomTemp?,
    val daysCounted: Int,
    val periodDays: Int,
    /** Honest note when fewer days than the period are counted. */
    val coverageNote: String?,
    val comparisonNote: String? = null,
)

enum class InsightAction { OPEN_SCHEDULE, OPEN_ROOM }

data class InsightCardState(
    val type: InsightType,
    val zoneId: String,
    val zoneName: String,
    val title: String,
    val text: String,
    val action: InsightAction?,
    val actionLabel: String?,
)

data class TimeOnTargetRow(
    val zoneId: String,
    val name: String,
    val isOff: Boolean,
    /** 0..1, or null when not scored. */
    val share: Double?,
    val isEstimate: Boolean,
    val detail: String?,
)

/** Table + chart data for a line chart with an optional dashed comparison line. */
data class LineChartState(
    val current: List<ChartPoint>,
    val previous: List<ChartPoint>,
    val xMax: Double,
    val xLabels: List<Pair<Double, String>>,
    val currentLabel: String,
    val previousLabel: String,
    val rangeCaption: String,
    val tableHeaders: List<String>,
    val tableRows: List<List<String>>,
    val description: String,
)

data class RoomYearRow(
    val zoneId: String,
    val name: String,
    val avgC: Double?,
    val lastYearAvgC: Double?,
    val deltaC: Double?,
    val partial: Boolean,
)

data class WinterRow(
    val name: String,
    val avgC: Double?,
    val nightsBelow: Int,
    val observedNights: Int,
    val partial: Boolean,
)

data class WinterState(val label: String, val thresholdC: Double, val rows: List<WinterRow>)

data class YearState(
    val monthly: LineChartState?,
    val rooms: List<RoomYearRow>,
    val winter: WinterState?,
    val partialNote: String?,
)

data class ReportsState(
    val kind: ReportPeriodKind = ReportPeriodKind.WEEK,
    val canGoNext: Boolean = false,
    val periodLabel: String = "",
    /** "previous week", "September", "2025". */
    val comparisonLabel: String = "",
    val status: ReportsStatus = ReportsStatus.LOADING,
    val sync: SyncProgress? = null,
    val offset: Int = 0,
    val rooms: List<RoomTemp> = emptyList(),
    val roomLinks: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
    val hero: HeroState? = null,
    val brief: BriefState? = null,
    val insights: List<InsightCardState> = emptyList(),
    val timeOnTarget: List<TimeOnTargetRow> = emptyList(),
    val timeOnTargetNote: String? = null,
    val dailyChart: LineChartState? = null,
    val year: YearState? = null,
)

// --- Room report ------------------------------------------------------------------------

data class StatTileState(
    val title: String,
    val value: String,
    val detail: String?,
    /** Spoken form when the visual text is terse ("~45 min" etc.). */
    val spoken: String,
)

data class DayRangeRow(
    val label: String,
    val lowC: Double,
    val avgC: Double,
    val highC: Double,
    val dimmed: Boolean,
)

data class WarmUpRow(val label: String, val minutes: Double)

data class GapRow(val text: String)

data class MissingReadingsState(
    val totalHours: Double,
    val gaps: List<GapRow>,
    val note: String?,
)

data class HeatmapState(
    val values: List<List<Double?>>,
    val coldC: Double,
    val neutralC: Double,
    val warmC: Double,
    val description: String,
    val weeks: Int,
)

data class RoomReportState(
    val zoneId: String = "",
    val name: String = "",
    val typeLabel: String = "",
    val modeLine: String = "",
    val kind: ReportPeriodKind = ReportPeriodKind.WEEK,
    val canGoNext: Boolean = false,
    val periodLabel: String = "",
    val comparisonLabel: String = "",
    val status: ReportsStatus = ReportsStatus.LOADING,
    val offset: Int = 0,
    val error: String? = null,
    val tiles: List<StatTileState> = emptyList(),
    val rangeBars: List<DayRangeRow> = emptyList(),
    val rangeBarsAreMonths: Boolean = false,
    val dayTargetC: Double? = null,
    val rangeXLabels: List<Pair<Int, String>> = emptyList(),
    val warmUps: List<WarmUpRow> = emptyList(),
    val warmUpAverageMinutes: Double? = null,
    val warmUpXLabels: List<Pair<Int, String>> = emptyList(),
    val warmUpNote: String? = null,
    val heatmap: HeatmapState? = null,
    val insights: List<InsightCardState> = emptyList(),
    val missing: MissingReadingsState? = null,
)

internal fun LocalDate.shortLabel(): String = "$dayOfMonth ${month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.UK)}"
