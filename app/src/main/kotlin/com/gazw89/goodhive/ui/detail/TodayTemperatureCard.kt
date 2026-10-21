package com.gazw89.goodhive.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.ui.components.chart.ChartPoint
import com.gazw89.goodhive.ui.components.chart.ChartSeries
import com.gazw89.goodhive.ui.components.chart.ChartSeriesStyle
import com.gazw89.goodhive.ui.components.chart.ChartViewport
import com.gazw89.goodhive.ui.components.chart.FullDayDomain
import com.gazw89.goodhive.ui.components.chart.TemperatureChart
import com.gazw89.goodhive.ui.components.chart.minuteOfDayLabel
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.calm
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.util.ScheduleStep
import com.gazw89.goodhive.util.scheduleNowNextLater
import com.gazw89.goodhive.util.stepsFor
import com.gazw89.goodhive.util.toTimeLabel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.Instant
import java.util.Locale
import com.gazw89.goodhive.data.model.TemperatureReading

private const val MINUTES_PER_DAY = 1440.0
private val ChartHeight = 168.dp
private val SwatchWidth = 16.dp
private val SwatchHeight = 4.dp
private val ScheduleStrokeWidth = 2.dp
private val RoomStrokeWidth = 3.dp
private val DayAxisLabels = listOf(0.0 to "00", 360.0 to "06", 720.0 to "12", 1080.0 to "18", 1440.0 to "24")

/**
 * Wall-clock minutes (0..1439) of [epochMillis] in [zoneId], or null when it falls on a different
 * calendar day than [date]. Wall-clock (not elapsed) minutes so readings line up with schedule
 * steps, which are also wall-clock, on DST-change days.
 */
internal fun wallClockMinuteOf(epochMillis: Long, date: LocalDate, zoneId: ZoneId = GoodHiveTime.londonZone): Int? {
    val local = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    if (local.toLocalDate() != date) return null
    return local.hour * 60 + local.minute
}

/**
 * Room line for the chart. Readings are sorted by time and mapped to wall-clock minutes; any point
 * that would step backwards in x (the repeated hour when clocks go back) is dropped so the line
 * stays monotonic. A live tail point at [nowX] is appended when [currentC] is known and the series
 * is non-empty and not already ahead of now.
 */
internal fun roomChartPoints(
    readings: List<TemperatureReading>,
    date: LocalDate,
    nowX: Double,
    currentC: Double?,
    zoneId: ZoneId = GoodHiveTime.londonZone,
): List<ChartPoint> {
    val points = ArrayList<ChartPoint>()
    readings.sortedBy { it.epochMillis }.forEach { r ->
        val minute = wallClockMinuteOf(r.epochMillis, date, zoneId) ?: return@forEach
        val x = minute.toDouble()
        if (points.isEmpty() || x > points.last().x) points += ChartPoint(x, r.temperatureC)
    }
    if (points.isNotEmpty() && currentC != null && nowX >= points.last().x) {
        points += ChartPoint(nowX, currentC)
    }
    return points
}

/** Step points from [steps] plus the closing point at the end of the day; empty when there are no steps. */
internal fun scheduleChartPoints(steps: List<ScheduleStep>): List<ChartPoint> {
    if (steps.isEmpty()) return emptyList()
    return steps.map { ChartPoint(it.startMinute.toDouble(), it.targetC) } +
        ChartPoint(MINUTES_PER_DAY, steps.last().targetC)
}

private fun plainTemp(c: Double): String {
    val oneDp = String.format(Locale.UK, "%.1f", c)
    return if (oneDp.endsWith(".0")) oneDp.dropLast(2) else oneDp
}

/** [base] unchanged at the full day; while zoomed, appends the visible time window. */
internal fun zoomedChartDescription(base: String, viewport: ChartViewport): String =
    if (viewport.isFullDay) base
    else "$base Zoomed to ${minuteOfDayLabel(viewport.start)} to ${minuteOfDayLabel(viewport.end)}."

