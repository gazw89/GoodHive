package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DerivedMetricsTest {
    // ---- Time on target -------------------------------------------------------------------

    /** Tue 13 Oct 2026, 10-minute readings, 19.8 from 08:00 (on target), 18.0 before. */
    private fun day(): List<TemperatureReading> = series(oct(13), oct(14), 10) {
        if (it >= oct(13, 8)) 19.8 else 18.0
    }

    @Test
    fun timeOnTargetCountsOnlySlotsAtOrAbove17() {
        val r = timeOnTarget(day(), standardSchedule)
        // 06:30..21:50 is 93 readings at target 20; the other 51 sit in the 16 degree setback.
        assertEquals(93, r.countedReadings)
        assertEquals(84, r.onTargetReadings)
        assertEquals(51, r.excludedLowTarget)
        assertEquals(84.0 / 93, r.share!!, 1e-9)
        assertEquals(0.0, r.coveredByModeLog, 1e-9)
        assertTrue(r.usedScheduleFallback)
    }

    @Test
    fun timeOnTargetExcludesNonScheduleModeAndReportsCoverage() {
        val modes = listOf(
            ModeInterval(oct(13, 6, 30), oct(13, 12), "SCHEDULE"),
            ModeInterval(oct(13, 12), oct(13, 16), "OFF"),
        )
        val r = timeOnTarget(day(), standardSchedule, modeIntervals = modes)
        assertEquals(24, r.excludedNotOnSchedule)
        assertEquals(69, r.countedReadings)
        assertEquals(60, r.onTargetReadings)
        assertEquals(33.0 / 69, r.coveredByModeLog, 1e-9)
    }

    @Test
    fun timeOnTargetUsesSnapshotsAndDropsFallbackFlag() {
        val lower = everyDay(0 to 18.0)
        val snap = listOf(ScheduleAt(oct(13), lower))
        val r = timeOnTarget(day(), standardSchedule, scheduleHistory = snap)
        assertFalse(r.usedScheduleFallback)
        // Target 18 all day: 18.0 readings are on target, 19.8 are not.
        assertEquals(48, r.onTargetReadings)
        assertEquals(144, r.countedReadings)
        // A snapshot that starts after the readings means the current schedule is a fallback.
        val late = listOf(ScheduleAt(oct(14), lower))
        assertTrue(timeOnTarget(day(), standardSchedule, scheduleHistory = late).usedScheduleFallback)
    }

    @Test
    fun timeOnTargetToleranceIsInclusive() {
        val sched = everyDay(0 to 20.0)
        val rs = listOf(TemperatureReading(oct(13, 9), 19.5), TemperatureReading(oct(13, 10), 19.49), TemperatureReading(oct(13, 11), 20.5))
        val r = timeOnTarget(rs, sched)
        assertEquals(2, r.onTargetReadings)
        assertEquals(3, r.countedReadings)
    }

    @Test
    fun timeOnTargetEmpty() {
        val r = timeOnTarget(emptyList(), standardSchedule)
        assertNull(r.share)
        assertEquals(0, r.countedReadings)
    }

    // ---- Warm-ups -------------------------------------------------------------------------

    private val morning = everyDay(360 to 19.0, 1320 to 16.0)

    /** 5-minute readings 05:30-08:00 on [d] Oct: 17.5 until 06:00, then a ramp reaching 18.7 after [m] minutes. */
    private fun ramp(d: Int, m: Int): List<TemperatureReading> =
        series(oct(d, 5, 30), oct(d, 8), 5) {
            if (it <= oct(d, 6)) 17.5 else minOf(19.0, 17.5 + 1.2 * (it - oct(d, 6)) / (m * 60_000.0))
        }

    @Test
    fun warmUpMinutesToTargetMinusPoint3() {
        val s = warmUps(ramp(13, 42), morning).single()
        assertEquals(360, s.slotStartMinutes)
        assertEquals(19.0, s.targetC, 1e-9)
        assertEquals(17.5, s.startTempC, 1e-9)
        assertEquals(45.0, s.minutes!!, 1e-9) // first 5-min reading at or above 18.7
        assertTrue(s.reached)
    }

    @Test
    fun warmUpNotReached() {
        val flat = series(oct(13, 5, 30), oct(13, 8), 5) { 17.5 }
        val s = warmUps(flat, morning).single()
        assertNull(s.minutes)
        assertFalse(s.reached)
        val summary = summariseWarmUps(listOf(s))
        assertEquals(1, summary.notReachedCount)
        assertNull(summary.medianMinutes)
    }

    @Test
    fun warmUpAcrossAutumnChangeUsesWallClockSlot() {
        // Sun 25 Oct 06:00 GMT, the morning after the clocks went back.
        val s = warmUps(ramp(25, 40), morning).single()
        assertEquals(oct(25, 6), s.slotStartMs)
        assertEquals(40.0, s.minutes!!, 1e-9)
    }

    @Test
    fun smallRisesAndAlreadyWarmRoomsAreIgnored() {
        val sched = everyDay(300 to 18.4, 360 to 19.0, 1320 to 16.0)
        val cold = series(oct(13), oct(14), 10) { 17.0 }
        val samples = warmUps(cold, sched)
        // 05:00 rises 2.4 from the 16 degree night; 06:00 rises only 0.6 so is skipped.
        assertEquals(listOf(300), samples.map { it.slotStartMinutes })
        val warm = series(oct(13), oct(14), 10) { 18.8 }
        assertTrue(warmUps(warm, morning).isEmpty()) // within 0.5 of target at slot start
    }

    @Test
    fun staleReadingBeforeSlotStartIsNotUsed() {
        val rs = listOf(TemperatureReading(oct(13, 5, 10), 17.0)) + series(oct(13, 6, 20), oct(13, 8), 5) { 19.0 }
        assertTrue(warmUps(rs, morning).isEmpty())
    }

    @Test
    fun warmUpsExcludedWhenNotOnSchedule() {
        val off = listOf(ModeInterval(oct(13), oct(14), "OFF"))
        assertTrue(warmUps(ramp(13, 42), morning, modeIntervals = off).isEmpty())
        // Switching to manual halfway through the ramp also drops the sample.
        val manual = listOf(
            ModeInterval(oct(13), oct(13, 6, 20), "SCHEDULE"),
            ModeInterval(oct(13, 6, 20), oct(14), "MANUAL"),
        )
        assertTrue(warmUps(ramp(13, 42), morning, modeIntervals = manual).isEmpty())
        val onSchedule = listOf(ModeInterval(oct(13), oct(14), "SCHEDULE"))
        assertEquals(1, warmUps(ramp(13, 42), morning, modeIntervals = onSchedule).size)
    }

    @Test
    fun warmUpSummaryMedianAndIqr() {
        val samples = (1..9).map {
            WarmUpSample(java.time.LocalDate.of(2026, 10, it), 0, 360, 19.0, 17.0, it * 10.0)
        }
        val s = summariseWarmUps(samples)
        assertEquals(50.0, s.medianMinutes!!, 1e-9)
        assertEquals(40.0, s.iqrMinutes!!, 1e-9)
    }

    // ---- Cool-down ------------------------------------------------------------------------

    @Test
    fun coolDownSlopeIsLeastSquares() {
        val rs = (1..6).flatMap { coolingNight(it, 0.4) }
        val nights = coolDownNights(rs, standardSchedule)
        // Night of the 6th is cut off after 05:30 on the 7th, which is still in the data.
        assertEquals(6, nights.size)
        nights.forEach {
            assertEquals(0.4, it.coolingRateCPerHour, 1e-6)
            assertTrue(it.spanHours >= 7.4)
        }
        val summary = summariseCoolDown(nights)
        assertEquals(0.4, summary.medianRateCPerHour!!, 1e-6)
        assertEquals(0.0, summary.iqrRateCPerHour!!, 1e-6)
    }

    @Test
    fun coolDownMedianAndIqrAcrossNights() {
        val rates = listOf(0.2, 0.3, 0.4, 0.5, 0.6)
        val rs = rates.mapIndexed { i, r -> coolingNight(i + 1, r) }.flatten()
        val s = summariseCoolDown(coolDownNights(rs, standardSchedule))
        assertEquals(0.4, s.medianRateCPerHour!!, 1e-6)
        assertEquals(0.2, s.iqrRateCPerHour!!, 1e-6)
    }

    @Test
    fun coolDownSkipsBoostAndNonScheduleNights() {
        val rs = (1..5).flatMap { coolingNight(it, 0.4) }
        val boost = listOf(TimeInterval(oct(3, 23), oct(3, 23, 30)))
        assertEquals(setOf(1, 2, 4, 5), coolDownNights(rs, standardSchedule, boostIntervals = boost).map { it.nightOf.dayOfMonth }.toSet())
        val manual = listOf(ModeInterval(oct(2, 22), oct(3, 5), "MANUAL"))
        assertEquals(4, coolDownNights(rs, standardSchedule, modeIntervals = manual).size)
    }

    @Test
    fun coolDownNeedsFourHoursAndSixReadings() {
        val short = series(oct(1, 22), oct(2, 1, 30), 15) { 20.0 - 0.4 * (it - oct(1, 22)) / 3_600_000.0 } // 3.25h
        assertTrue(coolDownNights(short, standardSchedule).isEmpty())
        val sparse = series(oct(1, 22), oct(2, 5, 31), 120) { 20.0 - 0.4 * (it - oct(1, 22)) / 3_600_000.0 } // 4 readings
        assertEquals(4, sparse.size)
        assertTrue(coolDownNights(sparse, standardSchedule).isEmpty())
        assertTrue(coolDownNights(emptyList(), standardSchedule).isEmpty())
    }

    @Test
    fun coolDownWorksOnTheLongAutumnNight() {
        // 22:00 BST on the 24th to 05:30 GMT on the 25th is 8.5 real hours.
        val rs = coolingNight(24, 0.5)
        val n = coolDownNights(rs, standardSchedule).single()
        assertEquals(0.5, n.coolingRateCPerHour, 1e-6)
        assertEquals(8.5, n.spanHours, 1e-9)
    }

    @Test
    fun coolDownNeedsAnEveningDrop() {
        val flat = everyDay(390 to 20.0, 1320 to 19.5)
        val rs = coolingNight(1, 0.4)
        assertTrue(coolDownNights(rs, flat).isEmpty())
    }
}
