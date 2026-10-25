package com.gazw89.goodhive.ui.components

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
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** One row in a [ReorderSheet]. */
data class ReorderItem(val id: String, val label: String)

/** Preserve the draft across updates, removing missing items and appending newly added ones. */
fun reconcileOrder(draft: List<String>, available: List<String>): List<String> {
    val availableIds = available.toSet()
    val retained = draft.filter { it in availableIds }.distinct()
    return retained + available.filter { it !in retained }.distinct()
}

/**
 * Drag-to-reorder sheet with Move up / Move down in a menu and as accessibility actions. [onSave]
 * receives the ids in their new order; if it throws, [saveErrorMessage] is shown and the sheet stays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderSheet(
    title: String,
    description: String,
    items: List<ReorderItem>,
    saveErrorMessage: String,
    onDismiss: () -> Unit,
    onSave: suspend (List<String>) -> Unit,
) {
    val availableIds = items.map { it.id }
    var draftIds by rememberSaveable { mutableStateOf(availableIds) }
    val orderedIds = reconcileOrder(draftIds, availableIds)
    val itemsById = items.associateBy { it.id }
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

    fun moveItem(from: Int, to: Int): Boolean {
        if (isSaving || from !in orderedIds.indices || to !in orderedIds.indices || from == to) return false
        draftIds = orderedIds.toMutableList().apply { add(to, removeAt(from)) }
        saveError = null
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        return true
    }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        moveItem(orderedIds.indexOf(from.key), orderedIds.indexOf(to.key))
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState,
        sheetGesturesEnabled = !isDragging && !isSaving,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = GoodHiveSpacing.lg)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                description,
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
                    val item = itemsById.getValue(id)
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
                            if (index > 0) add(CustomAccessibilityAction("Move up") { moveItem(index, index - 1) })
                            if (index < orderedIds.lastIndex) {
                                add(CustomAccessibilityAction("Move down") { moveItem(index, index + 1) })
                            }
                        }
                        ListItem(
                            content = { Text(item.label) },
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
                                                contentDescription = "Reorder ${item.label}"
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
                                                moveItem(index, index - 1)
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Move down") },
                                            enabled = !isSaving && index < orderedIds.lastIndex,
                                            onClick = {
                                                showMoveMenu = false
                                                moveItem(index, index + 1)
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
                                saveError = saveErrorMessage
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
