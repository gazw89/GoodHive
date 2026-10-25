package com.gazw89.goodhive.ui.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery2Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Hvac
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.HouseholdHeating
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.ZoneActivity
import com.gazw89.goodhive.data.model.DeviceHealth
import com.gazw89.goodhive.data.model.LOW_BATTERY_THRESHOLD_PERCENT
import com.gazw89.goodhive.data.model.deviceHealth
import com.gazw89.goodhive.data.model.isWaitingForBoiler
import com.gazw89.goodhive.data.model.roomsWaitingForBoiler
import com.gazw89.goodhive.ui.components.StatusBadge
import com.gazw89.goodhive.ui.routines.RoutinesRow
import com.gazw89.goodhive.ui.routines.RoutinesViewModel
import com.gazw89.goodhive.ui.components.estimatedBoostTotalMinutes
import com.gazw89.goodhive.ui.components.formatBoostDuration
import com.gazw89.goodhive.ui.components.modeIcon
import com.gazw89.goodhive.ui.components.HeatProgress
import com.gazw89.goodhive.ui.components.bouncyPressable
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.LocalReducedMotion
import com.gazw89.goodhive.ui.theme.calmContainer
import com.gazw89.goodhive.ui.theme.heat
import com.gazw89.goodhive.ui.theme.heatContainer
import com.gazw89.goodhive.ui.theme.heatInnerSurface
import com.gazw89.goodhive.ui.theme.onHeat
import com.gazw89.goodhive.ui.theme.onCalmContainer
import com.gazw89.goodhive.ui.theme.onHeatContainer
import com.gazw89.goodhive.ui.theme.onOverrideContainer
import com.gazw89.goodhive.ui.theme.overrideContainer
import com.gazw89.goodhive.util.hodFiringReason
import com.gazw89.goodhive.util.joinNames
import com.gazw89.goodhive.util.scheduleNowNextLater
import com.gazw89.goodhive.util.toTimeLabel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onZoneClick: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onEditRoutine: (Long?) -> Unit,
    onSessionExpired: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
    routinesViewModel: RoutinesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val routinesState by routinesViewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showReorderMenu by remember { mutableStateOf(false) }
    var showReorderSheet by rememberSaveable { mutableStateOf(false) }
    val trvs = uiState.zones.filter { it.productType == ProductType.TRV_CONTROL }

    LaunchedEffect(uiState.sessionExpired) {
        if (uiState.sessionExpired) onSessionExpired()
    }
    LaunchedEffect(routinesViewModel) {
        routinesViewModel.messages.collect { message ->
            val result = snackbar.showSnackbar(
                message = message.text,
                actionLabel = if (message.canRetry) "Retry" else null,
                withDismissAction = message.canRetry,
                duration = if (message.canRetry) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) routinesViewModel.retryFailed(message.routineId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GoodHive") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    Box {
                        IconButton(onClick = { showReorderMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = showReorderMenu,
                            onDismissRequest = { showReorderMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Reorder rooms") },
                                leadingIcon = { Icon(Icons.Filled.Reorder, contentDescription = null) },
                                enabled = trvs.size > 1,
                                onClick = {
                                    showReorderMenu = false
                                    showReorderSheet = true
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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refresh(force = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column {
                uiState.commandFailureBanner?.let { message ->
                    CommandFailureBanner(
                        message = message,
                        onClick = onOpenSettings,
                    )
                }
                if (uiState.zones.isNotEmpty()) {
                    HouseholdSummaryCard(
                        household = uiState.household,
                        zones = uiState.zones,
                        activities = uiState.activities,
                        modifier = Modifier.padding(
                            start = GoodHiveSpacing.lg,
                            end = GoodHiveSpacing.lg,
                            top = GoodHiveSpacing.xs,
                        ),
                    )
                }
                if (uiState.zones.isNotEmpty()) {
                    RoutinesRow(
                        uiState = routinesState,
                        onRun = routinesViewModel::run,
                        onEditRoutine = onEditRoutine,
                        onReorder = routinesViewModel::reorder,
                        onCollapsedChange = routinesViewModel::setCollapsed,
                        modifier = Modifier.padding(top = GoodHiveSpacing.sm),
                    )
                }
                SyncStatusBar(
                    lastSyncedAtEpochMillis = uiState.lastSyncedAtEpochMillis,
                    syncError = uiState.syncError,
                    pendingChangeCount = uiState.pendingZoneIds.size,
                )
                if (uiState.zones.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(GoodHiveSpacing.xxl))
                    }
                } else {
                    val zonesById = uiState.zones.associateBy { it.id }
                    val hodSourceZones = uiState.household.hodSourceIds.mapNotNull { zonesById[it] }
                    LazyColumn(
                        state = rememberLazyListState(),
                        contentPadding = PaddingValues(GoodHiveSpacing.lg),
                    ) {
                        itemsIndexed(uiState.zones, key = { _, zone -> zone.id }) { index, zone ->
                            val cardInteraction = remember { MutableInteractionSource() }
                            StaggeredEntrance(index = index) {
                                ZoneCard(
                                    zone = zone,
                                    activity = uiState.activities[zone.id] ?: ZoneActivity.IDLE,
                                    hodSourceZones = hodSourceZones,
                                    waitingForBoiler = zone.isWaitingForBoiler(uiState.household),
                                    onQuickAdjust = { delta -> viewModel.quickAdjust(zone, delta) },
                                    onTurnOn = { viewModel.turnOn(zone.id) },
                                    onClick = { onZoneClick(zone.id) },
                                    interactionSource = cardInteraction,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = GoodHiveSpacing.md),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReorderSheet) {
        ReorderRoomsSheet(
            rooms = trvs,
            onDismiss = { showReorderSheet = false },
            onSave = viewModel::commitTrvOrder,
        )
    }
}

/** Fades + slides each card in with a small stagger so the list feels alive on load, not dumped. */
@Composable
private fun StaggeredEntrance(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(tween(350, delayMillis = (index * 45).coerceAtMost(300))) +
            slideInVertically(tween(350, delayMillis = (index * 45).coerceAtMost(300))) { it / 6 },
        modifier = modifier,
    ) { content() }
}

@Composable
private fun CommandFailureBanner(message: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GoodHiveSpacing.lg, vertical = GoodHiveSpacing.sm)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .bouncyPressable(interactionSource)
            .clickable(interactionSource = interactionSource, onClick = onClick)
            .padding(GoodHiveSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(modifier = Modifier.width(GoodHiveSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Couldn't confirm a change",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        Icon(
            Icons.Filled.Settings,
            contentDescription = "Open settings",
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun SyncStatusBar(lastSyncedAtEpochMillis: Long?, syncError: String?, pendingChangeCount: Int) {
    val label = when {
        pendingChangeCount == 1 -> "Waiting to confirm a change…"
        pendingChangeCount > 1 -> "Waiting to confirm changes in $pendingChangeCount rooms…"
        syncError != null -> syncError
        lastSyncedAtEpochMillis == null -> "Getting your latest temperatures…"
        else -> "Synced ${secondsAgoLabel(lastSyncedAtEpochMillis)}"
    }
    // The zones below are already showing cached data even when this line is red, so only
    // the sign-in-expired case (which needs the user to actually do something) gets the
    // alarming error color — a transient network hiccup gets a calmer, muted tone.
    val isActionable = syncError?.contains("sign in", ignoreCase = true) == true
    val color = when {
        isActionable -> MaterialTheme.colorScheme.error
        syncError != null -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    AnimatedContent(targetState = label, label = "sync-status") { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = GoodHiveSpacing.section, vertical = GoodHiveSpacing.sm),
        )
    }
}

private fun secondsAgoLabel(epochMillis: Long): String {
    val seconds = (System.currentTimeMillis() - epochMillis) / 1000
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "${seconds}s ago"
        else -> "${seconds / 60}m ago"
    }
}

// --- Formatting helpers ---------------------------------------------------------------------

/** "17°" for whole values, "17.5°" otherwise (one decimal). */
private fun formatTemp(valueC: Double): String {
    val tenths = (valueC * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}°" else "%.1f°".format(tenths / 10.0)
}

private fun roomsLabel(count: Int): String = if (count == 1) "1 room" else "$count rooms"

// --- Household summary ----------------------------------------------------------------------

/** "Heating is on/off" plus why. Hidden when the boiler state is unknown (no thermostat). */
@Composable
private fun HouseholdSummaryCard(
    household: HouseholdHeating,
    zones: List<HeatingZone>,
    activities: Map<String, ZoneActivity>,
    modifier: Modifier = Modifier,
) {
    val boilerOn = household.boilerOn ?: return
    val namesById = zones.associate { it.id to it.name }
    val sourceNames = household.hodSourceIds.mapNotNull { namesById[it] }
    // Counted from the zones, not the activities: a boosted TRV that is calling while the boiler is
    // off is waiting too, even though its card shows Boost.
    val waiting = roomsWaitingForBoiler(zones, household)
    val warming = activities.values.count {
        it == ZoneActivity.HEATING || it == ZoneActivity.HOD_SOURCE
    }

    val title = if (boilerOn) "Heating is on" else "Heating is off"
    val reason = when {
        household.hodActive -> hodFiringReason(sourceNames)
        waiting > 0 -> "${roomsLabel(waiting)} waiting — boiler is off"
        boilerOn -> "Boiler is running"
        else -> "No rooms are calling for heat"
    }
    val supporting = if (warming > 0) "$reason · ${roomsLabel(warming)} warming up" else reason
    // Rooms want heat but the boiler is off: the one actionable problem, so flag it in the waiting style.
    val attention = !boilerOn && waiting > 0

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (attention) BorderStroke(1.dp, MaterialTheme.colorScheme.heat) else null,
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$title. $supporting"
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            modifier = Modifier.padding(GoodHiveSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (boilerOn) {
                            MaterialTheme.colorScheme.heat
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        boilerOn -> Icons.Filled.LocalFireDepartment
                        attention -> Icons.Filled.HourglassTop
                        else -> Icons.Filled.PowerSettingsNew
                    },
                    contentDescription = null,
                    tint = when {
                        boilerOn -> MaterialTheme.colorScheme.onHeat
                        attention -> MaterialTheme.colorScheme.heat
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.xxs)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Zone card ------------------------------------------------------------------------------

/** Colours for one rung of the status ladder (doc §2): calm, outlined/dormant, or heat. */
private data class ZonePalette(
    val container: Color,
    val content: Color,
    val secondary: Color,
    val stepperContainer: Color,
    val stepperContent: Color,
    val outlined: Boolean,
    val isHeatCard: Boolean,
)

private fun ZoneActivity.isHeatTier(): Boolean = when (this) {
    ZoneActivity.HEATING, ZoneActivity.BOOST, ZoneActivity.HOD_BOOST, ZoneActivity.HOD_SOURCE -> true
    else -> false
}

@Composable
private fun zonePalette(zone: HeatingZone, activity: ZoneActivity): ZonePalette {
    val scheme = MaterialTheme.colorScheme
    // A flat battery is flagged by the status chip alone; the card keeps the same look as its neighbours.
    val dormant = activity == ZoneActivity.OFF ||
        (activity == ZoneActivity.OFFLINE && zone.deviceHealth != DeviceHealth.BATTERY_EMPTY)
    return when {
        activity.isHeatTier() -> ZonePalette(
            container = scheme.heatContainer,
            content = scheme.onHeatContainer,
            secondary = scheme.onHeatContainer,
            stepperContainer = scheme.heatInnerSurface,
            stepperContent = scheme.heat,
            outlined = false,
            isHeatCard = true,
        )
        dormant -> ZonePalette(
            container = scheme.background,
            // Off reads as faded, not just outlined (onSurfaceVariant keeps ≥4.5:1; `outline` is only
            // 2.3:1 on cream). Offline keeps full-strength text beside its error badge.
            content = if (activity == ZoneActivity.OFF) scheme.onSurfaceVariant else scheme.onSurface,
            secondary = scheme.onSurfaceVariant,
            stepperContainer = scheme.surfaceContainerHighest,
            stepperContent = scheme.onSurface,
            outlined = true,
            isHeatCard = false,
        )
        else -> ZonePalette(
            container = scheme.surfaceContainer,
            content = scheme.onSurface,
            secondary = scheme.onSurfaceVariant,
            stepperContainer = scheme.surfaceContainerHighest,
            stepperContent = scheme.onSurface,
            outlined = false,
            isHeatCard = false,
        )
    }
}

private data class ChipSpec(
    val label: String,
    val icon: ImageVector,
    val container: Color,
    val content: Color,
    val border: Color? = null,
    val pulse: Boolean = false,
)

private const val PROGRESS_WINDOW_C = 5.0
private const val PROGRESS_MIN = 0.05f
private const val PROGRESS_MAX = 1f

@Composable
private fun chipSpec(zone: HeatingZone, activity: ZoneActivity): ChipSpec {
    val scheme = MaterialTheme.colorScheme
    if (!zone.reportedByHive) {
        return ChipSpec("Not listed", Icons.Filled.CloudOff, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
    zone.deviceHealth?.let { health ->
        return ChipSpec(
            label = when (health) {
                DeviceHealth.BATTERY_EMPTY -> "Battery empty"
                DeviceHealth.LOW_BATTERY -> "Battery low"
                else -> "Offline"
            },
            icon = if (health == DeviceHealth.OFFLINE) Icons.Filled.CloudOff else Icons.Filled.BatteryAlert,
            container = scheme.errorContainer,
            content = scheme.onErrorContainer,
        )
    }
    return when (activity) {
        ZoneActivity.OFF -> ChipSpec(
            label = "Off",
            icon = modeIcon(HeatingMode.OFF),
            container = Color.Transparent,
            content = scheme.onSurfaceVariant,
            border = scheme.outlineVariant,
        )
        ZoneActivity.OFFLINE -> {
            ChipSpec(
                label = "Offline",
                icon = Icons.Filled.CloudOff,
                container = scheme.errorContainer,
                content = scheme.onErrorContainer,
            )
        }
        ZoneActivity.IDLE -> if (zone.mode == HeatingMode.MANUAL) {
            ChipSpec("Manual", modeIcon(HeatingMode.MANUAL), scheme.overrideContainer, scheme.onOverrideContainer)
        } else {
            ChipSpec("On schedule", modeIcon(HeatingMode.SCHEDULE), scheme.calmContainer, scheme.onCalmContainer)
        }
        ZoneActivity.WAITING_FOR_BOILER -> ChipSpec(
            label = "Waiting",
            icon = Icons.Filled.HourglassTop,
            container = Color.Transparent,
            content = scheme.heat,
            border = scheme.heat,
        )
        ZoneActivity.HEATING, ZoneActivity.HOD_SOURCE -> ChipSpec(
            label = "Heating",
            icon = Icons.Filled.LocalFireDepartment,
            container = scheme.heat,
            content = scheme.onHeat,
            pulse = true,
        )
        ZoneActivity.BOOST -> ChipSpec(
            label = zone.boostMinutesLeft?.let { "Boost · ${formatBoostDuration(it)}" } ?: "Boost",
            icon = Icons.Filled.Bolt,
            container = scheme.heat,
            content = scheme.onHeat,
            pulse = true,
        )
        ZoneActivity.HOD_BOOST -> ChipSpec(
            label = "Heat on Demand",
            icon = Icons.Filled.Hvac,
            container = scheme.heat,
            content = scheme.onHeat,
            pulse = true,
        )
    }
}

/** Context line beside the room-temperature hero: whatever is most useful for the state, or null. */
private fun sublineFor(zone: HeatingZone, activity: ZoneActivity, waitingForBoiler: Boolean): String? {
    if (!zone.reportedByHive) return "Saved device · tap to manage"
    val current = zone.currentTemperatureC
    return when (activity) {
        ZoneActivity.OFF -> "Frost guard ${formatTemp(zone.frostProtectionC)}"
        ZoneActivity.OFFLINE -> if (current != null) "Last reading" else "No reading"
        ZoneActivity.IDLE -> {
            val next = if (zone.mode == HeatingMode.SCHEDULE) zone.scheduleNowNextLater() else null
            if (next?.nextChangeMinutes != null && next.nextTargetC != null) {
                "${formatTemp(next.nextTargetC)} at ${next.nextChangeMinutes.toTimeLabel()}"
            } else {
                null
            }
        }
        ZoneActivity.WAITING_FOR_BOILER -> "Boiler off"
        ZoneActivity.HOD_BOOST -> {
            // The thermostat's own target is the boost's 25°, so judge "at target" against the
            // setpoint Hive will restore when the boost ends.
            val reference = when (zone.previousMode) {
                HeatingMode.MANUAL -> zone.previousTargetC
                HeatingMode.SCHEDULE -> zone.scheduleNowNextLater()?.currentTargetC
                else -> null
            }
            if (current != null && reference != null && current >= reference - 0.1) "At target" else null
        }
        ZoneActivity.BOOST -> if (waitingForBoiler) "Boiler off" else null
        ZoneActivity.HEATING, ZoneActivity.HOD_SOURCE -> null
    }
}

/** Progress bar content for the active tiers, or null when the data to draw it is missing. */
private data class HeatProgressModel(
    val progress: Float,
    val label: String,
    val description: String,
)

private fun progressFor(zone: HeatingZone, activity: ZoneActivity): HeatProgressModel? {
    val current = zone.currentTemperatureC
    val target = zone.targetTemperatureC
    return when (activity) {
        ZoneActivity.HEATING, ZoneActivity.HOD_SOURCE -> {
            if (current == null || target == null) return null
            val progress = ((current - (target - PROGRESS_WINDOW_C)) / PROGRESS_WINDOW_C)
                .toFloat().coerceIn(PROGRESS_MIN, PROGRESS_MAX)
            val diff = target - current
            val remaining = if (diff > 0.05) "%.1f° to ${formatTemp(target)}".format(diff) else "at ${formatTemp(target)}"
            val description = if (diff > 0.05) "%.1f degrees below target".format(diff) else "At target"
            HeatProgressModel(progress, remaining, description)
        }
        ZoneActivity.BOOST -> {
            val minutes = zone.boostMinutesLeft ?: return null
            HeatProgressModel(
                progress = (minutes.toFloat() / estimatedBoostTotalMinutes(minutes)).coerceIn(PROGRESS_MIN, PROGRESS_MAX),
                label = "${formatBoostDuration(minutes)} left",
                description = "${formatBoostDuration(minutes)} of boost left",
            )
        }
        else -> null
    }
}

@Composable
private fun ZoneCard(
    zone: HeatingZone,
    activity: ZoneActivity,
    hodSourceZones: List<HeatingZone>,
    waitingForBoiler: Boolean,
    onQuickAdjust: (Double) -> Unit,
    onTurnOn: () -> Unit,
    onClick: () -> Unit,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val palette = zonePalette(zone, activity)
    val container by animateColorAsState(
        targetValue = palette.container,
        animationSpec = GoodHiveMotion.quickTween(reducedMotion, durationMs = 300),
        label = "zone-card-container",
    )
    val content by animateColorAsState(
        targetValue = palette.content,
        animationSpec = GoodHiveMotion.quickTween(reducedMotion, durationMs = 300),
        label = "zone-card-content",
    )
    val chip = chipSpec(zone, activity)
    val progress = progressFor(zone, activity)

    val cardModifier = modifier.bouncyPressable(interactionSource, pressedScale = 0.98f)

    val cardContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(GoodHiveSpacing.lg),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = zone.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = content,
                        )
                        RoomTemperatureHero(
                            currentC = zone.currentTemperatureC.takeIf { zone.reportedByHive },
                            context = sublineFor(zone, activity, waitingForBoiler),
                            heroColor = content,
                            contextColor = palette.secondary,
                            modifier = Modifier.padding(top = GoodHiveSpacing.xs),
                        )
                    }

                    StatusBadge(
                        label = chip.label,
                        icon = chip.icon,
                        containerColor = chip.container,
                        contentColor = chip.content,
                        borderColor = chip.border,
                        pulseIcon = chip.pulse,
                        contentDescription = "${zone.name} status: ${chip.label}",
                        modifier = Modifier.padding(start = GoodHiveSpacing.sm),
                    )
                }

                if (activity == ZoneActivity.HOD_SOURCE) {
                    HodSourceLine(modifier = Modifier.padding(top = GoodHiveSpacing.sm))
                }

                if (activity == ZoneActivity.HOD_BOOST && hodSourceZones.isNotEmpty()) {
                    HodBoostedByRow(
                        sources = hodSourceZones,
                        onClick = onClick,
                        modifier = Modifier.padding(top = GoodHiveSpacing.md),
                    )
                }

                if (progress != null) {
                    Column(modifier = Modifier.padding(top = GoodHiveSpacing.md)) {
                        HeatProgress(
                            progress = progress.progress,
                            color = MaterialTheme.colorScheme.heat,
                            description = progress.description,
                        )
                        Text(
                            text = progress.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.secondary,
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = GoodHiveSpacing.xs),
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = GoodHiveSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (zone.deviceHealth == null) {
                            BatteryIndicator(level = zone.batteryLevel, normalTint = palette.secondary)
                        }
                        DeviceTypeIcon(productType = zone.productType, tint = palette.secondary)
                    }

                    when (activity) {
                        // Filled tonal, so the action never looks like the outlined "Off" badge beside it.
                        ZoneActivity.OFF -> FilledTonalButton(
                            onClick = onTurnOn,
                            modifier = Modifier.semantics {
                                contentDescription = "Turn on ${zone.name}"
                            },
                        ) {
                            Text("Turn on")
                        }
                        ZoneActivity.OFFLINE -> Unit
                        else -> TargetStepper(
                            zone = zone,
                            palette = palette,
                            onQuickAdjust = onQuickAdjust,
                        )
                    }
                }
            }
        }
    }

    if (palette.outlined) {
        OutlinedCard(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.outlinedCardColors(containerColor = container, contentColor = content),
            border = CardDefaults.outlinedCardBorder(),
            modifier = cardModifier,
        ) { cardContent() }
    } else {
        Card(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = cardModifier,
        ) { cardContent() }
    }
}

@Composable
private fun TargetStepper(
    zone: HeatingZone,
    palette: ZonePalette,
    onQuickAdjust: (Double) -> Unit,
) {
    val stepperColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = palette.stepperContainer,
        contentColor = palette.stepperContent,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { onQuickAdjust(-0.5) }, colors = stepperColors) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "Decrease target for ${zone.name}",
            )
        }
        val target = zone.targetTemperatureC
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = GoodHiveSpacing.sm)
                .clearAndSetSemantics {
                    contentDescription = target?.let { "Target %.1f degrees".format(it) } ?: "No target"
                },
        ) {
            Text(
                text = "Target",
                style = MaterialTheme.typography.labelSmall,
                color = palette.secondary,
            )
            AnimatedContent(targetState = target, label = "target-temp") { value ->
                Text(
                    text = value?.let { "%.1f°".format(it) } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        FilledTonalIconButton(onClick = { onQuickAdjust(0.5) }, colors = stepperColors) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Increase target for ${zone.name}",
            )
        }
    }
}

/** TRV that triggered the running Heat on Demand boost. */
@Composable
private fun HodSourceLine(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.tight),
    ) {
        Icon(
            Icons.Filled.LocalFireDepartment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.heat,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "Heat on Demand · firing the boiler",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.heat,
        )
    }
}

