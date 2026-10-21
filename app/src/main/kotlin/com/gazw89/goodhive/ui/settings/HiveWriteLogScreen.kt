package com.gazw89.goodhive.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.data.network.HiveWriteKindFilter
import com.gazw89.goodhive.data.network.HiveWriteResultFilter
import com.gazw89.goodhive.data.network.HiveWriteSort
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HiveWriteLogScreen(
    onBack: () -> Unit,
    onOpenWrite: (Long) -> Unit,
    viewModel: HiveWriteLogViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var sortMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val rowColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hive writes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { sortMenu = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        HiveWriteSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    viewModel.setSort(option)
                                    sortMenu = false
                                },
                                trailingIcon = if (ui.filters.sort == option) {
                                    { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = GoodHiveSpacing.lg,
                vertical = GoodHiveSpacing.lg,
            ),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
        ) {
            item {
                Text(
                    text = "Temperature, mode, schedule, boost, and Heat on Demand changes sent to Hive. Kept on this phone for 30 days.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!ui.logEmpty) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md)) {
                        FilterGroup(label = "Result") {
                            HiveWriteResultFilter.entries.forEach { option ->
                                ChoiceChip(
                                    label = option.label,
                                    selected = ui.filters.result == option,
                                    onClick = { viewModel.setResult(option) },
                                )
                            }
                        }
                        FilterGroup(label = "Change") {
                            HiveWriteKindFilter.entries.forEach { option ->
                                ChoiceChip(
                                    label = option.label,
                                    selected = ui.filters.kind == option,
                                    onClick = { viewModel.setKind(option) },
                                )
                            }
                        }
                        FilterGroup(label = "Room") {
                            ChoiceChip(
                                label = "All rooms",
                                selected = ui.filters.roomId == null,
                                onClick = { viewModel.setRoom(null) },
                            )
                            ui.rooms.forEach { room ->
                                ChoiceChip(
                                    label = room.label,
                                    selected = ui.filters.roomId == room.zoneId,
                                    onClick = { viewModel.setRoom(room.zoneId) },
                                )
                            }
                        }
                    }
                }
            }
            when {
                ui.logEmpty -> item {
                    Text(
                        text = "No Hive writes yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ui.filtersHideAll -> item {
                    Text(
                        text = "No writes match these filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(ui.rows, key = { it.id }) { row ->
                    Column {
                        ListItem(
                            headlineContent = { Text(row.title) },
                            supportingContent = { WriteSupportingLine(row) },
                            trailingContent = {
                                Icon(
                                    Icons.AutoMirrored.Filled.NavigateNext,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = rowColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenWrite(row.id) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
            if (!ui.logEmpty) {
                item {
                    TextButton(onClick = { confirmClear = true }) {
                        Text("Clear log", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear Hive writes?") },
            text = {
                Text("This removes the saved write log from this phone, including rows hidden by the current filters. Hive is not changed.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clear()
                        confirmClear = false
                    },
                ) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WriteSupportingLine(row: HiveWriteRow) {
    val code = row.responseCode?.toString() ?: "No response"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val codeColor = if (row.failed) MaterialTheme.colorScheme.error else muted
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) { append("${row.summary} · ") }
            withStyle(SpanStyle(color = codeColor)) { append(code) }
            withStyle(SpanStyle(color = muted)) { append(" · ${row.durationMs} ms · ${row.whenLabel}") }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
        ) {
            content()
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.defaultMinSize(minHeight = GoodHiveSpacing.touchTarget),
    )
}

private val HiveWriteSort.label: String
    get() = when (this) {
        HiveWriteSort.Newest -> "Newest"
        HiveWriteSort.Oldest -> "Oldest"
    }

private val HiveWriteResultFilter.label: String
    get() = when (this) {
        HiveWriteResultFilter.All -> "All"
        HiveWriteResultFilter.Succeeded -> "Succeeded"
        HiveWriteResultFilter.Failed -> "Failed"
    }

private val HiveWriteKindFilter.label: String
    get() = when (this) {
        HiveWriteKindFilter.All -> "All"
        HiveWriteKindFilter.Temperature -> "Temperature"
        HiveWriteKindFilter.Mode -> "Mode"
        HiveWriteKindFilter.Boost -> "Boost"
        HiveWriteKindFilter.Schedule -> "Schedule"
        HiveWriteKindFilter.HeatOnDemand -> "Heat on Demand"
    }
