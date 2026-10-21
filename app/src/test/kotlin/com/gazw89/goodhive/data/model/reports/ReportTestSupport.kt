package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.ScheduleSlot
import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule
import java.time.ZoneId
import java.time.ZonedDateTime

internal val London: ZoneId = ZoneId.of("Europe/London")

/** Epoch millis for a London wall-clock time. */
internal fun ms(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
    ZonedDateTime.of(y, mo, d, h, mi, 0, 0, London).toInstant().toEpochMilli()

internal fun oct(d: Int, h: Int = 0, mi: Int = 0) = ms(2026, 10, d, h, mi)

/** Readings every [stepMin] minutes in `[fromMs, toMs)`, temperature from [f]. */
internal fun series(fromMs: Long, toMs: Long, stepMin: Int, f: (Long) -> Double): List<TemperatureReading> =
    generateSequence(fromMs) { it + stepMin * 60_000L }.takeWhile { it < toMs }
        .map { TemperatureReading(it, f(it)) }.toList()

/** Same slots every day of the week; each pair is (startMinutes, target). */
internal fun everyDay(vararg slots: Pair<Int, Double>) =
    WeekSchedule(WeekSchedule.DAY_KEYS.associateWith { slots.map { ScheduleSlot(it.first, it.second) } })

/** 06:30 -> 20, 22:00 -> 16 every day. */
internal val standardSchedule = everyDay(390 to 20.0, 1320 to 16.0)

/** One night of linear cooling from 22:00 on [day] of October 2026 to 05:30. */
internal fun coolingNight(day: Int, ratePerHour: Double, startC: Double = 20.0, stepMin: Int = 15) =
    oct(day, 22).let { start ->
        series(start, oct(day + 1, 5, 31), stepMin) { startC - ratePerHour * (it - start) / 3_600_000.0 }
    }
