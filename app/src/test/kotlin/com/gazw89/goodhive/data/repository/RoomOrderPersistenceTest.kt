package com.gazw89.goodhive.data.repository

import androidx.lifecycle.ViewModelStore
import com.gazw89.goodhive.data.db.RoomOrderDao
import com.gazw89.goodhive.data.db.RoomOrderEntity
import com.gazw89.goodhive.ui.dashboard.DashboardViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Dispatchers

@OptIn(ExperimentalCoroutinesApi::class)
class RoomOrderPersistenceTest : RepositoryTestBase() {
    @Test fun saveWaitsForLocalPersistenceAndReportsFailureToTheSheet() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val gate = CompletableDeferred<Unit>()
            var saved = emptyList<RoomOrderEntity>()
            var failSave = false
            val orders = object : RoomOrderDao {
                override fun observeAll() = MutableStateFlow<List<RoomOrderEntity>>(emptyList())
                override suspend fun upsertAll(orders: List<RoomOrderEntity>) {
                    gate.await()
                    check(!failSave) { "Storage unavailable" }
                    saved = orders
                }
            }
            val executor = CommandExecutor(pending, zones, repository, json, backgroundScope)
            val vm = DashboardViewModel(repository, executor, auth, orders, FakeLogDao())
            store.put("dashboard", vm)

            val save = async { vm.commitTrvOrder(listOf("bedroom", "kitchen")) }
            runCurrent()
            assertFalse(save.isCompleted)
            gate.complete(Unit)
            save.await()
            assertEquals(listOf(RoomOrderEntity("bedroom", 0), RoomOrderEntity("kitchen", 1)), saved)

            failSave = true
            assertFailsWith<IllegalStateException> { vm.commitTrvOrder(listOf("kitchen", "bedroom")) }
            assertEquals("bedroom", saved.first().zoneId)
            assertTrue(api.writes.isEmpty())
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
