package com.gazw89.goodhive.ui.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.auth.AuthRepository
import com.gazw89.goodhive.auth.HiveAuthException
import com.gazw89.goodhive.data.db.CommandLogDao
import com.gazw89.goodhive.data.db.RoomOrderDao
import com.gazw89.goodhive.data.db.RoomOrderEntity
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.util.scheduleNowNextLater
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.HouseholdHeating
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.ZoneActivity
import com.gazw89.goodhive.data.model.activity
import com.gazw89.goodhive.data.model.householdHeating
import com.gazw89.goodhive.data.repository.catching
import com.gazw89.goodhive.data.repository.CommandExecutor
import com.gazw89.goodhive.data.repository.HeatingRepository
import com.gazw89.goodhive.data.repository.HiveRateLimitedException
import com.gazw89.goodhive.data.repository.buildModePayload
import com.gazw89.goodhive.data.repository.buildTargetPayload
import com.gazw89.goodhive.data.repository.expectedStateForMode
import com.gazw89.goodhive.data.repository.expectedStateForTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


data class DashboardUiState(
    val zones: List<HeatingZone> = emptyList(),
    val lastSyncedAtEpochMillis: Long? = null,
    val isRefreshing: Boolean = false,
    val syncError: String? = null,
    val sessionExpired: Boolean = false,
    /** Compact notice when a Hive write failed to confirm for any zone. */
    val commandFailureBanner: String? = null,
    val pendingZoneIds: Set<String> = emptySet(),
    /** Household-wide boiler / Heat on Demand state, derived from [zones] (post-optimistic). */
    val household: HouseholdHeating = HouseholdHeating(
        boilerOn = null,
        hodActive = false,
        hodSourceIds = emptySet(),
    ),
    /** What each zone is doing right now, keyed by zone id; derived from [zones] + [household]. */
    val activities: Map<String, ZoneActivity> = emptyMap(),
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val heatingRepository: HeatingRepository,
    private val commandExecutor: CommandExecutor,
    private val authRepository: AuthRepository,
    private val roomOrderDao: RoomOrderDao,
    private val commandLogDao: CommandLogDao,
) : ViewModel() {

    private val isRefreshing = MutableStateFlow(false)
    private val syncError = MutableStateFlow<String?>(null)
    private val lastSyncedAt = MutableStateFlow<Long?>(null)
    private val sessionExpired = MutableStateFlow(false)

    /** Cold-start / flaky-radio: don't paint "couldn't reach Hive" on the first blip. */
    private val startedAtEpochMillis = System.currentTimeMillis()
    private var consecutiveSyncFailures = 0
    private var consecutiveAuthFailures = 0

    // Short-lived optimistic overrides for in-flight quick-adjust taps, keyed by zoneId — same
    // pattern as DeviceDetailViewModel, so a dashboard ± tap moves the number instantly instead
    // of waiting for the next poll to write the cache back.
    private val optimisticTargets = MutableStateFlow<Map<String, Double>>(emptyMap())

    // Same idea for "Turn on" (mode change) taps, keyed by zoneId -> Hive mode string.
    private val optimisticModes = MutableStateFlow<Map<String, String>>(emptyMap())
    private val changeVersions = mutableMapOf<String, Long>()

    // The main thermostat is always pinned first (never draggable); TRVs sort by the user's
    // saved drag order, with any zone that hasn't been manually placed yet falling back to the
    // end in name order rather than jumping around unpredictably.
    private val sortedZones: StateFlow<List<HeatingZone>> = combine(
        commandExecutor.observeZones(),
        roomOrderDao.observeAll(),
    ) { zones, orders ->
        val position = orders.associate { it.zoneId to it.position }
        val (thermostats, trvs) = zones.partition { it.productType == ProductType.HEATING }
        thermostats.sortedBy { it.name } +
            trvs.sortedWith(compareBy({ position[it.id] ?: Int.MAX_VALUE }, { it.name }))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val commandFailureBanner: StateFlow<String?> = commandLogDao.observeUnresolvedFailures()
        .map { failures ->
            val cutoff = System.currentTimeMillis() - 15 * 60_000L
            val recent = failures.filter { it.resolvedAtEpochMillis >= cutoff }
            when {
                recent.isEmpty() -> null
                recent.size == 1 -> {
                    val f = recent.first()
                    "Couldn't confirm change for ${f.zoneName} — open the room to retry"
                }
                else -> "Couldn't confirm ${recent.size} changes — open affected rooms to retry"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val baseState: StateFlow<DashboardUiState> = combine(
        combine(
            sortedZones,
            isRefreshing,
            syncError,
            lastSyncedAt,
            sessionExpired,
        ) { zones, refreshing, err, syncedAt, expired ->
            DashboardUiState(
                zones = zones,
                isRefreshing = refreshing,
                syncError = err,
                lastSyncedAtEpochMillis = syncedAt,
                sessionExpired = expired,
            )
        },
        commandFailureBanner,
        commandExecutor.observePending(),
    ) { state, commandBanner, pending ->
        state.copy(commandFailureBanner = commandBanner, pendingZoneIds = pending.map { it.zoneId }.toSet())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    val uiState: StateFlow<DashboardUiState> = combine(
        baseState,
        optimisticTargets,
        optimisticModes,
    ) { state, targetOverrides, modeOverrides ->
        val zones = if (targetOverrides.isEmpty() && modeOverrides.isEmpty()) {
            state.zones
        } else {
            state.zones.map { zone ->
                var updated = zone
                targetOverrides[zone.id]?.let { updated = updated.copy(targetTemperatureC = it) }
                modeOverrides[zone.id]?.let { updated = updated.copy(mode = it) }
                updated
            }
        }
        // Derive after overrides so the status ladder reacts instantly to optimistic taps.
        val household = householdHeating(zones)
        state.copy(
            zones = zones,
            pendingZoneIds = state.pendingZoneIds + targetOverrides.keys + modeOverrides.keys,
            household = household,
            activities = zones.associate { it.id to it.activity(household) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    init {
        // The dashboard stays on the navigation back stack while detail/schedule is open.
        // Its poll loop follows the activity, rather than the longer ViewModel lifetime.
        viewModelScope.launch {
            heatingRepository.foregroundActive.collectLatest { active ->
                if (active) {
                    while (true) {
                        refreshNow(force = false)
                        delay(25_000)
                    }
                }
            }
        }
        viewModelScope.launch { commandExecutor.resumeInFlightCommands() }
    }

    /**
     * @param force when true (pull-to-refresh), force a Hive network round-trip.
     * Background 25s polls reuse a fresh cache.
     */
    fun refresh(force: Boolean = false) {
        viewModelScope.launch { refreshNow(force) }
    }

    private suspend fun refreshNow(force: Boolean) {
        if (isRefreshing.value) return
        isRefreshing.value = true
        try {
            catching { heatingRepository.refresh(force = force) }
                .onSuccess { didFetch ->
                    consecutiveSyncFailures = 0
                    consecutiveAuthFailures = 0
                    syncError.value = null
                    if (didFetch || lastSyncedAt.value == null) {
                        lastSyncedAt.value = System.currentTimeMillis()
                    }
                }
                .onFailure { error ->
                    consecutiveSyncFailures += 1
                    Log.w(
                        "GoodHiveSync",
                        "Dashboard refresh failed (#$consecutiveSyncFailures): " +
                            "${error.javaClass.simpleName}: ${error.message}",
                        error,
                    )
                    if (error is HiveRateLimitedException || shouldSurfaceSyncError()) {
                        syncError.value = error.toFriendlyMessage()
                    }
                    if (error.isAuthFailure()) {
                        consecutiveAuthFailures += 1
                        Log.w(
                            "GoodHiveAuth",
                            "Definite session rejection on dashboard refresh " +
                                "(#$consecutiveAuthFailures): ${error.message}",
                        )
                        if (consecutiveAuthFailures >= AUTH_FAILURES_BEFORE_REAUTH) {
                            authRepository.markSessionNeedsReauth()
                            sessionExpired.value = true
                        }
                    } else {
                        consecutiveAuthFailures = 0
                    }
                }
        } finally {
            isRefreshing.value = false
        }
    }

    private fun shouldSurfaceSyncError(): Boolean {
        val pastColdStart =
            System.currentTimeMillis() - startedAtEpochMillis >= SYNC_ERROR_COLD_START_GRACE_MS
        return pastColdStart && consecutiveSyncFailures >= SYNC_FAILURES_BEFORE_ERROR
    }

    companion object {
        private const val SYNC_ERROR_COLD_START_GRACE_MS = 8_000L
        private const val SYNC_FAILURES_BEFORE_ERROR = 2
        private const val AUTH_FAILURES_BEFORE_REAUTH = 2
    }

    private fun Throwable.isAuthFailure(): Boolean =
        this is HiveAuthException && isDefiniteSessionRejection

    /**
     * Raw exception text ("Invalid Refresh Token.") reads as an alarming technical error the
     * moment the app opens, especially since zones are already showing from cache — swap it
     * for copy that says what the user can actually do about it.
     */
    private fun Throwable.toFriendlyMessage(): String = when {
        isAuthFailure() -> "Sign-in expired — please sign in again"
        this is HiveRateLimitedException ->
            "Hive is rate-limiting requests — retry in ${(retryAfterMs + 999) / 1000}s"
        else -> "Couldn't reach Hive — showing your last saved data"
    }

    /**
     * Quick +/- stepper from the dashboard card. Goes through the reliability layer, never a
     * bare API call, and shows the new value immediately via [optimisticTargets] rather than
     * waiting for the next poll — same reasoning as DeviceDetailViewModel's optimistic overrides.
     */
    fun quickAdjust(zone: HeatingZone, deltaC: Double) {
        val current = optimisticTargets.value[zone.id] ?: zone.targetTemperatureC ?: zone.frostProtectionC
        val newTarget = (current + deltaC).coerceIn(zone.minTemperatureC, zone.maxTemperatureC)
        setOptimisticTarget(zone, newTarget)
    }

    /**
     * "Turn on" for an OFF zone: hands it back to its schedule. Writes mode plus the current slot target (mode-only if unknown) via
     * the reliability layer, with an optimistic mode so the card leaves the Off state instantly.
     */
    fun turnOn(zoneId: String) {
        val zone = baseState.value.zones.firstOrNull { it.id == zoneId } ?: return
        val slotTarget = zone.scheduleNowNextLater()?.currentTargetC
        submitChange(zone, mode = HeatingMode.SCHEDULE, target = slotTarget) {
            commandExecutor.enqueue(
                zoneId = zone.id,
                zoneName = zone.name,
                productType = zone.productType,
                description = "Turn on ${zone.name} (schedule)",
                payload = buildModePayload(HeatingMode.SCHEDULE, scheduleTargetC = slotTarget),
                expectedState = expectedStateForMode(HeatingMode.SCHEDULE, scheduleTargetC = slotTarget),
            )
        }
    }

    /** Returns after the explicit Save succeeds; failures stay visible in the reorder sheet. */
    suspend fun commitTrvOrder(orderedZoneIds: List<String>) {
        roomOrderDao.upsertAll(orderedZoneIds.mapIndexed { index, zoneId -> RoomOrderEntity(zoneId, index) })
    }

    private fun setOptimisticTarget(zone: HeatingZone, newTarget: Double) {
        submitChange(zone, target = newTarget) {
            commandExecutor.enqueue(
                zoneId = zone.id,
                zoneName = zone.name,
                productType = zone.productType,
                description = "Set ${zone.name} to ${"%.1f".format(newTarget)}°C",
                payload = buildTargetPayload(newTarget),
                expectedState = expectedStateForTarget(newTarget),
            )
        }
    }

    private fun submitChange(zone: HeatingZone, mode: String? = null, target: Double? = null, enqueue: suspend () -> Long) {
        val version = (changeVersions[zone.id] ?: 0L) + 1
        changeVersions[zone.id] = version
        optimisticModes.value = optimisticModes.value - zone.id + listOfNotNull(mode?.let { zone.id to it })
        optimisticTargets.value = optimisticTargets.value - zone.id + listOfNotNull(target?.let { zone.id to it })
        viewModelScope.launch {
            try {
                commandExecutor.awaitSettled(enqueue())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                syncError.value = "Couldn't save change for ${zone.name} — please retry"
            } finally {
                if (changeVersions[zone.id] == version) {
                    optimisticModes.value = optimisticModes.value - zone.id
                    optimisticTargets.value = optimisticTargets.value - zone.id
                }
            }
        }
    }
}
