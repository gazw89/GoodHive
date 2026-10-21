package com.gazw89.goodhive.ui.schedule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.db.CommandLogDao
import com.gazw89.goodhive.data.repository.CommandExecutor
import com.gazw89.goodhive.data.repository.HeatingRepository
import com.gazw89.goodhive.data.repository.buildSchedulePayload
import com.gazw89.goodhive.data.repository.expectedStateForSchedule
import com.gazw89.goodhive.ui.theme.clampToTemperatureStep
import com.gazw89.goodhive.util.GoodHiveTime
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ScheduleUiState(
    val zone: HeatingZone? = null,
    val selectedDay: String = WeekSchedule.DAY_KEYS[GoodHiveTime.londonDateNow().dayOfWeek.value - 1],
    val slots: List<ScheduleSlot> = emptyList(),
    val isSaving: Boolean = false,
    val otherZones: List<HeatingZone> = emptyList(),
    val pendingPreview: SchedulePreview? = null,
    val lastFailureMessage: String? = null,
)

sealed class SchedulePreview {
    data class CopyDay(
        val sourceDay: String,
        val targetDays: Set<String>,
        val slots: List<ScheduleSlot>,
        val resultingDays: Map<String, List<ScheduleSlot>>,
        val minTemperatureC: Double,
        val maxTemperatureC: Double,
    ) : SchedulePreview()

    data class CopyToZone(
        val sourceZoneName: String,
        val target: HeatingZone,
        val resultingDays: Map<String, List<ScheduleSlot>>,
        val clampedDays: Set<String>,
        val blockedDays: Map<String, Int>,
    ) : SchedulePreview() {
        val canApply: Boolean get() = blockedDays.size < WeekSchedule.DAY_KEYS.size
    }
}