/** One-sentence summary of the chart for screen readers. */
internal fun todayChartDescription(
    roomTempsC: List<Double>,
    currentC: Double?,
    steps: List<ScheduleStep>,
): String {
    val parts = mutableListOf("Today.")
    if (roomTempsC.isNotEmpty()) {
        val room = "Room between ${plainTemp(roomTempsC.min())} and ${plainTemp(roomTempsC.max())} degrees" +
            (currentC?.let { ", now ${plainTemp(it)}" } ?: "") + "."
        parts += room
    }
    // Merge adjacent steps with the same target so the spoken schedule is as short as possible.
    val merged = mutableListOf<ScheduleStep>()
    steps.forEach { s ->
        val last = merged.lastOrNull()
        if (last != null && last.targetC == s.targetC) merged[merged.lastIndex] = last.copy(endMinute = s.endMinute)
        else merged += s
    }
    if (merged.size == 1) {
        parts += "Schedule: ${plainTemp(merged[0].targetC)} degrees all day."
    } else if (merged.size > 1) {
        val clauses = merged.mapIndexed { i, s ->
            when {
                i == merged.lastIndex -> "then ${plainTemp(s.targetC)}"
                i == 0 -> "${plainTemp(s.targetC)} degrees until ${s.endMinute.toTimeLabel()}"
                else -> "${plainTemp(s.targetC)} until ${s.endMinute.toTimeLabel()}"
            }
        }
        parts += "Schedule: ${clauses.joinToString(", ")}."
    }
    return parts.joinToString(" ")
}

@Composable
fun TodayTemperatureCard(
    zone: HeatingZone,
    history: TodayHistoryState,
    modifier: Modifier = Modifier,
) {
    val now = remember(zone.currentTemperatureC, history) { GoodHiveTime.londonDateTimeNow() }
    val today = now.toLocalDate()
    val nowX = (now.hour * 60 + now.minute).toDouble()

    val steps = remember(zone.schedule, today) { zone.schedule.stepsFor(today) }
    val nextLabel = remember(zone.schedule, now) { nextChangeLabel(zone, now) }
    val readings = (history as? TodayHistoryState.Ready)?.readings
    val roomPoints = remember(readings, today, nowX, zone.currentTemperatureC) {
        if (readings == null) emptyList() else roomChartPoints(readings, today, nowX, zone.currentTemperatureC)
    }
    val description = remember(roomPoints, zone.currentTemperatureC, steps) {
        todayChartDescription(roomPoints.map { it.y }, zone.currentTemperatureC.takeIf { roomPoints.isNotEmpty() }, steps)
    }

    val scheduleColor = MaterialTheme.colorScheme.primary
    val roomColor = MaterialTheme.colorScheme.calm
    val series = remember(steps, roomPoints, nowX, scheduleColor, roomColor) {
        buildList {
            if (steps.isNotEmpty()) {
                add(ChartSeriesStyle(ChartSeries.Step(scheduleChartPoints(steps)), scheduleColor, ScheduleStrokeWidth, dimBeforeX = nowX))
            }
            if (roomPoints.size >= 2) {
                add(ChartSeriesStyle(ChartSeries.Line(roomPoints), roomColor, RoomStrokeWidth))
            }
        }
    }

    // Temporary view state: plain remember (not saveable, not in the ViewModel), keyed on the zone so
    // leaving the page or opening another room always starts at the full day, even if this composable
    // instance is reused for a different zone.
    var viewport by remember(zone.id) { mutableStateOf(ChartViewport.FullDay) }
    val zoomed = !viewport.isFullDay

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(GoodHiveSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                if (nextLabel != null) {
                    Text(
                        nextLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(ChartHeight)) {
                TemperatureChart(
                    xDomain = FullDayDomain,
                    series = series,
                    nowX = nowX,
                    xLabels = DayAxisLabels,
                    contentDescription = zoomedChartDescription(description, viewport),
                    viewport = viewport,
                    onViewportChange = { viewport = it },
                    modifier = Modifier.fillMaxSize(),
                )
                // Overlaid (not in the layout flow) so the card never changes height when it appears.
                if (zoomed) {
                    FilledTonalIconButton(
                        onClick = { viewport = ChartViewport.FullDay },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Filled.ZoomOut, contentDescription = "Reset graph zoom")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.lg)) {
                LegendItem("Schedule", scheduleColor)
                LegendItem("Room", roomColor)
            }
            val caption = when (history) {
                TodayHistoryState.Loading -> "Loading room history…"
                TodayHistoryState.Unavailable -> "Room history isn't available right now"
                else -> null
            }
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun nextChangeLabel(zone: HeatingZone, now: LocalDateTime): String? {
    val nnl = zone.scheduleNowNextLater(now) ?: return null
    val minutes = nnl.nextChangeMinutes ?: return null
    val target = nnl.nextTargetC ?: return null
    return "Next: ${formatTemp(target)} at ${minutes.toTimeLabel()}"
}

@Composable
private fun LegendItem(label: String, color: Color) {
    // The chart's content description already carries the data; the legend is visual-only.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics { },
    ) {
        Box(
            Modifier
                .size(width = SwatchWidth, height = SwatchHeight)
                .background(color, MaterialTheme.shapes.extraSmall),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = GoodHiveSpacing.sm),
        )
    }
}
