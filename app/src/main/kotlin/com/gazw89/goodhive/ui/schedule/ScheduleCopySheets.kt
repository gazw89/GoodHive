package com.gazw89.goodhive.ui.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.util.toSteps
import com.gazw89.goodhive.util.toTimeLabel

private val WEEKDAY_KEYS = WeekSchedule.DAY_KEYS.take(5).toSet()
private val WEEKEND_KEYS = WeekSchedule.DAY_KEYS.takeLast(2).toSet()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CopyDaySheet(
    sourceDay: String,
    onDismiss: () -> Unit,
    onPreview: (Set<String>) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val selectableDays = remember(sourceDay) { WeekSchedule.DAY_KEYS.filter { it != sourceDay } }
    var selected by remember { mutableStateOf(emptySet<String>()) }

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
        ) {
            Text(
                text = "Copy ${dayLabel(sourceDay)} to…",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Choose which days should get this schedule",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.md),
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
            ) {
                QuickSelectChip(
                    label = "Weekdays",
                    selected = selected == WEEKDAY_KEYS.intersect(selectableDays.toSet()),
                    onClick = {
                        selected = WEEKDAY_KEYS.intersect(selectableDays.toSet())
                    },
                )
                QuickSelectChip(
                    label = "Weekend",
                    selected = selected == WEEKEND_KEYS.intersect(selectableDays.toSet()),
                    onClick = {
                        selected = WEEKEND_KEYS.intersect(selectableDays.toSet())
                    },
                )
                QuickSelectChip(
                    label = "All other days",
                    selected = selected == selectableDays.toSet(),
                    onClick = { selected = selectableDays.toSet() },
                )
            }

            Spacer(Modifier.height(GoodHiveSpacing.md))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
            ) {
                selectableDays.forEach { day ->
                    FilterChip(
                        selected = day in selected,
                        onClick = {
                            selected = if (day in selected) selected - day else selected + day
                        },
                        label = { Text(day.take(3).replaceFirstChar { it.uppercase() }) },
                        shape = MaterialTheme.shapes.medium,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(GoodHiveSpacing.lg))
            SheetActionRow(
                onCancel = onDismiss,
                confirmLabel = "Preview",
                onConfirm = { onPreview(selected) },
                confirmEnabled = selected.isNotEmpty(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CopyToZoneSheet(
    otherZones: List<HeatingZone>,
    onDismiss: () -> Unit,
    onSelectZone: (String) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

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
        ) {
            Text(
                text = "Copy schedule to another zone",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "The whole week will be copied. You'll preview changes before applying.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.md),
            )

            LazyColumn {
                items(otherZones, key = { it.id }) { zone ->
                    ListItem(
                        headlineContent = { Text(zone.name) },
                        supportingContent = { Text(productTypeLabel(zone.productType)) },
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onSelectZone(zone.id) },
                    )
                }
            }

            Spacer(Modifier.height(GoodHiveSpacing.md))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulePreviewSheet(
    preview: SchedulePreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        when (preview) {
            is SchedulePreview.CopyDay -> CopyDayPreviewContent(
                preview = preview,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
            is SchedulePreview.CopyToZone -> CopyToZonePreviewContent(
                preview = preview,
                onDismiss = onDismiss,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun CopyDayPreviewContent(
    preview: SchedulePreview.CopyDay,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val dayCount = preview.targetDays.size
    val dayWord = if (dayCount == 1) "day" else "days"
    val sortedSlots = preview.slots.sortedBy { it.startMinutes }
    val summary = when {
        sortedSlots.isEmpty() -> "Those days will be cleared."
        else -> {
            val first = sortedSlots.first().startMinutes.toTimeLabel()
            val last = sortedSlots.last().startMinutes.toTimeLabel()
            val changeWord = if (sortedSlots.size == 1) "change" else "changes"
            "${sortedSlots.size} $changeWord · $first–$last"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GoodHiveSpacing.xl)
            .padding(bottom = GoodHiveSpacing.xl),
    ) {
        Text(
            text = "Preview copy",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Copy ${dayLabel(preview.sourceDay)} → $dayCount $dayWord",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.md),
        )

        // A bare slot list has no known carry-over (it depends on each target day's neighbour), so the
        // stretch before the first change stays neutral here.
        LabeledDayTimeline(
            steps = remember(preview.slots) { preview.slots.toSteps() },
            minTempC = preview.minTemperatureC,
            maxTempC = preview.maxTemperatureC,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = GoodHiveSpacing.md),
        )

        Spacer(Modifier.height(GoodHiveSpacing.lg))
        SheetActionRow(
            onCancel = onDismiss,
            confirmLabel = "Apply",
            onConfirm = onConfirm,
        )
    }
}

@Composable
private fun CopyToZonePreviewContent(
    preview: SchedulePreview.CopyToZone,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GoodHiveSpacing.xl)
            .padding(bottom = GoodHiveSpacing.xl),
    ) {
        Text(
            text = "Preview copy",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Copy ${preview.sourceZoneName} → ${preview.target.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.md),
        )

        WeekSchedule.DAY_KEYS.forEach { day ->
            val status = when {
                day in preview.blockedDays -> {
                    val count = preview.blockedDays.getValue(day)
                    "Blocked — $count changes (max ${preview.target.maxEventsPerDay})"
                }
                day in preview.clampedDays -> "Temps adjusted to this zone's range"
                else -> "Ready to copy"
            }
            val statusColor = when {
                day in preview.blockedDays -> MaterialTheme.colorScheme.error
                day in preview.clampedDays -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            ListItem(
                headlineContent = { Text(dayLabel(day)) },
                supportingContent = {
                    Text(status, color = statusColor, style = MaterialTheme.typography.bodySmall)
                },
            )
        }

        Spacer(Modifier.height(GoodHiveSpacing.md))
        SheetActionRow(
            onCancel = onDismiss,
            confirmLabel = "Apply",
            onConfirm = onConfirm,
            confirmEnabled = preview.canApply,
        )
    }
}

@Composable
private fun QuickSelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = MaterialTheme.shapes.medium,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

private fun productTypeLabel(productType: String): String = when (productType) {
    ProductType.HEATING -> "Thermostat"
    ProductType.TRV_CONTROL -> "Radiator valve"
    else -> productType
}