/**
 * List-based schedule editor. Bulk copy actions stage a [SchedulePreview] first; per-slot
 * add/edit/delete still auto-save via [saveDay].
 */
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val heatingRepository: HeatingRepository,
    private val commandExecutor: CommandExecutor,
    private val commandLogDao: CommandLogDao,
) : ViewModel() {

    private val zoneId: String = checkNotNull(savedStateHandle["zoneId"])
    private val selectedDay = MutableStateFlow(
        WeekSchedule.DAY_KEYS[GoodHiveTime.londonDateNow().dayOfWeek.value - 1],
    )
    private val isSaving = MutableStateFlow(false)
    private val optimisticSchedules = MutableStateFlow<Map<String, WeekSchedule>>(emptyMap())
    private val changeVersions = mutableMapOf<String, Long>()
    private val pendingPreview = MutableStateFlow<SchedulePreview?>(null)

    private val lastFailureMessage = commandLogDao.observeUnresolvedFailures()
        .map { failures ->
            val cutoff = System.currentTimeMillis() - 15 * 60_000L
            failures.firstOrNull {
                it.zoneId == zoneId &&
                    it.resolvedAtEpochMillis >= cutoff &&
                    it.description.contains("schedule", ignoreCase = true)
            }?.description
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<ScheduleUiState> = combine(
        combine(commandExecutor.observeZones(), optimisticSchedules) { zones, drafts ->
            zones.map { zone -> drafts[zone.id]?.let { zone.copy(schedule = it) } ?: zone }
        },
        selectedDay,
        combine(isSaving, commandExecutor.observePending()) { local, pending ->
            local || pending.any { it.zoneId == zoneId && "\"schedule\"" in it.payloadJson }
        },
        pendingPreview,
        lastFailureMessage,
    ) { zones, day, saving, preview, failure ->
        val zone = zones.firstOrNull { it.id == zoneId }
        ScheduleUiState(
            zone = zone,
            selectedDay = day,
            slots = zone?.schedule?.days?.get(day).orEmpty(),
            isSaving = saving,
            otherZones = zones.filter { it.id != zoneId },
            pendingPreview = preview,
            lastFailureMessage = failure,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUiState())

    fun selectDay(day: String) {
        selectedDay.value = day
    }

    fun saveDay(day: String, slots: List<ScheduleSlot>) {
        val zone = uiState.value.zone ?: return
        // maxEvents is a real, confirmed Hive-side limit — enforce it here too
        // rather than letting a rejected write surface as a confusing generic failure.
        if (slots.size > zone.maxEventsPerDay) return

        val updatedDays = (optimisticSchedules.value[zone.id] ?: zone.schedule).days.toMutableMap()
        updatedDays[day] = slots.sortedBy { it.startMinutes }
        enqueueScheduleWrite(
            zone = zone,
            schedule = WeekSchedule(updatedDays),
            description = "Update ${zone.name}'s $day schedule",
        )
    }

    fun prepareCopyDayPreview(targetDays: Set<String>) {
        val zone = uiState.value.zone ?: return
        val sourceDay = selectedDay.value
        val targets = targetDays.filter { it != sourceDay && it in WeekSchedule.DAY_KEYS }.toSet()
        if (targets.isEmpty()) return

        val slots = zone.schedule.days[sourceDay].orEmpty().sortedBy { it.startMinutes }
        val resultingDays = zone.schedule.days.toMutableMap()
        targets.forEach { day -> resultingDays[day] = slots }

        pendingPreview.value = SchedulePreview.CopyDay(
            sourceDay = sourceDay,
            targetDays = targets,
            slots = slots,
            resultingDays = resultingDays,
            minTemperatureC = zone.minTemperatureC,
            maxTemperatureC = zone.maxTemperatureC,
        )
    }

    fun prepareCopyToZonePreview(targetZoneId: String) {
        val source = uiState.value.zone ?: return
        val target = uiState.value.otherZones.firstOrNull { it.id == targetZoneId } ?: return

        val resultingDays = target.schedule.days.toMutableMap()
        val clampedDays = mutableSetOf<String>()
        val blockedDays = mutableMapOf<String, Int>()

        WeekSchedule.DAY_KEYS.forEach { day ->
            val sourceSlots = source.schedule.days[day].orEmpty()
            if (sourceSlots.size > target.maxEventsPerDay) {
                blockedDays[day] = sourceSlots.size
                return@forEach
            }
            var dayClamped = false
            val copied = sourceSlots.map { slot ->
                val clamped = clampToTemperatureStep(
                    slot.targetTemperatureC,
                    target.minTemperatureC,
                    target.maxTemperatureC,
                )
                if (clamped != slot.targetTemperatureC) dayClamped = true
                slot.copy(targetTemperatureC = clamped)
            }.sortedBy { it.startMinutes }
            if (dayClamped) clampedDays += day
            resultingDays[day] = copied
        }

        pendingPreview.value = SchedulePreview.CopyToZone(
            sourceZoneName = source.name,
            target = target,
            resultingDays = resultingDays,
            clampedDays = clampedDays,
            blockedDays = blockedDays,
        )
    }

    fun confirmPendingPreview() {
        when (val preview = pendingPreview.value) {
            is SchedulePreview.CopyDay -> {
                val zone = uiState.value.zone ?: return
                val targetsLabel = preview.targetDays.joinToString { dayLabel(it) }
                enqueueScheduleWrite(
                    zone = zone,
                    schedule = WeekSchedule(preview.resultingDays),
                    description = "Copy ${dayLabel(preview.sourceDay)} schedule to $targetsLabel",
                )
            }
            is SchedulePreview.CopyToZone -> {
                if (!preview.canApply) return
                enqueueScheduleWrite(
                    zone = preview.target,
                    schedule = WeekSchedule(preview.resultingDays),
                    description = "Copy ${preview.sourceZoneName} schedule to ${preview.target.name}",
                )
            }
            null -> return
        }
        pendingPreview.value = null
    }

    fun dismissPreview() {
        pendingPreview.value = null
    }

    private fun enqueueScheduleWrite(zone: HeatingZone, schedule: WeekSchedule, description: String) {
        val version = (changeVersions[zone.id] ?: 0L) + 1
        changeVersions[zone.id] = version
        optimisticSchedules.value = optimisticSchedules.value + (zone.id to schedule)
        isSaving.value = true
        viewModelScope.launch {
            try {
                val id = commandExecutor.enqueue(
                    zoneId = zone.id,
                    zoneName = zone.name,
                    productType = zone.productType,
                    description = description,
                    payload = buildSchedulePayload(schedule),
                    expectedState = expectedStateForSchedule(schedule),
                )
                commandExecutor.awaitSettled(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                commandLogDao.insert(
                    com.gazw89.goodhive.data.db.CommandLogEntity(
                        zoneId = zone.id, zoneName = zone.name,
                        description = "$description — couldn't save schedule; please retry",
                        outcome = "FAILED", createdAtEpochMillis = System.currentTimeMillis(),
                        resolvedAtEpochMillis = System.currentTimeMillis(), attemptCount = 0,
                    ),
                )
            } finally {
                if (changeVersions[zone.id] == version) optimisticSchedules.value = optimisticSchedules.value - zone.id
                isSaving.value = optimisticSchedules.value.isNotEmpty()
            }
        }
    }
}

internal fun dayLabel(dayKey: String): String =
    dayKey.replaceFirstChar { it.uppercase() }