/** Inline row on a HoD-driven thermostat: who boosted it. Tapping opens this thermostat's detail. */
@Composable
private fun HodBoostedByRow(
    sources: List<HeatingZone>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val names = joinNames(sources.map { it.name })
    val first = sources.first()
    val until = if (sources.size == 1) {
        first.targetTemperatureC?.let { "until ${first.name} reaches ${formatTemp(it)}" }
            ?: "until ${first.name} reaches its target"
    } else {
        "until they all reach their targets"
    }
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.heatInnerSurface,
        contentColor = MaterialTheme.colorScheme.onHeatContainer,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Boosted by $names. Boiler stays on $until. Opens thermostat details."
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = GoodHiveSpacing.md, vertical = GoodHiveSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.heat),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Hvac,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onHeat,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Boosted by $names",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "Boiler stays on $until",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onHeatContainer,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onHeatContainer,
            )
        }
    }
}

/** Bottom-left battery indicator, next to [DeviceTypeIcon]. Rendered only when a reading exists. */
@Composable
private fun BatteryIndicator(level: Int?, normalTint: Color) {
    if (level == null) return
    val isLow = level <= LOW_BATTERY_THRESHOLD_PERCENT
    val icon = when {
        isLow -> Icons.Filled.BatteryAlert
        level >= 95 -> Icons.Filled.BatteryFull
        level >= 80 -> Icons.Filled.Battery6Bar
        level >= 65 -> Icons.Filled.Battery5Bar
        level >= 50 -> Icons.Filled.Battery4Bar
        level >= 35 -> Icons.Filled.Battery3Bar
        level >= 20 -> Icons.Filled.Battery2Bar
        else -> Icons.Filled.Battery1Bar
    }
    Icon(
        icon,
        contentDescription = "Battery $level percent" + if (isLow) ", low" else "",
        tint = if (isLow) MaterialTheme.colorScheme.error else normalTint,
        modifier = Modifier
            .size(18.dp)
            .padding(end = GoodHiveSpacing.xs),
    )
}

