package com.gazw89.goodhive.ui.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.ui.components.chart.*
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

@Composable
fun RoomReportScreen(onBack: () -> Unit, onOpenRoomControls: () -> Unit, onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit, viewModel: RoomReportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenOpened() }
    RoomReportContent(state, viewModel::selectKind, viewModel::previous, viewModel::next,
        onBack, onOpenRoomControls, onOpenSchedule, onOpenSettings, viewModel::retry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomReportContent(
    state: RoomReportState,
    onSelectKind: (ReportPeriodKind) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onOpenRoomControls: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var about by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text(state.name.ifEmpty { "Room report" }) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        }, actions = { IconButton(onClick = { about = true }) { Icon(Icons.Filled.Info, "About these reports") } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(GoodHiveSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.lg)) {
            item { Text("${state.typeLabel} · ${state.modeLine}", style = MaterialTheme.typography.bodyMedium) }
            item { PeriodControls(state.kind, state.periodLabel, state.canGoNext, onSelectKind, onPrevious, onNext) }
            item { ReportStatus(state.status, state.error, onRetry) }
            state.tiles.forEach { tile -> item {
                ReportCard(tile.title) {
                    Text(tile.value, style = MaterialTheme.typography.headlineMedium)
                    tile.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            } }
            if (state.rangeBars.isNotEmpty()) item {
                ReportChart(if (state.rangeBarsAreMonths) "Monthly observed range" else "Daily observed range",
                    listOf("Period", "Low", "Mean", "High"), state.rangeBars.map {
                        listOf(it.label + if (it.dimmed) " (partial)" else "", deg(it.lowC), deg(it.avgC), deg(it.highC))
                    }) {
                    RangeBarChart(bars = state.rangeBars.map { RangeBarDatum(it.lowC, it.highC, it.avgC, it.dimmed) },
                        xLabels = state.rangeXLabels, contentDescription = "Observed low, mean and high temperatures for ${state.periodLabel}. Partial periods are marked in the table.",
                        modifier = Modifier.fillMaxWidth().height(GoodHiveSpacing.chartHeight))
                    Text("Lowest and highest returned readings; marks show the mean. Fainter bars are partial. Missing periods are omitted.", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.heatmap?.let { heatmap -> item {
                val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                ReportChart("Typical week · preceding four weeks", listOf("Weekday / hour", "Mean"),
                    heatmap.values.flatMapIndexed { d, values -> values.mapIndexed { h, v -> listOf("${days[d]} ${h.toString().padStart(2, '0')}:00", v?.let(::deg) ?: "No readings") } }) {
                    HourWeekHeatmap(heatmap.values, heatmap.coldC, heatmap.neutralC, heatmap.warmC,
                        heatmap.description, Modifier.fillMaxWidth().height(GoodHiveSpacing.heatmapHeight))
                    HeatmapLegend(heatmap.coldC, heatmap.warmC)
                    Text(heatmap.description, style = MaterialTheme.typography.bodySmall)
                    Text("Outlined cells have no readings. Colour is relative to this room, not a comfort score.", style = MaterialTheme.typography.bodySmall)
                }
            } }
            state.insights.forEach { insight -> item { InsightCards(listOf(insight)) } }
            state.missing?.let { missing -> item {
                ReportCard("Missing readings") {
                    Text("About ${d1(missing.totalHours)} hours without readings in gaps over two hours")
                    missing.gaps.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                    missing.note?.let { Text(it) }
                    TextButton(onClick = onRetry) { Text("Retry sync") }
                }
            } }
            item {
                ReportCard("Reading these figures") {
                    Text("Readings near target is a sample-based estimate within ±0.5° of scheduled comfort targets. Current schedules fill missing history. A low share can mean above target, not heating failure.")
                    Text("Warm-up and cooling advice needs validated active-heating evidence and is not shown.")
                    TextButton(onClick = onOpenSettings) { Text("Change cold-night threshold in Settings") }
                }
            }
            if (state.name.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
                    OutlinedButton(onClick = onOpenRoomControls, modifier = Modifier.fillMaxWidth()) { Text("Room controls") }
                    TextButton(onClick = onOpenSchedule, modifier = Modifier.fillMaxWidth()) { Text("View schedule") }
                }
            }
        }
    }
    if (about) ReportsAbout { about = false }
}
