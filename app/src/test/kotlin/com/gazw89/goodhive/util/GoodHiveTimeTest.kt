package com.gazw89.goodhive.util

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class GoodHiveTimeTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun startOfDayInWinterIsUtcMidnight() {
        assertEquals(Instant.parse("2026-01-15T00:00:00Z"), GoodHiveTime.londonStartOfDay(LocalDate.of(2026, 1, 15)))
    }

    @Test
    fun startOfDayInSummerIsPreviousEvening() {
        assertEquals(Instant.parse("2026-07-14T23:00:00Z"), GoodHiveTime.londonStartOfDay(LocalDate.of(2026, 7, 15)))
    }

    @Test
    fun springForwardDayHas1380Minutes() {
        val d = LocalDate.of(2026, 3, 29)
        // Day starts 00:00 GMT; next midnight is 23:00Z on the 29th (00:00 BST on the 30th).
        assertEquals(0, GoodHiveTime.minutesSinceLondonMidnight(ms("2026-03-29T00:00:00Z"), d))
        assertEquals(1379, GoodHiveTime.minutesSinceLondonMidnight(ms("2026-03-29T22:59:00Z"), d))
        assertEquals(1380, GoodHiveTime.minutesSinceLondonMidnight(ms("2026-03-29T23:00:00Z"), d))
    }

    @Test
    fun fallBackDayHas1500MinutesAndIsNotClamped() {
        val d = LocalDate.of(2026, 10, 25)
        // Day starts 00:00 BST = 23:00Z on the 24th.
        assertEquals(0, GoodHiveTime.minutesSinceLondonMidnight(ms("2026-10-24T23:00:00Z"), d))
        assertEquals(1499, GoodHiveTime.minutesSinceLondonMidnight(ms("2026-10-25T23:59:00Z"), d))
        assertEquals(1500, GoodHiveTime.minutesSinceLondonMidnight(ms("2026-10-26T00:00:00Z"), d))
    }

    @Test
    fun instantsBeforeTheDayAreNegative() {
        val d = LocalDate.of(2026, 1, 15)
        assertEquals(-1, GoodHiveTime.minutesSinceLondonMidnight(ms("2026-01-14T23:59:30Z"), d))
    }
}
