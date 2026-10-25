package com.gazw89.goodhive.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ZoneActivity
import com.gazw89.goodhive.data.model.canControl
import com.gazw89.goodhive.ui.components.BOOST_DEFAULT_MINUTES
import com.gazw89.goodhive.ui.components.DeviceHealthNotice
import com.gazw89.goodhive.ui.components.WheelPicker
import com.gazw89.goodhive.ui.components.BoostWheels
import com.gazw89.goodhive.ui.components.boostDurationsMinutes
import com.gazw89.goodhive.ui.components.defaultBoostTargetC
import com.gazw89.goodhive.ui.components.formatBoostDuration
import com.gazw89.goodhive.ui.components.modeIcon
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.PillShape
import com.gazw89.goodhive.ui.theme.TEMPERATURE_STEP_C
import com.gazw89.goodhive.ui.theme.temperatureColor
import com.gazw89.goodhive.ui.theme.temperatureSteps
import com.gazw89.goodhive.util.scheduleNowNextLater
import kotlin.math.roundToInt

private val ModeButtonHeight = 56.dp
private val ModeIconSize = 20.dp
private val BannerIconSize = 18.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    onBack: () -> Unit,
    onEditSchedule: () -> Unit,
    onOpenZone: (String) -> Unit,
    viewModel: DeviceDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val removed by viewModel.removed.collectAsStateWithLifecycle()
    val removalError by viewModel.removalError.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showTempSheet by remember { mutableStateOf(false) }
    var showBoostSheet by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { }
    }
    LaunchedEffect(removed) { if (removed) onBack() }
    LaunchedEffect(removalError) { removalError?.let { snackbar.showSnackbar(it) } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(uiState.zone?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        val currentZone = uiState.zone
        if (currentZone == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        if (!currentZone.reportedByHive) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(GoodHiveSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.lg, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No longer listed by Hive", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Your saved device stays here until you remove it. Devices that Hive lists as offline are kept too.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { showRemoveDialog = true }) { Text("Remove from GoodHive") }
            }
            if (showRemoveDialog) {
                AlertDialog(
                    onDismissRequest = { showRemoveDialog = false },
                    title = { Text("Remove ${currentZone.name}?") },
                    text = { Text("This removes the saved device from GoodHive. It does not delete a device from your Hive account or change its settings.") },
                    confirmButton = {
                        TextButton(onClick = { showRemoveDialog = false; viewModel.removeUnreportedDevice() }) { Text("Remove") }
                    },
                    dismissButton = { TextButton(onClick = { showRemoveDialog = false }) { Text("Keep device") } },
                )
            }
            return@Scaffold
        }

        if (!currentZone.canControl) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding)
                    .verticalScroll(rememberScrollState()).padding(GoodHiveSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.lg),
            ) {
                DeviceHealthNotice(currentZone)
                Text(
                    currentZone.currentTemperatureC?.let { "Last temperature reading: %.1f°C".format(it) }
                        ?: "No temperature reading available",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Live temperature and heating controls will return when the device reconnects.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onEditSchedule) { Text("View saved schedule") }
            }
            return@Scaffold
        }
        val accent = temperatureColor(
            currentZone.targetTemperatureC ?: currentZone.frostProtectionC,
            currentZone.minTemperatureC,
            currentZone.maxTemperatureC,
        )
        val animatedAccent by animateColorAsState(accent, label = "target-accent")

        val activity = uiState.activity
        val isHodBoost = activity == ZoneActivity.HOD_BOOST
        val isUserBoost = currentZone.mode == HeatingMode.BOOST && !isHodBoost
        val headline = zoneHeadline(currentZone, activity, currentZone.scheduleNowNextLater())
        val todayHistory by viewModel.todayHistory.collectAsStateWithLifecycle()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GoodHiveSpacing.lg, vertical = GoodHiveSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.lg),
        ) {
            DeviceHealthNotice(zone = currentZone)

            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = GoodHiveSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.xs),
            ) {
                Text(headline.title, style = MaterialTheme.typography.headlineSmall)
                headline.supporting?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isHodBoost) {
                HeatOnDemandCard(
                    sources = uiState.hodSources,
                    onOpenZone = onOpenZone,
                    onStopBoost = viewModel::stopHeatOnDemandBoost,
                )
            }

            TargetStepper(
                targetC = currentZone.targetTemperatureC ?: currentZone.frostProtectionC,
                minC = currentZone.minTemperatureC,
                maxC = currentZone.maxTemperatureC,
                accent = animatedAccent,
                isApplying = uiState.isApplying,
                onTargetChange = viewModel::setTarget,
                onOpenPicker = { showTempSheet = true },
            )

            AnimatedVisibility(visible = uiState.lastFailure != null) {
                FailedCommandBanner(
                    onRetry = viewModel::retryLastFailure,
                    onDismiss = viewModel::dismissLastFailure,
                )
            }

            ModeButtonGroup(
                currentMode = currentZone.mode,
                onModeSelected = viewModel::setMode,
            )

            TodayTemperatureCard(zone = currentZone, history = todayHistory)

            SettingsCard(
                zone = currentZone,
                showBoost = !isHodBoost,
                isUserBoost = isUserBoost,
                onHeatOnDemandChange = viewModel::setHeatOnDemand,
                onBoost = { showBoostSheet = true },
                onCancelBoost = viewModel::cancelBoost,
                onEditSchedule = onEditSchedule,
            )
        }

        if (showBoostSheet) {
            BoostSheet(
                zone = currentZone,
                onDismiss = { showBoostSheet = false },
                onConfirm = { minutes, targetC ->
                    viewModel.boost(minutes = minutes, targetC = targetC)
                    showBoostSheet = false
                },
            )
        }

        if (showTempSheet) {
            TargetTempSheet(
                zone = currentZone,
                onDismiss = { showTempSheet = false },
                onConfirm = { newTarget ->
                    viewModel.setTarget(newTarget)
                    showTempSheet = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModeButtonGroup(currentMode: String, onModeSelected: (String) -> Unit) {
    val modes = listOf(
        HeatingMode.SCHEDULE to "Schedule",
        HeatingMode.MANUAL to "Manual",
        HeatingMode.OFF to "Off",
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        modes.forEachIndexed { index, (mode, label) ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = currentMode == mode,
                onCheckedChange = { onModeSelected(mode) },
                shapes = shapes,
                modifier = Modifier.weight(1f).height(ModeButtonHeight),
            ) {
                Icon(modeIcon(mode), contentDescription = null, modifier = Modifier.size(ModeIconSize))
                Spacer(Modifier.width(GoodHiveSpacing.sm))
                Text(label)
            }
        }
    }
}

@Composable
private fun SettingsCard(
    zone: HeatingZone,
    showBoost: Boolean,
    isUserBoost: Boolean,
    onHeatOnDemandChange: (Boolean) -> Unit,
    onBoost: () -> Unit,
    onCancelBoost: () -> Unit,
    onEditSchedule: () -> Unit,
) {
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    val rows = mutableListOf<@Composable () -> Unit>()

    if (zone.heatOnDemandAvailable) {
        rows += {
            val enabled = zone.heatOnDemand == "ENABLED"
            ListItem(
                headlineContent = { Text("Heat on demand") },
                trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
                colors = rowColors,
                modifier = Modifier.toggleable(
                    value = enabled,
                    role = Role.Switch,
                    onValueChange = onHeatOnDemandChange,
                ),
            )
        }
    }
    // A Heat on Demand boost isn't the user's: cancelling it would only re-fire ~3.5 min later while
    // the TRV is still calling, so no boost row then ("Stop boost" lives on the Heat on Demand card).
    if (showBoost) {
        rows += {
            ListItem(
                headlineContent = { Text("Boost") },
                supportingContent = zone.boostMinutesLeft?.takeIf { isUserBoost }?.let { { Text("${formatBoostDuration(it)} left") } },
                trailingContent = {
                    if (isUserBoost) {
                        FilledTonalButton(onClick = onCancelBoost) { Text("Cancel boost") }
                    } else {
                        FilledTonalButton(onClick = onBoost) { Text("Start boost") }
                    }
                },
                colors = rowColors,
            )
        }
    }
    rows += {
        ListItem(
            headlineContent = { Text("Edit schedule") },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            colors = rowColors,
            modifier = Modifier.clickable(role = Role.Button, onClick = onEditSchedule),
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = GoodHiveSpacing.lg),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            row()
        }
    }
}

@Composable
private fun FailedCommandBanner(onRetry: () -> Unit, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = GoodHiveSpacing.md)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = GoodHiveSpacing.lg, vertical = GoodHiveSpacing.sm),
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(BannerIconSize),
        )
        Spacer(Modifier.width(GoodHiveSpacing.sm))
        Text(
            text = "Couldn't confirm the change",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.width(GoodHiveSpacing.md))
        TextButton(onClick = onRetry) { Text("Retry") }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

