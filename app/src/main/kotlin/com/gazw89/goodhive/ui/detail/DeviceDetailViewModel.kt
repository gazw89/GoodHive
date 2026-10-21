package com.gazw89.goodhive.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.data.db.CommandLogDao
import com.gazw89.goodhive.data.db.CommandLogEntity
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.HouseholdHeating
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.ZoneActivity
import com.gazw89.goodhive.data.model.activity
import com.gazw89.goodhive.data.model.householdHeating
import com.gazw89.goodhive.data.repository.CommandExecutor
import com.gazw89.goodhive.data.repository.HeatingRepository
import com.gazw89.goodhive.util.GoodHiveTime
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.util.scheduleNowNextLater
import java.time.LocalDate
import com.gazw89.goodhive.data.repository.buildBoostPayload
import com.gazw89.goodhive.data.repository.buildCancelBoostPayload
import com.gazw89.goodhive.data.repository.buildModePayload
import com.gazw89.goodhive.data.repository.buildTargetPayload
import com.gazw89.goodhive.data.repository.buildHeatOnDemandPayload
import com.gazw89.goodhive.data.repository.effectiveHeatOnDemand
import com.gazw89.goodhive.data.repository.expectedStateForBoost
import com.gazw89.goodhive.data.repository.expectedStateForCancelBoost
import com.gazw89.goodhive.data.repository.expectedStateForHeatOnDemand
import com.gazw89.goodhive.data.repository.expectedStateForMode
import com.gazw89.goodhive.data.repository.expectedStateForTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DeviceDetailUiState(
    val zone: HeatingZone? = null,
    /** True while a change is in flight — tap feedback is instant, this is just "still confirming". */
    val isApplying: Boolean = false,
    /** Most recent unresolved failed command for this zone, if any — surfaced as an inline retry affordance. */
    val lastFailure: CommandLogEntity? = null,
    /** Household-wide heating state derived from ALL zones (after this zone's optimistic overrides). */
    val household: HouseholdHeating = HouseholdHeating(boilerOn = null, hodActive = false, hodSourceIds = emptySet()),
    /** What [zone] is doing right now (see [ZoneActivity]); null until the zone has loaded. */
    val activity: ZoneActivity? = null,
    /**
     * Zones that triggered the running Heat on Demand boost (ids in [HouseholdHeating.hodSourceIds]).
     * Only meaningful for the thermostat while [activity] is [ZoneActivity.HOD_BOOST].
     */
    val hodSources: List<HeatingZone> = emptyList(),
)

/** Today's room readings for the chart; loaded separately from [DeviceDetailUiState] so it never delays controls. */
sealed interface TodayHistoryState {
    data object Loading : TodayHistoryState
    data class Ready(val readings: List<TemperatureReading>) : TodayHistoryState
    data object Unavailable : TodayHistoryState
    data object NoSource : TodayHistoryState
}

private data class Optimistic(val mode: String?, val target: Double?, val heatOnDemand: String?)

/**
 * Command reliability layer (see CommandExecutor) deliberately only confirms a change once
 * the device's real state is polled back — which is correct, but naively binding UI
 * directly to that confirmed state means tapping SCHEDULE/MANUAL/OFF feels unresponsive
 * for several seconds. This ViewModel adds a short-lived optimistic override: the tapped
 * value shows immediately, and the override clears itself once the real zone state catches
 * up OR a command failure is recorded — never on a short timeout that snaps back to stale cache.
 */
