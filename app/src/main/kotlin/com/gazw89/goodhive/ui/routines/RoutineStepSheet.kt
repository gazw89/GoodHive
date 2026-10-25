package com.gazw89.goodhive.ui.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.canControl
import com.gazw89.goodhive.data.model.routines.BoostRoom
import com.gazw89.goodhive.data.model.routines.ModeRoom
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.ui.components.BoostWheels
import com.gazw89.goodhive.ui.components.TemperatureWheel
import com.gazw89.goodhive.ui.components.boostDurationsMinutes
import com.gazw89.goodhive.ui.components.defaultBoostTargetC
import com.gazw89.goodhive.ui.components.formatBoostDuration
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.clampToTemperatureStep
import com.gazw89.goodhive.ui.theme.temperatureSteps

enum class RoutineStepType(val title: String, val description: String) {
    BOOST("Boost", "Heat rooms to a temperature for a set time"),
    SET_MODE("Set mode", "Switch rooms to Schedule, Manual or Off"),
    CANCEL_BOOST("Cancel boost", "End boosts and go back to what each room was doing"),
}

internal fun RoutineStep.type(): RoutineStepType = when (this) {
    is RoutineStep.Boost -> RoutineStepType.BOOST
    is RoutineStep.SetMode -> RoutineStepType.SET_MODE
    is RoutineStep.CancelBoost -> RoutineStepType.CANCEL_BOOST
}

/** Shared starting values for rooms ticked in a new step. */
private const val NEW_STEP_MINUTES = 60
private const val NEW_MANUAL_TARGET_C = 20.0
private val MODES = listOf(HeatingMode.SCHEDULE, HeatingMode.MANUAL, HeatingMode.OFF)