private fun stepIndex(valueC: Double, minC: Double): Int = ((valueC - minC) / TEMPERATURE_STEP_C).roundToInt()

private fun snapToStep(valueC: Double, minC: Double): Double = minC + stepIndex(valueC, minC) * TEMPERATURE_STEP_C

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetTempSheet(
    zone: HeatingZone,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    val steps = remember(zone.minTemperatureC, zone.maxTemperatureC) {
        temperatureSteps(zone.minTemperatureC, zone.maxTemperatureC)
    }
    val initialIndex = remember {
        steps.indexOf(snapToStep(zone.targetTemperatureC ?: zone.frostProtectionC, zone.minTemperatureC))
            .coerceIn(0, steps.lastIndex)
    }
    var selectedIndex by remember { mutableStateOf(initialIndex) }

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
                text = "Set exact temperature",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = zone.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.md),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                WheelPicker(
                    values = steps.map { "%.1f".format(it) },
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { selectedIndex = it },
                    contentDescription = "Temperature",
                )
                Text("°C", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = GoodHiveSpacing.sm))
            }
            Spacer(Modifier.padding(top = GoodHiveSpacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onConfirm(steps[selectedIndex]) },
                    modifier = Modifier.weight(1f),
                ) { Text("Set") }
            }
        }
    }
}

