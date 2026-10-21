package com.gazw89.goodhive.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GoodHive's single timezone contract.
 *
 * Instants are stored and transported in UTC. Calendar dates, schedule decisions, and timestamps
 * shown to the household are always interpreted in Europe/London so GMT/BST changes are automatic.
 */
object GoodHiveTime {
    val londonZone: ZoneId = ZoneId.of("Europe/London")

    private val displayFormatter = DateTimeFormatter
        .ofPattern("d MMM yyyy, HH:mm z", Locale.UK)
        .withZone(londonZone)
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    private val shortMonthFormatter = DateTimeFormatter.ofPattern("MMM", Locale.UK)
    private val csvFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss XXX", Locale.UK)
        .withZone(londonZone)

    fun londonDateNow(): LocalDate = LocalDate.now(londonZone)

    fun londonDateTimeNow(): LocalDateTime = LocalDateTime.now(londonZone)

    /** The instant London's [date] begins (00:00 local; correct across GMT/BST changes). */
    fun londonStartOfDay(date: LocalDate): Instant = date.atStartOfDay(londonZone).toInstant()

    /**
     * Elapsed minutes from London midnight of [date] to [epochMillis] (floored). This is real
     * elapsed time, so it is monotonic for charting but NOT clamped: on a 25h day (autumn
     * change) it can reach 1500, on a 23h day (spring change) 1380, and it is negative for
     * instants before [date] begins. Callers wanting wall-clock minutes should convert via
     * [londonZone] instead.
     */
    fun minutesSinceLondonMidnight(epochMillis: Long, date: LocalDate): Int =
        Math.floorDiv(epochMillis - londonStartOfDay(date).toEpochMilli(), 60_000L).toInt()

    fun formatForDisplay(instant: Instant): String = displayFormatter.format(instant)

    fun formatForCsv(instant: Instant): String = csvFormatter.format(instant)

    fun formatDate(date: LocalDate): String = dateFormatter.format(date)

    fun formatDateRange(start: LocalDate, end: LocalDate): String = when {
        start == end -> formatDate(start)
        start.year == end.year && start.month == end.month ->
            "${start.dayOfMonth}–${end.dayOfMonth} ${shortMonthFormatter.format(end)} ${end.year}"
        start.year == end.year ->
            "${start.dayOfMonth} ${shortMonthFormatter.format(start)} – " +
                "${end.dayOfMonth} ${shortMonthFormatter.format(end)} ${end.year}"
        else -> "${formatDate(start)} – ${formatDate(end)}"
    }

    fun formatForDisplay(isoInstant: String): String = runCatching {
        formatForDisplay(Instant.parse(isoInstant))
    }.getOrDefault(isoInstant)
}