/** Bottom-left device-type indicator: thermostat vs room TRV, as a plain icon (not a pill). */
@Composable
private fun DeviceTypeIcon(productType: String, tint: Color) {
    val isThermostat = productType == ProductType.HEATING
    Icon(
        imageVector = if (isThermostat) Icons.Filled.Thermostat else Icons.Filled.Hvac,
        contentDescription = if (isThermostat) "Thermostat" else "TRV",
        tint = tint,
        modifier = Modifier.size(18.dp),
    )
}

/**
 * Current room temperature as the card's headline number, with the state's context text beside it.
 * Renders only the context when there is no reading.
 */
@Composable
private fun RoomTemperatureHero(
    currentC: Double?,
    context: String?,
    heroColor: Color,
    contextColor: Color,
    modifier: Modifier = Modifier,
) {
    if (currentC == null && context == null) return
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
    ) {
        if (currentC != null) {
            Text(
                text = "%.1f°".format(currentC),
                style = MaterialTheme.typography.headlineMedium,
                color = heroColor,
                modifier = Modifier.semantics { contentDescription = "%.1f degrees now".format(currentC) },
            )
        }
        if (context != null) {
            Text(
                text = context,
                style = MaterialTheme.typography.bodyMedium,
                color = contextColor,
                modifier = Modifier.padding(bottom = GoodHiveSpacing.xs),
            )
        }
    }
}
