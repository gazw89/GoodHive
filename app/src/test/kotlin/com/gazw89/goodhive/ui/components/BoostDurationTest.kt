package com.gazw89.goodhive.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

class BoostDurationTest {
    @Test
    fun durationsSpanHiveAppRangeInQuarterHours() {
        assertEquals(15, boostDurationsMinutes.first())
        assertEquals(360, boostDurationsMinutes.last())
        assertEquals(24, boostDurationsMinutes.size)
        assert(BOOST_DEFAULT_MINUTES in boostDurationsMinutes)
    }

    @Test
    fun formatsDurations() {
        assertEquals("15m", formatBoostDuration(15))
        assertEquals("1h", formatBoostDuration(60))
        assertEquals("2h 15m", formatBoostDuration(135))
        assertEquals("6h", formatBoostDuration(360))
    }

    @Test
    fun defaultTargetIsThreeDegreesOverMeasuredTemperature() {
        assertEquals(18.0, defaultBoostTargetC(15.0, 21.0, 5.0, 32.0))
        assertEquals(20.0, defaultBoostTargetC(17.0, 21.0, 5.0, 32.0))
        assertEquals(21.5, defaultBoostTargetC(18.5, 21.0, 5.0, 32.0))
        assertEquals(23.0, defaultBoostTargetC(20.0, 21.0, 5.0, 32.0))
    }

    @Test
    fun defaultTargetSnapsToHalfDegreeSteps() {
        assertEquals(20.5, defaultBoostTargetC(17.3, null, 5.0, 32.0))
        assertEquals(20.0, defaultBoostTargetC(16.9, null, 5.0, 32.0))
    }

    @Test
    fun defaultTargetRespectsDeviceLimits() {
        assertEquals(32.0, defaultBoostTargetC(31.0, 20.0, 5.0, 32.0))
        assertEquals(32.0, defaultBoostTargetC(32.0, 20.0, 5.0, 32.0))
        assertEquals(7.0, defaultBoostTargetC(1.0, 20.0, 7.0, 32.0))
    }

    @Test
    fun defaultTargetFallsBackToTargetWhenReadingUnusable() {
        assertEquals(21.0, defaultBoostTargetC(null, 19.0, 5.0, 32.0))
        assertEquals(20.0, defaultBoostTargetC(null, null, 5.0, 32.0))
        assertEquals(21.0, defaultBoostTargetC(Double.NaN, 19.0, 5.0, 32.0))
        assertEquals(21.0, defaultBoostTargetC(85.0, 19.0, 5.0, 32.0))
        assertEquals(32.0, defaultBoostTargetC(null, 31.5, 5.0, 32.0))
    }

    @Test
    fun estimatesTotalFromMinutesLeft() {
        assertEquals(30, estimatedBoostTotalMinutes(12))
        assertEquals(30, estimatedBoostTotalMinutes(30))
        assertEquals(60, estimatedBoostTotalMinutes(45))
        assertEquals(240, estimatedBoostTotalMinutes(200))
        assertEquals(360, estimatedBoostTotalMinutes(400))
    }
}
