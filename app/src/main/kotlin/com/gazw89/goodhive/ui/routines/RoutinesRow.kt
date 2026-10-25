package com.gazw89.goodhive.ui.routines

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.gazw89.goodhive.data.repository.RoutineRunner.RunState
import com.gazw89.goodhive.ui.components.ReorderItem
import com.gazw89.goodhive.ui.components.ReorderSheet
import com.gazw89.goodhive.ui.components.bouncyPressable
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.LocalReducedMotion

/**
 * Routines header plus a row of tiles. Tapping a tile runs it; in edit mode a tap opens the editor.
 * The trailing tile creates a new routine ([onEditRoutine] with null). The header's chevron hides the
 * tiles ([onCollapsedChange]) for people who want the dashboard space back.
 */
@Composable
fun RoutinesRow(
    uiState: RoutinesUiState,
    onRun: (Long) -> Unit,
    onEditRoutine: (Long?) -> Unit,
    onReorder: suspend (List<String>) -> Unit,
    onCollapsedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!uiState.loaded) return
    val reducedMotion = LocalReducedMotion.current
    val collapsed = uiState.collapsed
    var editing by rememberSaveable { mutableStateOf(false) }
    var showReorder by rememberSaveable { mutableStateOf(false) }
    val tiles = uiState.tiles
    if ((tiles.isEmpty() || collapsed) && editing) editing = false

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = GoodHiveSpacing.section, end = GoodHiveSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (collapsed && tiles.isNotEmpty()) "Routines · ${tiles.size}" else "Routines",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (!collapsed && editing && tiles.size > 1) {
                TextButton(onClick = { showReorder = true }) { Text("Reorder") }
            }
            if (!collapsed && tiles.isNotEmpty()) {
                TextButton(onClick = { editing = !editing }) { Text(if (editing) "Done" else "Edit") }
            }
            IconButton(onClick = { onCollapsedChange(!collapsed) }) {
                Icon(
                    if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (collapsed) "Show routines" else "Hide routines",
                )
            }
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(GoodHiveMotion.settleSpring(reducedMotion)) + fadeIn(GoodHiveMotion.quickTween(reducedMotion)),
            exit = shrinkVertically(GoodHiveMotion.settleSpring(reducedMotion)) + fadeOut(GoodHiveMotion.quickTween(reducedMotion)),
        ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = GoodHiveSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
        ) {
            items(tiles, key = { it.routine.id }) { tile ->
                RoutineTileCard(
                    tile = tile,
                    editing = editing,
                    onClick = { if (editing) onEditRoutine(tile.routine.id) else onRun(tile.routine.id) },
                )
            }
            item(key = "new") { NewRoutineTile(onClick = { onEditRoutine(null) }) }
        }
        }
    }

    if (showReorder) {
        ReorderSheet(
            title = "Reorder routines",
            description = "Drag the handles to arrange your routines on the Rooms screen.",
            items = tiles.map { ReorderItem(it.routine.id.toString(), it.routine.name) },
            saveErrorMessage = "Couldn't save routine order. Try again.",
            onDismiss = { showReorder = false },
            onSave = onReorder,
        )
    }
}

@Composable
private fun RoutineTileCard(tile: RoutineTile, editing: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val state = tile.state
    val status = when {
        editing -> "Tap to edit"
        state is RunState.Running -> "Running…"
        state is RunState.Finished && state.failed.isNotEmpty() -> "${state.failed.size} failed"
        state is RunState.Finished -> "Done"
        else -> "${tile.routine.steps.size} ${if (tile.routine.steps.size == 1) "step" else "steps"}"
    }
    val failed = !editing && state is RunState.Finished && state.failed.isNotEmpty()
    val spoken = when {
        editing -> "${tile.routine.name}, edit"
        state is RunState.Running -> "${tile.routine.name}, running"
        failed -> "${tile.routine.name}, last run: $status"
        state is RunState.Finished -> "${tile.routine.name}, last run done"
        else -> tile.routine.name
    }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        colors = CardDefaults.cardColors(
            containerColor = if (editing) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .width(GoodHiveSpacing.routineTileWidth)
            .heightIn(min = GoodHiveSpacing.touchTarget)
            .bouncyPressable(interaction)
            .semantics(mergeDescendants = true) {
                contentDescription = spoken
                onClick(label = if (editing) "Edit routine" else "Run routine") { onClick(); true }
            },
    ) {
        Column(modifier = Modifier.padding(GoodHiveSpacing.md)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(GoodHiveSpacing.iconContainer)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(GoodHiveSpacing.iconContainer),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when {
                                editing -> Icons.Filled.Edit
                                else -> routineIcon(tile.routine.iconKey)
                            },
                            contentDescription = null,
                        )
                    }
                }
                if (!editing && state is RunState.Running) {
                    CircularProgressIndicator(modifier = Modifier.size(GoodHiveSpacing.iconContainer))
                }
            }
            Text(
                tile.routine.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = GoodHiveSpacing.sm),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clearAndSetSemantics { }) {
                if (!editing && state is RunState.Finished) {
                    Icon(
                        if (failed) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(GoodHiveSpacing.lg).padding(end = GoodHiveSpacing.xxs),
                    )
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NewRoutineTile(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedCard(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .width(GoodHiveSpacing.routineTileWidth)
            .heightIn(min = GoodHiveSpacing.touchTarget)
            .bouncyPressable(interaction),
    ) {
        Column(modifier = Modifier.padding(GoodHiveSpacing.md)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(GoodHiveSpacing.iconContainer)) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text("New routine", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = GoodHiveSpacing.sm))
            Text(
                "Your own",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