@HiltViewModel
class DeviceDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val heatingRepository: HeatingRepository,
    private val commandExecutor: CommandExecutor,
    private val commandLogDao: CommandLogDao,
    private val todayHistoryLoader: TodayHistoryLoader,
) : ViewModel() {

    private val zoneId: String = checkNotNull(savedStateHandle["zoneId"])
    private val _removed = MutableStateFlow(false)
    val removed = _removed.asStateFlow()
    private val _removalError = MutableStateFlow<String?>(null)
    val removalError = _removalError.asStateFlow()

    fun removeUnreportedDevice() {
        viewModelScope.launch {
            _removalError.value = null
            try {
                if (heatingRepository.removeUnreportedDevice(zoneId)) _removed.value = true
                else _removalError.value = "Hive lists this device again — it has been kept"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _removalError.value = "Couldn't remove the saved device — please retry"
            }
        }
    }

    private val allZones: StateFlow<List<HeatingZone>> = commandExecutor.observeZones()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val realZone: StateFlow<HeatingZone?> = allZones
        .map { zones -> zones.firstOrNull { it.id == zoneId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val optimisticMode = MutableStateFlow<String?>(null)
    private val optimisticTarget = MutableStateFlow<Double?>(null)
    private val optimisticHeatOnDemand = MutableStateFlow<String?>(null)
    private val lastFailure: StateFlow<CommandLogEntity?> = commandLogDao.observeUnresolvedFailures()
        .map { failures ->
            val cutoff = System.currentTimeMillis() - RECENT_FAILURE_WINDOW_MS
            failures.firstOrNull {
                it.zoneId == zoneId && it.resolvedAtEpochMillis >= cutoff
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val changeVersions = mutableMapOf<String, Long>()

    private val optimistic = combine(optimisticMode, optimisticTarget, optimisticHeatOnDemand, ::Optimistic)

    val uiState: StateFlow<DeviceDetailUiState> = combine(
        realZone,
        allZones,
        optimistic,
        lastFailure,
        commandExecutor.observePending(),
    ) { real, zones, pending, failure, durable ->
        val displayed = real?.copy(
            mode = pending.mode ?: real.mode,
            targetTemperatureC = pending.target ?: real.targetTemperatureC,
            heatOnDemand = pending.heatOnDemand ?: real.heatOnDemand,
        )
        val applying = durable.any { it.zoneId == zoneId } || pending.mode != null ||
            pending.target != null ||
            pending.heatOnDemand != null
        // Household state is derived from every zone, with this zone's optimistic overrides applied
        // so e.g. tapping OFF on the thermostat mid-Heat-on-Demand updates the activity immediately.
        val adjusted = if (displayed == null) zones else zones.map { if (it.id == displayed.id) displayed else it }
        val household = householdHeating(adjusted)
        DeviceDetailUiState(
            zone = displayed,
            isApplying = applying,
            lastFailure = failure,
            household = household,
            activity = displayed?.activity(household),
            hodSources = adjusted.filter { it.reportedByHive && it.id in household.hodSourceIds },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceDetailUiState())

    // Kept for callers that just need the raw zone (e.g. reading min/max before a command).
    val zone: StateFlow<HeatingZone?> get() = realZone

    init {
        // One coalesced refresh on open — do not run a parallel 25s Hive poll loop here.
        // Dashboard + CommandExecutor already refresh; stacking loops trips Beekeeper 403s.
        viewModelScope.launch {
            runCatching { heatingRepository.refresh(force = false) }
        }
    }

    private val _todayHistory = MutableStateFlow<TodayHistoryState>(TodayHistoryState.Loading)
    val todayHistory: StateFlow<TodayHistoryState> = _todayHistory.asStateFlow()
    private var historyJob: Job? = null
    private var lastHistoryAttemptMillis: Long? = null
    private var observedHistoryDate: LocalDate? = null

    init {
        // Start once when the zone first appears. The chart reads the local store and re-emits as the
        // history sync lands new readings; the cloud is only asked when the store has nothing for today.
        viewModelScope.launch {
            val zone = realZone.filterNotNull().first()
            startTodayHistory(zone)
        }
    }

    /**
     * Called when the screen resumes. After 10 minutes it asks the sync for a top-up (the sync decides
     * whether one is really due); after midnight it re-observes so "today" moves on.
     */
    fun onResume() {
        val last = lastHistoryAttemptMillis ?: return // initial load not started yet
        val zone = realZone.value ?: return
        if (observedHistoryDate != GoodHiveTime.londonDateNow()) {
            startTodayHistory(zone)
            return
        }
        if (System.currentTimeMillis() - last < HISTORY_REFRESH_MIN_AGE_MS) return
        lastHistoryAttemptMillis = System.currentTimeMillis()
        viewModelScope.launch { todayHistoryLoader.topUpIfDue() }
    }

    private fun startTodayHistory(zone: HeatingZone) {
        historyJob?.cancel()
        lastHistoryAttemptMillis = System.currentTimeMillis()
        observedHistoryDate = GoodHiveTime.londonDateNow()
        historyJob = viewModelScope.launch {
            todayHistoryLoader.observe(zone.historySourceIds).collect { state ->
                // A failed fallback must not blank a chart that already has readings.
                if (state is TodayHistoryState.Unavailable && _todayHistory.value is TodayHistoryState.Ready) return@collect
                _todayHistory.value = state
            }
        }
    }

    fun setTarget(targetC: Double) {
        val current = realZone.value ?: return
        submitChange(target = targetC) {
            commandExecutor.enqueue(
                zoneId = current.id,
                zoneName = current.name,
                productType = current.productType,
                description = "Set ${current.name} to ${"%.1f".format(targetC)}°C",
                payload = buildTargetPayload(targetC),
                expectedState = expectedStateForTarget(targetC),
            )
        }
    }

    /**
     * Ends a running Heat on Demand boost by lowering every source TRV's target to its schedule slot
     * (see [stopBoostTargetFor]); Heat on Demand itself stays enabled. Turning the thermostat OFF can
     * be undone by Hive ~80 s later while a source is still calling, so the sources are the fix. One
     * command per source - the boost only ends once no source is calling.
     */
    fun stopHeatOnDemandBoost() {
        val sources = uiState.value.hodSources
        sources.forEach { source ->
            val targetC = stopBoostTargetFor(source) ?: return@forEach
            viewModelScope.launch {
                commandExecutor.enqueue(
                    zoneId = source.id,
                    zoneName = source.name,
                    productType = source.productType,
                    description = "Stop Heat on Demand boost from ${source.name}",
                    payload = buildTargetPayload(targetC),
                    expectedState = expectedStateForTarget(targetC),
                )
            }
        }
    }

    fun setMode(mode: String) {
        val current = realZone.value ?: return
        // MANUAL sends the UI target; OFF sends the frost setpoint; SCHEDULE sends the current slot's
        // target from the schedule (never the UI target). See buildModePayload.
        val target = optimisticTarget.value ?: current.targetTemperatureC
        val scheduleTarget = if (mode == HeatingMode.SCHEDULE) current.scheduleNowNextLater()?.currentTargetC else null
        val optimistic = if (mode == HeatingMode.SCHEDULE) scheduleTarget else target.takeIf { mode == "MANUAL" }
        submitChange(mode = mode, target = optimistic) {
            commandExecutor.enqueue(
                zoneId = current.id,
                zoneName = current.name,
                productType = current.productType,
                description = "Set ${current.name} to $mode",
                payload = buildModePayload(
                    mode,
                    target,
                    offTargetC = current.frostProtectionC,
                    scheduleTargetC = scheduleTarget,
                ),
                expectedState = expectedStateForMode(mode, target, scheduleTarget),
            )
        }
    }

    /**
     * Ends a boost the way Pyhiveapi's `set_boost_off` does: restore `props.previous` when it was
     * MANUAL/OFF, otherwise return to SCHEDULE with the current slot target (see [buildCancelBoostPayload]).
     */
    fun cancelBoost() {
        val current = realZone.value ?: return
        val slotTarget = current.scheduleNowNextLater()?.currentTargetC
        val payload = buildCancelBoostPayload(current.previousMode, current.previousTargetC, slotTarget)
        val expected = expectedStateForCancelBoost(current.previousMode, current.previousTargetC, slotTarget)
        val restoredMode = expected.getValue("mode")
        submitChange(mode = restoredMode, target = expected["target"]?.toDoubleOrNull()) {
            commandExecutor.enqueue(
                zoneId = current.id,
                zoneName = current.name,
                productType = current.productType,
                description = "Cancel boost on ${current.name}",
                payload = payload,
                expectedState = expected,
            )
        }
    }

    fun boost(minutes: Int, targetC: Double) {
        val current = realZone.value ?: return
        submitChange(mode = "BOOST", target = targetC) {
            commandExecutor.enqueue(
                zoneId = current.id,
                zoneName = current.name,
                productType = current.productType,
                description = "Boost ${current.name} to ${"%.1f".format(targetC)}°C for $minutes min",
                payload = buildBoostPayload(minutes, targetC),
                expectedState = expectedStateForBoost(targetC),
            )
        }
    }

    fun setHeatOnDemand(enabled: Boolean) {
        val current = realZone.value ?: return
        val newValue = if (enabled) "ENABLED" else "DISABLED"
        // Compare with displayed intent so a rapid ON -> OFF can supersede the pending ON.
        if ((optimisticHeatOnDemand.value ?: effectiveHeatOnDemand(current.heatOnDemand)) == newValue) return
        submitChange(heatOnDemand = newValue) { isCurrent ->
            if (enabled) heatingRepository.refresh(force = false)
            if (!isCurrent()) return@submitChange null
            val thermostatZoneId = if (enabled) {
                heatingRepository.thermostatHiveZoneId() ?: current.hiveZoneId
                    ?: error("Primary thermostat zone unknown — refresh and retry")
            } else null
            if (!isCurrent()) return@submitChange null
            commandExecutor.enqueue(
                zoneId = current.id,
                zoneName = current.name,
                productType = current.productType,
                description = "Turn heat on demand $newValue for ${current.name}",
                payload = buildHeatOnDemandPayload(enabled, thermostatZoneId),
                expectedState = expectedStateForHeatOnDemand(enabled),
            )
        }
    }

    /** Retries by re-enqueuing the failed command's original payload/expected state. */
    fun retryLastFailure() {
        viewModelScope.launch {
            commandExecutor.retryLatestFailed(zoneId)
            commandLogDao.dismissFailuresForZone(zoneId)
        }
    }

    fun dismissLastFailure() {
        viewModelScope.launch {
            commandLogDao.dismissFailuresForZone(zoneId)
        }
    }

    /** Local feedback bridges enqueue; durable command state owns its lifetime after that. */
    private fun submitChange(
        mode: String? = null,
        target: Double? = null,
        heatOnDemand: String? = null,
        enqueue: suspend (isCurrent: () -> Boolean) -> Long?,
    ) {
        val group = if (heatOnDemand != null) "heatOnDemand" else "heating"
        val version = (changeVersions[group] ?: 0L) + 1
        changeVersions[group] = version
        if (heatOnDemand != null) optimisticHeatOnDemand.value = heatOnDemand else {
            optimisticMode.value = mode
            optimisticTarget.value = target
        }
        viewModelScope.launch {
            try {
                val commandId = enqueue { changeVersions[group] == version }
                if (commandId != null) commandExecutor.awaitSettled(commandId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (changeVersions[group] != version) return@launch
                commandLogDao.insert(
                    CommandLogEntity(
                        zoneId = zoneId, zoneName = realZone.value?.name ?: zoneId,
                        description = "Couldn't save change — ${error.message ?: "please retry"}",
                        outcome = "FAILED", createdAtEpochMillis = System.currentTimeMillis(),
                        resolvedAtEpochMillis = System.currentTimeMillis(), attemptCount = 0,
                    ),
                )
            } finally {
                if (changeVersions[group] == version) {
                    if (heatOnDemand != null) optimisticHeatOnDemand.value = null else {
                        optimisticMode.value = null
                        optimisticTarget.value = null
                    }
                }
            }
        }
    }

    companion object {
        private const val RECENT_FAILURE_WINDOW_MS = 15 * 60_000L
        private const val HISTORY_REFRESH_MIN_AGE_MS = 10 * 60_000L
    }
}