/**
 * Edits one step. Rooms are picked with chips. For Boost and Set mode, the wheels edit either "All
 * rooms" (every ticked room gets the new value) or a single room picked in the "Adjust" row. A newly
 * ticked boost room starts at its own suggestion (room temperature + 3°) and the shared duration.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoutineStepSheet(
    type: RoutineStepType,
    initial: RoutineStep?,
    zones: List<HeatingZone>,
    onDismiss: () -> Unit,
    onSave: (RoutineStep) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val zonesById = zones.associateBy { it.id }
    // Rooms in the order the step will run them; values per room.
    val boostRooms = remember { mutableStateListOf<BoostRoom>().apply { addAll((initial as? RoutineStep.Boost)?.rooms.orEmpty()) } }
    val modeRooms = remember { mutableStateListOf<ModeRoom>().apply { addAll((initial as? RoutineStep.SetMode)?.rooms.orEmpty()) } }
    val cancelIds = remember { mutableStateListOf<String>().apply { addAll((initial as? RoutineStep.CancelBoost)?.zoneIds.orEmpty()) } }
    var sharedMinutes by remember { mutableStateOf(boostRooms.firstOrNull()?.minutes ?: NEW_STEP_MINUTES) }
    var sharedBoostTarget by remember { mutableStateOf(boostRooms.firstOrNull()?.targetC ?: 21.0) }
    var sharedMode by remember { mutableStateOf(modeRooms.firstOrNull()?.mode ?: HeatingMode.SCHEDULE) }
    var sharedManualTarget by remember { mutableStateOf(modeRooms.firstOrNull()?.targetC ?: NEW_MANUAL_TARGET_C) }
    /** Null edits all rooms; otherwise the one room whose values the wheels show. */
    var adjusting by remember { mutableStateOf<String?>(null) }

    val selectedIds: List<String> = when (type) {
        RoutineStepType.BOOST -> boostRooms.map { it.zoneId }
        RoutineStepType.SET_MODE -> modeRooms.map { it.zoneId }
        RoutineStepType.CANCEL_BOOST -> cancelIds.toList()
    }
    if (adjusting != null && adjusting !in selectedIds) adjusting = null

    fun toggle(zone: HeatingZone) {
        when (type) {
            RoutineStepType.BOOST -> {
                val index = boostRooms.indexOfFirst { it.zoneId == zone.id }
                if (index >= 0) boostRooms.removeAt(index) else {
                    val suggested = defaultBoostTargetC(
                        currentTemperatureC = zone.currentTemperatureC.takeIf { zone.canControl },
                        currentTargetC = zone.targetTemperatureC,
                        minC = zone.minTemperatureC,
                        maxC = zone.maxTemperatureC,
                    )
                    boostRooms += BoostRoom(zone.id, suggested, sharedMinutes)
                }
            }
            RoutineStepType.SET_MODE -> {
                val index = modeRooms.indexOfFirst { it.zoneId == zone.id }
                if (index >= 0) modeRooms.removeAt(index)
                else modeRooms += ModeRoom(zone.id, sharedMode, sharedManualTarget.takeIf { sharedMode == HeatingMode.MANUAL })
            }
            RoutineStepType.CANCEL_BOOST -> if (zone.id in cancelIds) cancelIds.remove(zone.id) else cancelIds += zone.id
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GoodHiveSpacing.xl)
                .padding(bottom = GoodHiveSpacing.xl),
        ) {
            Text(type.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                type.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.lg),
            )
            SectionLabel("Rooms")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
                zones.forEach { zone ->
                    val selected = zone.id in selectedIds
                    FilterChip(
                        selected = selected,
                        onClick = { toggle(zone) },
                        label = { Text(zone.routineLabel()) },
                        leadingIcon = if (selected) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null,
                    )
                }
            }
            val missing = selectedIds.count { it !in zonesById }
            if (missing > 0) {
                Text(
                    if (missing == 1) "1 room is no longer on your account and will be skipped" else "$missing rooms are no longer on your account and will be skipped",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = GoodHiveSpacing.xs),
                )
            }

            if (type != RoutineStepType.CANCEL_BOOST && selectedIds.isNotEmpty()) {
                SectionLabel("Adjust", Modifier.padding(top = GoodHiveSpacing.lg))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
                    FilterChip(
                        selected = adjusting == null,
                        onClick = { adjusting = null },
                        label = { Text("All rooms") },
                    )
                    selectedIds.forEach { id ->
                        val name = zonesById[id]?.name ?: "Missing room"
                        val value = when (type) {
                            RoutineStepType.BOOST -> boostRooms.first { it.zoneId == id }.let {
                                "${formatRoutineTemp(it.targetC)} · ${formatBoostDuration(it.minutes)}"
                            }
                            else -> modeRooms.first { it.zoneId == id }.let {
                                if (it.mode == HeatingMode.MANUAL && it.targetC != null) "Manual ${formatRoutineTemp(it.targetC)}" else modeLabel(it.mode)
                            }
                        }
                        FilterChip(selected = adjusting == id, onClick = { adjusting = id }, label = { Text("$name  $value") })
                    }
                }
                Text(
                    if (adjusting == null) "Changes here apply to every room above." else "Changes apply to ${zonesById[adjusting]?.name ?: "this room"} only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.sm),
                )
                val editingZones = adjusting?.let { listOfNotNull(zonesById[it]) } ?: selectedIds.mapNotNull { zonesById[it] }
                val minC = editingZones.minOfOrNull { it.minTemperatureC } ?: 5.0
                val maxC = editingZones.maxOfOrNull { it.maxTemperatureC } ?: 32.0
                val steps = remember(minC, maxC) { temperatureSteps(minC, maxC) }
                fun indexOf(valueC: Double) = steps.indexOf(clampToTemperatureStep(valueC, minC, maxC)).coerceAtLeast(0)

                // Re-key so the wheels open on the values of whatever is being adjusted.
                key(adjusting, type) {
                    if (type == RoutineStepType.BOOST) {
                        val room = adjusting?.let { id -> boostRooms.first { it.zoneId == id } }
                        val target = room?.targetC ?: sharedBoostTarget
                        val minutes = room?.minutes ?: sharedMinutes
                        BoostWheels(
                            steps = steps,
                            tempIndex = indexOf(target),
                            onTempIndexChange = { i ->
                                val value = steps[i]
                                if (room == null) {
                                    sharedBoostTarget = value
                                    boostRooms.replaceAll { it.copy(targetC = value) }
                                } else boostRooms.replaceAll { if (it.zoneId == room.zoneId) it.copy(targetC = value) else it }
                            },
                            durationIndex = boostDurationsMinutes.indexOf(minutes).coerceAtLeast(0),
                            onDurationIndexChange = { i ->
                                val value = boostDurationsMinutes[i]
                                if (room == null) {
                                    sharedMinutes = value
                                    boostRooms.replaceAll { it.copy(minutes = value) }
                                } else boostRooms.replaceAll { if (it.zoneId == room.zoneId) it.copy(minutes = value) else it }
                            },
                        )
                    } else {
                        val room = adjusting?.let { id -> modeRooms.first { it.zoneId == id } }
                        val mode = room?.mode ?: sharedMode
                        val target = room?.targetC ?: sharedManualTarget
                        fun apply(transform: (ModeRoom) -> ModeRoom) {
                            modeRooms.replaceAll { if (room == null || it.zoneId == room.zoneId) transform(it) else it }
                        }
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            MODES.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = mode == option,
                                    onClick = {
                                        if (room == null) sharedMode = option
                                        apply { it.copy(mode = option, targetC = if (option == HeatingMode.MANUAL) it.targetC ?: target else null) }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index, MODES.size),
                                ) { Text(modeLabel(option)) }
                            }
                        }
                        if (mode == HeatingMode.MANUAL) {
                            TemperatureWheel(
                                steps = steps,
                                selectedIndex = indexOf(target),
                                onSelectedIndexChange = { i ->
                                    val value = steps[i]
                                    if (room == null) sharedManualTarget = value
                                    apply { if (it.mode == HeatingMode.MANUAL) it.copy(targetC = value) else it }
                                },
                                contentDescription = "Manual temperature",
                                modifier = Modifier.fillMaxWidth().padding(top = GoodHiveSpacing.md),
                            )
                        } else {
                            Text(
                                if (mode == HeatingMode.OFF) "Rooms drop to their frost protection temperature."
                                else "Rooms follow their schedule from its current slot.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = GoodHiveSpacing.sm),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = GoodHiveSpacing.xl),
                horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSave(
                            when (type) {
                                RoutineStepType.BOOST -> RoutineStep.Boost(boostRooms.toList())
                                RoutineStepType.SET_MODE -> RoutineStep.SetMode(modeRooms.toList())
                                RoutineStepType.CANCEL_BOOST -> RoutineStep.CancelBoost(cancelIds.toList())
                            },
                        )
                    },
                ) { Text(if (initial == null) "Add step" else "Save step") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = GoodHiveSpacing.xs),
    )
}
