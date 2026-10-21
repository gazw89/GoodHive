package com.gazw89.goodhive.ui.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.ui.components.WheelPicker
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.ScheduleTimeTextStyle
import com.gazw89.goodhive.ui.theme.temperatureColor
import com.gazw89.goodhive.ui.theme.temperatureSteps
import com.gazw89.goodhive.util.ScheduleStep
import com.gazw89.goodhive.util.mergedByTarget
import com.gazw89.goodhive.util.stepsForDay
import com.gazw89.goodhive.util.toSteps
import com.gazw89.goodhive.util.toTimeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(
    onBack: () -> Unit,
    viewModel: ScheduleViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var editingSlot by remember { mutableStateOf<Pair<Int, ScheduleSlot>?>(null) }
    var addingSlot by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showCopyDaySheet by remember { mutableStateOf(false) }
    var showCopyToZoneSheet by remember { mutableStateOf(false) }
    val zone = uiState.zone
    val otherZonesForCopy = uiState.otherZones

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = zone?.name ?: "",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "Heat schedule",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy day to…") },
                            onClick = {
                                menuExpanded = false
                                showCopyDaySheet = true
                            },
                        )
                        if (otherZonesForCopy.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Copy schedule to another zone") },
                                onClick = {
                                    menuExpanded = false
                                    showCopyToZoneSheet = true
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
        floatingActionButton = {
            if (zone != null && uiState.slots.size < zone.maxEventsPerDay) {
                FloatingActionButton(
                    onClick = { addingSlot = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add time")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        if (zone == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        HeatScheduleBody(
            zone = zone,
            uiState = uiState,
            onSelectDay = viewModel::selectDay,
            onEditSlot = { index, slot -> editingSlot = index to slot },
            onDeleteSlot = { index ->
                val updated = uiState.slots.toMutableList().apply { removeAt(index) }
                viewModel.saveDay(uiState.selectedDay, updated)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        )
    }

    val currentZone = zone
    if (currentZone != null) {
        editingSlot?.let { (index, slot) ->
            SlotEditSheet(
                zone = currentZone,
                initial = slot,
                onDismiss = { editingSlot = null },
                onConfirm = { newSlot ->
                    val updated = uiState.slots.toMutableList().apply { set(index, newSlot) }
                    viewModel.saveDay(uiState.selectedDay, updated)
                    editingSlot = null
                },
            )
        }

        if (addingSlot) {
            SlotEditSheet(
                zone = currentZone,
                initial = ScheduleSlot(startMinutes = 480, targetTemperatureC = 20.0),
                onDismiss = { addingSlot = false },
                onConfirm = { newSlot ->
                    viewModel.saveDay(uiState.selectedDay, uiState.slots + newSlot)
                    addingSlot = false
                },
            )
        }
    }

    if (showCopyDaySheet) {
        CopyDaySheet(
            sourceDay = uiState.selectedDay,
            onDismiss = { showCopyDaySheet = false },
            onPreview = { targets ->
                showCopyDaySheet = false
                viewModel.prepareCopyDayPreview(targets)
            },
        )
    }

    if (showCopyToZoneSheet) {
        CopyToZoneSheet(
            otherZones = otherZonesForCopy,
            onDismiss = { showCopyToZoneSheet = false },
            onSelectZone = { targetZoneId ->
                showCopyToZoneSheet = false
                viewModel.prepareCopyToZonePreview(targetZoneId)
            },
        )
    }

    uiState.pendingPreview?.let { preview ->
        SchedulePreviewSheet(
            preview = preview,
            onDismiss = { viewModel.dismissPreview() },
            onConfirm = { viewModel.confirmPendingPreview() },
        )
    }
}

@Composable
private fun HeatScheduleBody(
    zone: HeatingZone,
    uiState: ScheduleUiState,
    onSelectDay: (String) -> Unit,
    onEditSlot: (Int, ScheduleSlot) -> Unit,
    onDeleteSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        uiState.lastFailureMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GoodHiveSpacing.lg, vertical = GoodHiveSpacing.sm),
            )
        }

        DayStrip(
            selectedDay = uiState.selectedDay,
            onSelectDay = onSelectDay,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GoodHiveSpacing.lg, vertical = GoodHiveSpacing.md),
        )

        // Includes the stretch before the day's first change, carried over from the previous day.
        val steps = remember(zone.schedule, uiState.selectedDay) { zone.schedule.stepsForDay(uiState.selectedDay) }
        LabeledDayTimeline(
            steps = steps,
            minTempC = zone.minTemperatureC,
            maxTempC = zone.maxTemperatureC,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GoodHiveSpacing.lg),
        )

        Text(
            text = "Up to ${zone.maxEventsPerDay} changes per day",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                start = GoodHiveSpacing.lg,
                end = GoodHiveSpacing.lg,
                top = GoodHiveSpacing.md,
                bottom = GoodHiveSpacing.sm,
            ),
        )

        if (uiState.slots.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = GoodHiveSpacing.xl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No schedule changes",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Tap + to add a temperature change for this day",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = GoodHiveSpacing.xs),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = GoodHiveSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
                contentPadding = PaddingValues(
                    bottom = GoodHiveSpacing.xxl + GoodHiveSpacing.xl,
                ),
            ) {
                itemsIndexed(uiState.slots, key = { _, slot -> slot.startMinutes }) { index, slot ->
                    val untilMinutes = uiState.slots.getOrNull(index + 1)?.startMinutes
                    SlotCard(
                        slot = slot,
                        untilMinutes = untilMinutes,
                        minTempC = zone.minTemperatureC,
                        maxTempC = zone.maxTemperatureC,
                        onEdit = { onEditSlot(index, slot) },
                        onDelete = { onDeleteSlot(index) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DayStrip(
    selectedDay: String,
    onSelectDay: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedIndex = WeekSchedule.DAY_KEYS.indexOf(selectedDay).coerceAtLeast(0)
    val labelStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = remember { WeekSchedule.DAY_KEYS.map { it.take(3).replaceFirstChar { c -> c.uppercase() } } }

    @Composable
    fun DayTabs() {
        WeekSchedule.DAY_KEYS.forEachIndexed { index, day ->
            Tab(
                selected = selectedDay == day,
                onClick = { onSelectDay(day) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                // The content overload has no built-in 16dp side padding, so "Mon" fits a seventh of the width.
                Text(
                    text = labels[index],
                    style = labelStyle,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(vertical = GoodHiveSpacing.md),
                )
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        // All seven days share the width unless large font scaling makes a label wider than its seventh;
        // only then fall back to a scrolling row so nothing is clipped or wrapped.
        val needed = with(density) {
            labels.maxOf { measurer.measure(it, labelStyle, softWrap = false, maxLines = 1).size.width }.toDp()
        } + GoodHiveSpacing.sm * 2
        if (maxWidth / WeekSchedule.DAY_KEYS.size >= needed) {
            PrimaryTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = MaterialTheme.colorScheme.background,
            ) { DayTabs() }
        } else {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.background,
            ) { DayTabs() }
        }
    }
}

/** Timeline bar plus 00 / 06 / 12 / 18 / 24 hour labels. */
@Composable
internal fun LabeledDayTimeline(
    steps: List<ScheduleStep>,
    minTempC: Double,
    maxTempC: Double,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        DayTimeline(
            steps = steps,
            minTempC = minTempC,
            maxTempC = maxTempC,
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(MaterialTheme.shapes.medium),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = GoodHiveSpacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("00", "06", "12", "18", "24").forEach { tick ->
                Text(
                    text = tick,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Read-only 24h visualization of the day's effective target: one colour band per [steps] entry,
 * sized by duration. Any minute no step covers (a bare slot list with no known carry-over, or an
 * empty week) shows the neutral surface colour.
 */
@Composable
internal fun DayTimeline(
    steps: List<ScheduleStep>,
    minTempC: Double,
    maxTempC: Double,
    modifier: Modifier = Modifier,
) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val bands = remember(steps) { steps.mergedByTarget() }
    Canvas(modifier = modifier) {
        drawRect(color = surfaceVariant)
        bands.forEach { step ->
            val startX = (step.startMinute / MINUTES_PER_DAY) * size.width
            val endX = (step.endMinute / MINUTES_PER_DAY) * size.width
            drawRect(
                color = temperatureColor(step.targetC, minTempC, maxTempC),
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, size.height),
            )
        }
    }
}

private const val MINUTES_PER_DAY = 1440f

/** End-of-day shows as 24:00 in the UI only; the stored schedule never contains it. */
internal fun Int?.toRangeEndLabel(): String = this?.toTimeLabel() ?: "24:00"

@Composable
private fun SlotCard(
    slot: ScheduleSlot,
    untilMinutes: Int?,
    minTempC: Double,
    maxTempC: Double,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = temperatureColor(slot.targetTemperatureC, minTempC, maxTempC)
    val start = slot.startMinutes.toTimeLabel()
    val end = untilMinutes.toRangeEndLabel()
    val temperature = "${"%.1f".format(slot.targetTemperatureC)}°"
    val separator = MaterialTheme.colorScheme.onSurfaceVariant
    val range = buildAnnotatedString {
        append(start)
        withStyle(SpanStyle(color = separator)) { append(" → ") }
        append(end)
    }

    Card(
        onClick = onEdit,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(4.dp)
                    .background(accent),
            )
            Text(
                text = range,
                style = ScheduleTimeTextStyle,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = GoodHiveSpacing.lg, vertical = GoodHiveSpacing.sm),
            )
            Text(
                text = temperature,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                maxLines = 1,
                modifier = Modifier.padding(end = GoodHiveSpacing.xs),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Remove $start to $end change",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SlotEditSheet(
    zone: HeatingZone,
    initial: ScheduleSlot,
    onDismiss: () -> Unit,
    onConfirm: (ScheduleSlot) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val hours = remember { (0..23).map { "%02d".format(it) } }
    val minutes = remember { (0..55 step 5).map { "%02d".format(it) } }
    val tempSteps = remember(zone.minTemperatureC, zone.maxTemperatureC) {
        temperatureSteps(zone.minTemperatureC, zone.maxTemperatureC)
    }
    val temps = remember(tempSteps) { tempSteps.map { "%.1f".format(it) } }

    var hourIndex by remember { mutableStateOf(initial.startMinutes / 60) }
    var minuteIndex by remember { mutableStateOf((initial.startMinutes % 60) / 5) }
    var tempIndex by remember {
        mutableStateOf(
            tempSteps.indexOf(initial.targetTemperatureC)
                .let { if (it < 0) 0 else it }
                .coerceIn(0, temps.lastIndex),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GoodHiveSpacing.xl)
                .padding(bottom = GoodHiveSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Schedule change",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = zone.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.lg),
            )

            Text(
                text = "Time",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = GoodHiveSpacing.md),
            ) {
                WheelPicker(
                    values = hours,
                    selectedIndex = hourIndex,
                    onSelectedIndexChange = { hourIndex = it },
                    contentDescription = "Hour",
                )
                Text(
                    ":",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = GoodHiveSpacing.xs),
                )
                WheelPicker(
                    values = minutes,
                    selectedIndex = minuteIndex,
                    onSelectedIndexChange = { minuteIndex = it },
                    contentDescription = "Minute",
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = GoodHiveSpacing.section))

            Text(
                text = "Target temperature",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = GoodHiveSpacing.md),
            ) {
                WheelPicker(
                    values = temps,
                    selectedIndex = tempIndex,
                    onSelectedIndexChange = { tempIndex = it },
                    contentDescription = "Temperature",
                )
                Text(
                    "°C",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(start = GoodHiveSpacing.sm),
                )
            }

            Spacer(Modifier.height(GoodHiveSpacing.lg))
            SheetActionRow(
                onCancel = onDismiss,
                confirmLabel = "Save",
                onConfirm = {
                    val minuteValue = minutes.getOrElse(minuteIndex) { "00" }.toInt()
                    val target = temps.getOrElse(tempIndex) {
                        "%.1f".format(initial.targetTemperatureC)
                    }.toDouble()
                    onConfirm(ScheduleSlot(startMinutes = hourIndex * 60 + minuteValue, targetTemperatureC = target))
                },
            )
        }
    }
}

@Composable
internal fun SheetActionRow(
    onCancel: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) { Text("Cancel") }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) { Text(confirmLabel) }
    }
}
