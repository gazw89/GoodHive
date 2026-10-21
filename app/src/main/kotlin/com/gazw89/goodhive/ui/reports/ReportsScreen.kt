package com.gazw89.goodhive.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

@Composable
fun ReportsScreen(onOpenRoom: (String, ReportPeriodKind, Int) -> Unit, onOpenSettings: () -> Unit,
    viewModel: ReportsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onScreenOpened() }
    ReportsContent(state, viewModel::selectKind, viewModel::previous, viewModel::next,
        { onOpenRoom(it, state.kind, state.offset) }, onOpenSettings, viewModel::retry)
}

/** Stateless production screen; also renders deterministic debug fixtures without auth or a ViewModel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsContent(
    state: ReportsState,
    onSelectKind: (ReportPeriodKind) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenRoom: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var about by remember { mutableStateOf(false) }
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text("Reports") }, actions = {
            IconButton(onClick = { about = true }) { Icon(Icons.Filled.Info, "About these reports") }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, "Settings") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(GoodHiveSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.lg)) {
            item { PeriodControls(state.kind, state.periodLabel, state.canGoNext, onSelectKind, onPrevious, onNext) }
            state.sync?.let { sync -> item {
                if (!sync.active && sync.issueCount == 0 && sync.unsupportedCount == 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("History stored · ${sync.roomsTotal} rooms", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRetry) { Text("Sync now") }
                    }
                } else ReportCard(if (sync.active) "Collecting history" else "Stored history") {
                    Text("${sync.roomsDone} of ${sync.roomsTotal} rooms backfilled")
                    if (sync.active) {
                        LinearProgressIndicator(progress = { sync.fraction }, modifier = Modifier.fillMaxWidth())
                        Text("Reports update as readings arrive. Downloading may wait for network or retry after Hive limits requests.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (sync.issueCount > 0) Text("${sync.issueCount} sources have gaps or sync errors. Available readings are shown.")
                    if (sync.unsupportedCount > 0) Text("${sync.unsupportedCount} sources are unavailable from Hive.")
                    TextButton(onClick = onRetry) { Text(if (sync.issueCount > 0) "Retry sync" else "Sync now") }
                }
            } }
            item { ReportStatus(state.status, state.error, onRetry) }
            state.hero?.let { hero -> item {
                ReportCard("Whole-home average") {
                    Text(deg(hero.avgC), style = MaterialTheme.typography.displayMedium)
                    hero.deltaC?.let { Text(comparisonPhrase(it, state.comparisonLabel)) }
                    hero.comparisonNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    hero.coverageNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    hero.warmest?.let { Text("Warmest observed: ${it.name} · ${deg(it.avgC)}") }
                    hero.coolest?.let { Text("Coolest observed: ${it.name} · ${deg(it.avgC)}") }
                }
            } }
            state.brief?.let { brief -> item { ReportCard(brief.title) { Text(brief.text) } } }
            state.dailyChart?.let { item { ReportLineChart("Daily averages", it) } }
            items(state.insights, key = { "${it.zoneId}-${it.type}" }) { insight -> InsightCards(listOf(insight), onOpenRoom) }
            if (state.timeOnTarget.isNotEmpty()) item {
                ReportCard("Readings near scheduled target") {
                    state.timeOnTarget.forEach { row ->
                        ListItem(headlineContent = { Text(row.name) },
                            supportingContent = { Text(row.detail ?: "Estimate · ±0.5° of scheduled target") },
                            trailingContent = { Text(row.share?.let(::pct) ?: "–") },
                            modifier = Modifier.clickable { onOpenRoom(row.zoneId) }.heightIn(min = GoodHiveSpacing.touchTarget))
                    }
                    Text(state.timeOnTargetNote ?: "Within ±0.5° of scheduled comfort targets. Sample-based estimate using current schedules where needed.", style = MaterialTheme.typography.bodySmall)
                    Text("A low share may mean the room is above target. This is not a heating-performance score.", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.year?.let { year ->
                year.monthly?.let { item { ReportLineChart("Monthly averages", it) } }
                year.partialNote?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
                if (year.rooms.isNotEmpty()) item {
                    ReportCard("Rooms this year") {
                        year.rooms.forEach { room ->
                            ListItem(headlineContent = { Text(room.name) },
                                supportingContent = { Text(buildString {
                                    append(room.deltaC?.let { "${comparisonPhrase(it, "last year")} · matched days" } ?: "No matching comparison")
                                    if (room.partial) append(" · partial year")
                                }) }, trailingContent = { Text(room.avgC?.let(::deg) ?: "–") },
                                modifier = Modifier.clickable { onOpenRoom(room.zoneId) }.heightIn(min = GoodHiveSpacing.touchTarget))
                        }
                    }
                }
                year.winter?.let { winter -> item {
                    ReportCard(winter.label) {
                        winter.rows.forEach { row ->
                            Text("${row.name}: ${row.avgC?.let(::deg) ?: "No readings"}${if (row.partial) " · partial" else ""}")
                            Text(if (row.observedNights == 0) "No complete nights observed" else
                                "${row.nightsBelow} of ${row.observedNights} observed nights below ${deg(winter.thresholdC)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } }
            }
            if (state.roomLinks.isNotEmpty()) item { Text("Room reports", style = MaterialTheme.typography.titleLarge) }
            items(state.roomLinks, key = { "room-${it.first}" }) { (id, name) ->
                OutlinedCard(onClick = { onOpenRoom(id) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(headlineContent = { Text(name) }, supportingContent = { Text("Open ${state.kind.noun} report") })
                }
            }
        }
    }
    if (about) ReportsAbout { about = false }
}
