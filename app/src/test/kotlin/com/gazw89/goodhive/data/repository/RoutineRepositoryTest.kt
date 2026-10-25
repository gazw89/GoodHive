package com.gazw89.goodhive.data.repository

import android.content.Context
import com.gazw89.goodhive.data.db.RoutineDao
import com.gazw89.goodhive.data.db.RoutineEntity
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.WeekSchedule
import com.gazw89.goodhive.data.model.routines.Routine
import com.gazw89.goodhive.data.model.routines.RoutineStep
import com.gazw89.goodhive.data.prefs.RoutinePreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito

internal class FakeRoutineDao : RoutineDao {
    val rows = MutableStateFlow<List<RoutineEntity>>(emptyList())
    private var nextId = 1L
    override fun observeAll() = rows.map { list -> list.sortedWith(compareBy({ it.position }, { it.id })) }
    override suspend fun getById(id: Long) = rows.value.find { it.id == id }
    override suspend fun count() = rows.value.size
    override suspend fun nextPosition() = (rows.value.maxOfOrNull { it.position } ?: -1) + 1
    override suspend fun insert(routine: RoutineEntity): Long {
        val id = nextId++
        rows.value += routine.copy(id = id)
        return id
    }
    override suspend fun update(routine: RoutineEntity) { rows.value = rows.value.map { if (it.id == routine.id) routine else it } }
    override suspend fun delete(id: Long) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun setPosition(id: Long, position: Int) { rows.value = rows.value.map { if (it.id == id) it.copy(position = position) else it } }
}

private class FakeRoutinePreferences : RoutinePreferences(Mockito.mock(Context::class.java)) {
    var seeded = false
    override suspend fun isSeeded() = seeded
    override suspend fun setSeeded(seeded: Boolean) { this.seeded = seeded }
}

class RoutineRepositoryTest {
    private val dao = FakeRoutineDao()
    private val prefs = FakeRoutinePreferences()
    private val repository = RoutineRepository(dao, prefs)
    private val zones = listOf(
        HeatingZone(
            id = "thermo", productType = ProductType.HEATING, name = "Thermostat", online = true, currentTemperatureC = 17.0,
            targetTemperatureC = 18.0, mode = HeatingMode.SCHEDULE, frostProtectionC = 7.0, maxEventsPerDay = 6,
            schedule = WeekSchedule(emptyMap()), heatOnDemand = null,
        ),
    )

    @Test fun starterRoutineIsCreatedOnceAndNeverReturnsAfterDeletion() = runTest {
        repository.seedDefaultsIfNeeded(zones)
        repository.seedDefaultsIfNeeded(zones)
        val seeded = repository.observeRoutines().first().single()
        assertEquals("Morning boost", seeded.name)
        repository.delete(seeded.id)
        repository.seedDefaultsIfNeeded(zones)
        assertEquals(emptyList(), repository.observeRoutines().first())
    }

    @Test fun noStarterWithoutRoomsOrWhenRoutinesAlreadyExist() = runTest {
        repository.seedDefaultsIfNeeded(emptyList())
        assertEquals(false, prefs.seeded)
        repository.save(Routine(name = "Mine", steps = listOf(RoutineStep.CancelBoost(listOf("thermo")))))
        repository.seedDefaultsIfNeeded(zones)
        assertEquals(listOf("Mine"), repository.observeRoutines().first().map { it.name })
        assertEquals(true, prefs.seeded)
    }

    @Test fun newRoutinesGoLastAndReorderIsSaved() = runTest {
        val a = repository.save(Routine(name = "A", steps = emptyList()))
        val b = repository.save(Routine(name = "B", steps = emptyList()))
        assertEquals(listOf("A", "B"), repository.observeRoutines().first().map { it.name })
        repository.reorder(listOf(b, a))
        assertEquals(listOf("B", "A"), repository.observeRoutines().first().map { it.name })
        repository.save(repository.get(a)!!.copy(name = "A2"))
        assertEquals(listOf("B", "A2"), repository.observeRoutines().first().map { it.name })
    }
}
