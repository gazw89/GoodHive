package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.data.db.ZoneStateLogEntity
import org.junit.Test
import kotlin.test.*

class ReportsDataTest {
    private fun state(at: Long, mode: String = "SCHEDULE", override: Boolean = false, boost: Boolean = false) =
        ZoneStateLogEntity(zoneId = "room", epochMs = at, mode = mode, targetC = 20.0,
            scheduleOverride = override, boostActive = boost, hodActive = false, working = true, online = true, battery = 80)

    @Test fun `sparse observations do not claim an entire day of mode coverage`() {
        val day = 24 * 3_600_000L
        val spans = modeIntervalsFromLog(listOf(state(0), state(day)), day + 60_000)
        assertEquals(5 * 60_000L, spans[0].toMs)
        assertEquals(day, spans[1].fromMs)
        assertEquals(day + 60_000, spans[1].toMs)
    }
    @Test fun `five minute heartbeats provide continuous bounded coverage`() {
        val spans = modeIntervalsFromLog(listOf(state(0), state(300_000)), 900_000)
        assertEquals(spans[0].toMs, spans[1].fromMs)
        assertEquals(600_000, spans[1].toMs)
    }
    @Test fun `logged boost and schedule overrides are unscored modes`() {
        val spans = modeIntervalsFromLog(listOf(state(0, override = true), state(300_000, boost = true)), 600_000)
        assertEquals(listOf("MANUAL", "BOOST"), spans.map { it.mode })
    }
    @Test fun `sparse boost observation is not stretched overnight`() {
        val spans = boostIntervalsFromLog(listOf(state(0, boost = true)), 24 * 3_600_000L)
        assertEquals(300_000, spans.single().toMs)
    }
}
