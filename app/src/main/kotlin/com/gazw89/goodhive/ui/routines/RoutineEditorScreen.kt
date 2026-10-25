package com.gazw89.goodhive.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.data.model.routines.RoutineIcons
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.data.repository.RoutineRunner.RunState
import com.gazw89.goodhive.ui.components.ReorderItem
import com.gazw89.goodhive.ui.components.ReorderSheet
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

/** Which step sheet is open: a new step of [type], or the existing step at [index]. */
private data class StepEdit(val type: RoutineStepType, val index: Int?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoutineEditorScreen(
    onClose: () -> Unit,
    viewModel: RoutineEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val closed by viewModel.closed.collectAsStateWithLifecycle()
    var showAddStep by rememberSaveable { mutableStateOf(false) }
    var stepEdit by remember { mutableStateOf<StepEdit?>(null) }
    var showReorder by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmTestRun by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(closed) { if (closed) onClose() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbar.showSnackbar(it); viewModel.dismissError() }
    }
    fun leave() { if (uiState.hasChanges) confirmDiscard = true else onClose() }
    BackHandler(enabled = uiState.hasChanges) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isNew) "New routine" else "Edit routine") },
                navigationIcon = {
                    IconButton(onClick = ::leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!uiState.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete routine")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = GoodHiveSpacing.lg, vertical = GoodHiveSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { confirmTestRun = true },
                        enabled = uiState.steps.isNotEmpty() && uiState.testRun !is RunState.Running,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (uiState.testRun is RunState.Running) "Running…" else "Test run") }
                    Button(
                        onClick = viewModel::save,
                        enabled = uiState.canSave,
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                }
            }
        },
    ) { padding ->
        if (!uiState.loaded) return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(GoodHiveSpacing.lg),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::setName,
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Morning boost") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "icons") {
                Text(
                    "Icon",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = GoodHiveSpacing.lg, bottom = GoodHiveSpacing.xs),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
                    RoutineIcons.ALL.forEach { key ->
                        FilterChip(
                            selected = uiState.iconKey == key,
                            onClick = { viewModel.setIcon(key) },
                            label = { Text(routineIconLabel(key)) },
                            leadingIcon = {
                                Icon(routineIcon(key), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                            },
                        )
                    }
                }
            }
            item(key = "steps-header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = GoodHiveSpacing.lg),
                ) {
                    Text("Steps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (uiState.steps.size > 1) TextButton(onClick = { showReorder = true }) { Text("Reorder") }
                }
                Text(
                    "Steps run in order. Different rooms change side by side; each change is sent a few seconds apart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(uiState.steps, key = { index, step -> "step-$index-${step.hashCode()}" }) { index, step ->
                val warnings = uiState.warnings.filter { it.stepIndex == index }
                val hasMissing = step.zoneIds.any { uiState.nameOf(it) == null }
                StepRow(
                    index = index,
                    step = step,
                    summary = stepSummary(step, uiState::nameOf),
                    warnings = warnings.map { it.message },
                    onClick = { stepEdit = StepEdit(step.type(), index) },
                    onDelete = { viewModel.deleteStep(index) },
                    onRemoveMissing = if (hasMissing) ({ viewModel.removeMissingRooms(index) }) else null,
                )
            }
            item(key = "add") {
                OutlinedButton(
                    onClick = { showAddStep = true },
                    modifier = Modifier.fillMaxWidth().padding(top = GoodHiveSpacing.md),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                    Text("Add step", modifier = Modifier.padding(start = GoodHiveSpacing.sm))
                }
            }
            uiState.testRun?.let { run ->
                item(key = "test-run") {
                    Text(
                        when (run) {
                            is RunState.Running -> "Test run: ${run.done} of ${run.total} rooms finished…"
                            is RunState.Finished -> runSummary(run)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = GoodHiveSpacing.lg)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }

    if (showAddStep) {
        ModalBottomSheet(onDismissRequest = { showAddStep = false }) {
            Text(
                "Add a step",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = GoodHiveSpacing.xl, vertical = GoodHiveSpacing.sm),
            )
            RoutineStepType.entries.forEach { type ->
                ListItem(
                    headlineContent = { Text(type.title) },
                    supportingContent = { Text(type.description) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable {
                        showAddStep = false
                        stepEdit = StepEdit(type, null)
                    },
                )
            }
            Spacer(Modifier.padding(bottom = GoodHiveSpacing.xl))
        }
    }

    stepEdit?.let { edit ->
        RoutineStepSheet(
            type = edit.type,
            initial = edit.index?.let(uiState.steps::getOrNull),
            zones = uiState.zones,
            onDismiss = { stepEdit = null },
            onSave = { step ->
                if (edit.index == null) viewModel.addStep(step) else viewModel.replaceStep(edit.index, step)
                stepEdit = null
            },
        )
    }

    if (showReorder) {
        ReorderSheet(
            title = "Reorder steps",
            description = "Steps run from top to bottom.",
            items = uiState.steps.mapIndexed { index, step ->
                ReorderItem(index.toString(), "${stepTitle(step)}: ${stepSummary(step, uiState::nameOf)}")
            },
            saveErrorMessage = "Couldn't reorder steps. Try again.",
            onDismiss = { showReorder = false },
            onSave = { order -> viewModel.reorderSteps(order) },
        )
    }

    if (confirmTestRun) {
        AlertDialog(
            onDismissRequest = { confirmTestRun = false },
            title = { Text("Run it now?") },
            text = { Text("A test run changes your heating for real, exactly as a normal run would.") },
            confirmButton = {
                TextButton(onClick = { confirmTestRun = false; viewModel.testRun() }) { Text("Run now") }
            },
            dismissButton = { TextButton(onClick = { confirmTestRun = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete routine?") },
            text = { Text("\"${uiState.name}\" will be removed. Heating that's already changed stays as it is.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your changes to this routine haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun StepRow(
    index: Int,
    step: RoutineStep,
    summary: String,
    warnings: List<String>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRemoveMissing: (() -> Unit)?,
) {
    ListItem(
        overlineContent = { Text("Step ${index + 1}") },
        headlineContent = { Text(stepTitle(step)) },
        supportingContent = {
            Column {
                Text(summary)
                warnings.forEach { warning ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = GoodHiveSpacing.xs)) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                        Text(
                            "Warning: $warning",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = GoodHiveSpacing.xs),
                        )
                    }
                }
                if (onRemoveMissing != null) {
                    TextButton(onClick = onRemoveMissing) { Text("Remove missing rooms") }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete step ${index + 1}")
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
