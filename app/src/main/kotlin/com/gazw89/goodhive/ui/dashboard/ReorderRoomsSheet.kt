package com.gazw89.goodhive.ui.dashboard

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Preserve the draft across polls, removing missing rooms and appending newly discovered ones. */
internal fun reconcileRoomOrder(draft: List<String>, available: List<String>): List<String> {
    val availableIds = available.toSet()
    val retained = draft.filter { it in availableIds }.distinct()
    return retained + available.filter { it !in retained }.distinct()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReorderRoomsSheet(
    rooms: List<HeatingZone>,
    onDismiss: () -> Unit,
    onSave: suspend (List<String>) -> Unit,
) {
    val availableIds = rooms.map { it.id }
    var draftIds by rememberSaveable { mutableStateOf(availableIds) }
    val orderedIds = reconcileRoomOrder(draftIds, availableIds)
    val roomsById = rooms.associateBy { it.id }
    var isDragging by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reducedMotion = LocalReducedMotion.current
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { !isSaving },
    )
    val listState = rememberLazyListState()

    fun moveRoom(from: Int, to: Int): Boolean {
        if (isSaving || from !in orderedIds.indices || to !in orderedIds.indices || from == to) return false
        draftIds = orderedIds.toMutableList().apply { add(to, removeAt(from)) }
        saveError = null
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        return true
    }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        moveRoom(orderedIds.indexOf(from.key), orderedIds.indexOf(to.key))
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !isDragging && !isSaving,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = GoodHiveSpacing.lg)) {
            Text("Reorder rooms", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Drag the handles to arrange your rooms. The thermostat stays at the top.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GoodHiveSpacing.sm, bottom = GoodHiveSpacing.lg),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(bottom = GoodHiveSpacing.sm),
            ) {
                itemsIndexed(orderedIds, key = { _, id -> id }) { index, id ->
                    val room = roomsById.getValue(id)
                    ReorderableItem(
                        reorderState,
                        key = id,
                        enabled = !isSaving,
                        animateItemModifier = Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = GoodHiveMotion.settleSpring(reducedMotion),
                        ),
                    ) { isBeingDragged ->
                        val interaction = remember { MutableInteractionSource() }
                        var showMoveMenu by remember { mutableStateOf(false) }
                        val actions = if (isSaving) emptyList() else buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move up") { moveRoom(index, index - 1) })
                            if (index < orderedIds.lastIndex) {
                                add(CustomAccessibilityAction("Move down") { moveRoom(index, index + 1) })
                            }
                        }
                        ListItem(
                            content = { Text(room.name) },
                            supportingContent = { Text("${index + 1} of ${orderedIds.size}") },
                            colors = ListItemDefaults.colors(
                                containerColor = if (isBeingDragged) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                customActions = actions
                            },
                            trailingContent = {
                                Box {
                                    IconButton(
                                        onClick = { showMoveMenu = true },
                                        enabled = !isSaving && orderedIds.size > 1,
                                        interactionSource = interaction,
                                        modifier = Modifier
                                            .draggableHandle(
                                                enabled = !isSaving,
                                                interactionSource = interaction,
                                                onDragStarted = {
                                                    isDragging = true
                                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                                },
                                                onDragStopped = {
                                                    isDragging = false
                                                    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                },
                                            )
                                            .semantics {
                                                contentDescription = "Reorder ${room.name}"
                                                stateDescription = "Position ${index + 1} of ${orderedIds.size}"
                                                customActions = actions
                                            },
                                    ) {
                                        Icon(Icons.Rounded.DragHandle, contentDescription = null)
                                    }
                                    DropdownMenu(
                                        expanded = showMoveMenu,
                                        onDismissRequest = { showMoveMenu = false },
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Move up") },
                                            enabled = !isSaving && index > 0,
                                            onClick = {
                                                showMoveMenu = false
                                                moveRoom(index, index - 1)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Move down") },
                                            enabled = !isSaving && index < orderedIds.lastIndex,
                                            onClick = {
                                                showMoveMenu = false
                                                moveRoom(index, index + 1)
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            saveError?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = GoodHiveSpacing.sm).semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = GoodHiveSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
            ) {
                TextButton(onClick = onDismiss, enabled = !isSaving, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    enabled = !isSaving && !isDragging && orderedIds != availableIds,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        isSaving = true
                        saveError = null
                        scope.launch {
                            try {
                                onSave(orderedIds)
                                isSaving = false
                                sheetState.hide()
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                saveError = "Couldn't save room order. Try again."
                            } finally {
                                isSaving = false
                            }
                        }
                    },
                ) {
                    Text(if (isSaving) "Saving…" else "Save")
                }
            }
        }
    }
}
