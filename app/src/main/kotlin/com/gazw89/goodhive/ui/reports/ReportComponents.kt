package com.gazw89.goodhive.ui.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.gazw89.goodhive.ui.components.chart.*
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeriodControls(kind: ReportPeriodKind, label: String, canGoNext: Boolean,
    onSelectKind: (ReportPeriodKind) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ReportPeriodKind.entries.forEachIndexed { index, period ->
                SegmentedButton(selected = kind == period, onClick = { onSelectKind(period) },
                    shape = SegmentedButtonDefaults.itemShape(index, ReportPeriodKind.entries.size)) { Text(period.label) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous ${kind.noun}") }
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onNext, enabled = canGoNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next ${kind.noun}") }
        }
    }
}

@Composable
internal fun ReportCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(GoodHiveSpacing.lg), verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
internal fun ReportChart(title: String, headers: List<String>, rows: List<List<String>>, chart: @Composable () -> Unit) {
    var table by rememberSaveable(title) { mutableStateOf(false) }
    ReportCard(title) {
        if (table) ChartTable(headers, rows) else chart()
        TextButton(onClick = { table = !table }, modifier = Modifier.heightIn(min = GoodHiveSpacing.touchTarget)) {
            Text(if (table) "Show chart" else "Show as table")
        }
    }
}

/** Split across absent days/months rather than drawing invented continuity through a gap. */
private fun segments(points: List<ChartPoint>): List<List<ChartPoint>> {
    val result = mutableListOf<MutableList<ChartPoint>>()
    points.sortedBy { it.x }.forEach { p ->
        if (result.isEmpty() || p.x - result.last().last().x > 1.01) result.add(mutableListOf())
        result.last().add(p)
    }
    return result
}

@Composable
internal fun ReportLineChart(title: String, data: LineChartState) {
    ReportChart(title, data.tableHeaders, data.tableRows) {
        val current = MaterialTheme.colorScheme.primary
        val previous = MaterialTheme.colorScheme.onSurfaceVariant
        TemperatureChart(xDomain = ChartRange(0.0, data.xMax),
            series = segments(data.current).map { ChartSeriesStyle(ChartSeries.Line(it), current) } +
                segments(data.previous).map { ChartSeriesStyle(ChartSeries.Line(it), previous, dashed = true) },
            xLabels = data.xLabels, contentDescription = data.description,
            modifier = Modifier.fillMaxWidth().height(GoodHiveSpacing.chartHeight))
        Text(data.rangeCaption, style = MaterialTheme.typography.bodySmall)
        Text("Solid: ${data.currentLabel}. Dashed: ${data.previousLabel}. Gaps have no readings; partial days appear in the table.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun ReportStatus(status: ReportsStatus, error: String?, onRetry: () -> Unit) {
    when (status) {
        ReportsStatus.LOADING -> ReportCard("Loading stored readings") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        ReportsStatus.NO_DATA -> ReportCard("No readings for this period") {
            Text("History may still be downloading, or Hive may not have readings this far back. Try a different period or sync again.")
            TextButton(onClick = onRetry) { Text("Sync now") }
        }
        ReportsStatus.ERROR -> ReportCard("Report unavailable") {
            Text(error ?: "Stored readings could not be loaded.")
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        ReportsStatus.READY -> Unit
    }
}

@Composable
internal fun InsightCards(insights: List<InsightCardState>, onOpenRoom: ((String) -> Unit)? = null) {
    insights.forEach { insight ->
        ReportCard(insight.title) {
            Text(insight.text)
            if (onOpenRoom != null) TextButton(onClick = { onOpenRoom(insight.zoneId) }) { Text("See room report") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportsAbout(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = GoodHiveSpacing.xl).padding(bottom = GoodHiveSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md)) {
            Text("About these reports", style = MaterialTheme.typography.titleLarge)
            Text("Reports use Hive's observed readings stored on this phone. Calculations and summaries stay on device. No heating settings change automatically.")
            Text("Temperatures are sample averages, not weighted by elapsed time. Lows and highs are the lowest and highest returned 5-minute average readings, not exact temperature extremes. Missing readings are excluded, never treated as zero.")
            Text("Room sensors can read differently, especially valves beside radiators. Whole-home figures weight available rooms equally and include the thermostat. Partial coverage can change which rooms contribute.")
            Text("Differences use matching completed calendar dates in Europe/London with readings in at least 75% of each day’s hours in every contributing room; today is excluded. These are reasonably sampled days, not fully observed days. The chart table marks partial days. Hive retains about 15 months, so earlier periods and years may be incomplete.")
            Text("Readings near scheduled target means the percentage of samples within ±0.5° of comfort targets of 17° or above. It is an estimate using current schedules when historical schedules are missing. A low share may mean the room is warmer, and is not a heating-performance score. OFF and MANUAL rooms are not scored.")
            Text("Cold nights use finished 22:00–07:00 nights with readings across at least seven hours. Missing periods do not prove a device fault. Warm-up and cooling advice is withheld until active-heating evidence is validated.")
        }
    }
}