/**
 * Boost picker matching the Hive app: a temperature and a length from 15 minutes to 6 hours
 * ([boostDurationsMinutes]). Opens on [BOOST_SUGGESTED_RISE_C] over the measured room temperature for 30 minutes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoostSheet(
    zone: HeatingZone,
    onDismiss: () -> Unit,
    onConfirm: (minutes: Int, targetC: Double) -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded),
    )
    val steps = remember(zone.minTemperatureC, zone.maxTemperatureC) {
        temperatureSteps(zone.minTemperatureC, zone.maxTemperatureC)
    }
    var tempIndex by remember {
        // Read once when the sheet opens, so later polls never move a value the user is editing.
        val suggested = defaultBoostTargetC(
            currentTemperatureC = zone.currentTemperatureC.takeIf { zone.canControl },
            currentTargetC = zone.targetTemperatureC,
            minC = zone.minTemperatureC,
            maxC = zone.maxTemperatureC,
        )
        mutableStateOf(steps.indexOf(snapToStep(suggested, zone.minTemperatureC)).coerceIn(0, steps.lastIndex))
    }
    var durationIndex by remember {
        mutableStateOf(boostDurationsMinutes.indexOf(BOOST_DEFAULT_MINUTES).coerceAtLeast(0))
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
            Text(text = "Boost", style = MaterialTheme.typography.titleMedium)
            Text(
                text = zone.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = GoodHiveSpacing.xs, bottom = GoodHiveSpacing.md),
            )
            BoostWheels(
                steps = steps,
                tempIndex = tempIndex,
                onTempIndexChange = { tempIndex = it },
                durationIndex = durationIndex,
                onDurationIndexChange = { durationIndex = it },
            )
            Spacer(Modifier.padding(top = GoodHiveSpacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = { onConfirm(boostDurationsMinutes[durationIndex], steps[tempIndex]) },
                    modifier = Modifier.weight(1f),
                ) { Text("Boost") }
            }
        }
    }
}
