package com.gazw89.goodhive.data.repository

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.ui.dashboard.DashboardViewModel
import com.gazw89.goodhive.ui.detail.noopTodayHistoryLoader
import com.gazw89.goodhive.ui.detail.DeviceDetailViewModel
import com.gazw89.goodhive.ui.schedule.ScheduleViewModel
import com.gazw89.goodhive.data.db.RoomOrderDao
import com.gazw89.goodhive.data.db.RoomOrderEntity
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class PendingUiTest : RepositoryTestBase() {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val logs = FakeLogDao()
    @Before fun setMainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun clearViewModels() { store.clear(); Dispatchers.resetMain() }
    private fun <T : ViewModel> keep(vm: T): T = vm.also { store.put(it.javaClass.name, it) }
    private fun TestScope.executor(scope: CoroutineScope = this) =
        CommandExecutor(pending, zones, repository, json, scope) { testScheduler.currentTime }
    private val handle get() = SavedStateHandle(mapOf("zoneId" to "zone"))

    @Test fun delayedWriteDoesNotSilentlyLoseItsOptimisticValueAfterThreeMinutes() = runTest(dispatcher) {
        configureToken()
        repository.refresh()
        api.set = { _, body -> delay(240_000); api.state = JsonObject(api.state + body) }
        val vm = keep(DeviceDetailViewModel(handle, repository, executor(this), logs, noopTodayHistoryLoader()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        runCurrent()
        vm.setTarget(21.0); runCurrent()
        advanceTimeBy(180_001); runCurrent()
        assertEquals(21.0, vm.uiState.value.zone?.targetTemperatureC)
        assertTrue(vm.uiState.value.isApplying)
        advanceTimeBy(68_000); runCurrent()
        assertFalse(vm.uiState.value.isApplying)
        assertEquals(21.0, vm.uiState.value.zone?.targetTemperatureC)
    }

    @Test fun repeatedValueDoesNotLetOlderCommandClearNewerIntent() = runTest(dispatcher) {
        configureToken()
        repository.refresh()
        api.set = { _, body -> delay(10_000); api.state = JsonObject(api.state + body) }
        val vm = keep(DeviceDetailViewModel(handle, repository, executor(this), logs, noopTodayHistoryLoader()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        runCurrent()
        vm.setTarget(21.0); runCurrent()
        vm.setTarget(19.0)
        vm.setTarget(21.0)
        runCurrent()
        assertEquals(21.0, vm.uiState.value.zone?.targetTemperatureC)
        assertTrue(vm.uiState.value.isApplying)
        advanceTimeBy(28_001); runCurrent()
        assertEquals(21.0, vm.uiState.value.zone?.targetTemperatureC)
        assertFalse(vm.uiState.value.isApplying)
    }

    @Test fun delayedHeatOnDemandSetupCannotEnqueueAfterNewerOffAction() = runTest(dispatcher) {
        configureToken()
        repository.refresh()
        val gate = CompletableDeferred<Unit>()
        api.get = { gate.await(); api.snapshot() }
        // Force a refresh into flight; enabling must wait for this fresh lookup.
        val refresh = launch { repository.refresh(force = true) }
        runCurrent()
        val vm = keep(DeviceDetailViewModel(handle, repository, executor(this), logs, noopTodayHistoryLoader()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        runCurrent()
        vm.setHeatOnDemand(true); runCurrent()
        vm.setHeatOnDemand(false); runCurrent()
        gate.complete(Unit); refresh.join(); advanceUntilIdle()
        assertTrue(api.writes.none { it.second["autoBoost"]?.jsonPrimitive?.content == "ENABLED" })
        assertEquals("DISABLED", effectiveHeatOnDemand(vm.uiState.value.zone?.heatOnDemand))
    }

    @Test fun rapidEditsToDifferentDaysPreserveBothInTheFullWeekWrite() = runTest(dispatcher) {
        configureToken()
        repository.refresh()
        val vm = keep(ScheduleViewModel(handle, repository, executor(this), logs))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        runCurrent()
        val monday = listOf(ScheduleSlot(360, 19.0))
        val tuesday = listOf(ScheduleSlot(480, 20.0))
        vm.saveDay("monday", monday)
        vm.saveDay("tuesday", tuesday)
        runCurrent()
        val schedule = api.writes.last().second.getValue("schedule").jsonObject
        assertEquals(19.0, schedule.getValue("monday").jsonArray.single().jsonObject.getValue("value").jsonObject.getValue("target").jsonPrimitive.double)
        assertEquals(20.0, schedule.getValue("tuesday").jsonArray.single().jsonObject.getValue("value").jsonObject.getValue("target").jsonPrimitive.double)
        assertTrue(vm.uiState.value.isSaving)
        advanceTimeBy(8_001); runCurrent()
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test fun navigationToDetailRetainsCommandStartedElsewhere() = runTest(dispatcher) {
        configureToken()
        repository.refresh()
        api.set = { _, body -> delay(30_000); api.state = JsonObject(api.state + body) }
        val executor = executor(this)
        executor.enqueue("zone", "Room", "trvcontrol", "Target", buildTargetPayload(21.0), expectedStateForTarget(21.0))
        runCurrent()
        val vm = keep(DeviceDetailViewModel(handle, repository, executor, logs, noopTodayHistoryLoader()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        runCurrent()
        assertEquals(21.0, vm.uiState.value.zone?.targetTemperatureC)
        assertTrue(vm.uiState.value.isApplying)
        advanceTimeBy(38_001); runCurrent()
        assertFalse(vm.uiState.value.isApplying)
    }

    @Test fun foregroundPollingStopsInBackgroundAndRefreshesOnResume() = runTest(dispatcher) {
        configureToken()
        val orders = object : RoomOrderDao {
            override fun observeAll() = MutableStateFlow<List<RoomOrderEntity>>(emptyList())
            override suspend fun upsertAll(orders: List<RoomOrderEntity>) = Unit
        }
        keep(DashboardViewModel(repository, executor(this), auth, orders, logs))
        repository.setForegroundActive(true); runCurrent()
        assertEquals(1, api.getCount)
        repository.setForegroundActive(false); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, api.getCount)
        repository.setForegroundActive(true); runCurrent()
        // Resume invokes refresh immediately, but a still-fresh cache is reused.
        assertEquals(1, api.getCount)
        repository.setForegroundActive(false); runCurrent()
    }
}
