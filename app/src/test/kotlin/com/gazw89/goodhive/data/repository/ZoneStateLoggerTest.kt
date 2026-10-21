package com.gazw89.goodhive.data.repository

import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.WeekSchedule
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneStateLoggerTest : RepositoryTestBase() {
    // Thursday 2026-10-15 12:00 London (BST, 11:00 UTC) so the Thursday slot below is current.
    private val t0 = Instant.parse("2026-10-15T11:00:00Z").toEpochMilli()
    private val schedule = WeekSchedule(mapOf("thursday" to listOf(ScheduleSlot(0, 17.0), ScheduleSlot(7 * 60, 20.0))))

    private fun zone(
        mode: String = HeatingMode.SCHEDULE,
        target: Double = 20.0,
        current: Double = 19.0,
        working: Boolean? = false,
        battery: Int? = 80,
        autoBoostActive: Boolean? = null,
        schedule: WeekSchedule = this.schedule,
    ) = HeatingZone(
        id = "z", productType = ProductType.TRV_CONTROL, name = "Room", online = true,
        currentTemperatureC = current, targetTemperatureC = target, mode = mode, frostProtectionC = 7.0,
        maxEventsPerDay = 6, schedule = schedule, heatOnDemand = null, working = working,
        batteryLevel = battery, autoBoostActive = autoBoostActive,
    )

    @Test fun appendsOnlyWhenALoggedValueChanges() = runTest {
        val log = stateLogger
        log.record(listOf(zone()), t0)
        log.record(listOf(zone()), t0 + 60_000)
        log.record(listOf(zone(current = 19.4)), t0 + 120_000) // current temperature is not logged
        assertEquals(1, stateDao.states.size)

        log.record(listOf(zone(working = true)), t0 + 180_000)
        log.record(listOf(zone(working = true, battery = 70)), t0 + 240_000)
        log.record(listOf(zone(working = true, battery = 70)), t0 + 300_000)
        assertEquals(3, stateDao.states.size)
        assertEquals(listOf(t0, t0 + 180_000, t0 + 240_000), stateDao.states.map { it.epochMs })
    }

    @Test fun firstRowAfterRestartMatchesThePersistedRow() = runTest {
        stateLogger.record(listOf(zone()), t0)
        // A new logger (process restart) seeds from Room and sees no change.
        ZoneStateLogger(stateDao, json).record(listOf(zone()), t0 + 60_000)
        assertEquals(1, stateDao.states.size)
    }

    @Test fun derivesScheduleOverrideBoostAndHod() = runTest {
        val log = stateLogger
        log.record(listOf(zone(target = 20.0)), t0)
        log.record(listOf(zone(target = 22.0)), t0 + 1_000)
        log.record(listOf(zone(mode = HeatingMode.BOOST, target = 25.0, autoBoostActive = true)), t0 + 2_000)
        log.record(listOf(zone(mode = HeatingMode.BOOST, target = 25.0, autoBoostActive = false)), t0 + 3_000)
        log.record(listOf(zone(mode = HeatingMode.MANUAL, target = 21.0)), t0 + 4_000)
        val rows = stateDao.states
        assertEquals(false, rows[0].scheduleOverride)
        assertEquals(true, rows[1].scheduleOverride)
        assertTrue(rows[2].hodActive && !rows[2].boostActive)
        assertTrue(!rows[3].hodActive && rows[3].boostActive)
        assertNull(rows[4].scheduleOverride)
    }

    @Test fun scheduleSnapshotOnlyWhenCanonicalScheduleChanges() = runTest {
        val log = stateLogger
        log.record(listOf(zone()), t0)
        log.record(listOf(zone(target = 19.0)), t0 + 1_000)
        assertEquals(1, stateDao.schedules.size)
        val edited = WeekSchedule(schedule.days + ("friday" to listOf(ScheduleSlot(8 * 60, 18.0))))
        log.record(listOf(zone(schedule = edited)), t0 + 2_000)
        assertEquals(2, stateDao.schedules.size)
        assertEquals(t0 + 2_000, stateDao.schedules.last().fromMs)
    }

    @Test fun emptyScheduleIsNotSnapshotted() = runTest {
        stateLogger.record(listOf(zone(schedule = WeekSchedule(emptyMap()))), t0)
        assertTrue(stateDao.schedules.isEmpty())
        assertFalse(stateDao.states.isEmpty())
    }

    @Test fun refreshWritesTheLog() = runTest {
        configureToken()
        repository.refresh(force = true)
        assertEquals(1, stateDao.states.size)
        repository.refresh(force = true)
        assertEquals(1, stateDao.states.size)
    }
    @Test fun unchangedStateGetsObservationCheckpointAfterGap() = runTest {
        stateLogger.record(listOf(zone()), t0)
        stateLogger.record(listOf(zone()), t0 + 6 * 60_000)
        assertEquals(listOf(t0, t0 + 6 * 60_000), stateDao.states.map { it.epochMs })
        assertEquals(1, stateDao.schedules.size)
    }

}
