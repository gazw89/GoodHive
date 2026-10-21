package com.gazw89.goodhive.ui.reports

import com.gazw89.goodhive.util.GoodHiveTime
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

enum class ReportPeriodKind(val label: String, val noun: String) {
    WEEK("Week", "week"),
    MONTH("Month", "month"),
    YEAR("Year", "year"),
}

/**
 * A reporting window and the window it is compared with, all as London calendar dates (end
 * exclusive). [offset] is how many periods back from now (0 is the current one).
 *
 * - Week: Monday to Sunday. The current week ends at tomorrow’s London midnight (today is partial).
 * - Month: a calendar month. Year: a calendar year.
 *
 * The comparison window ([prevFrom], [prevToExclusive]) always covers the same number of elapsed days
 * as the current window so a half-finished month is compared with the first half of the last one.
 * [lastYearFrom] / [lastYearToExclusive] is the current window shifted back one year.
 */
data class PeriodWindow(
    val kind: ReportPeriodKind,
    val offset: Int,
    val from: LocalDate,
    val toExclusive: LocalDate,
    val prevFrom: LocalDate,
    val prevToExclusive: LocalDate,
    val lastYearFrom: LocalDate,
    val lastYearToExclusive: LocalDate,
    val label: String,
    val comparisonLabel: String,
    /** True when the window has not finished (it contains today). */
    val isOpen: Boolean,
) {
    val canGoNext: Boolean get() = offset > 0
    val fromMs: Long get() = ms(from)
    val toMs: Long get() = ms(toExclusive)
    val prevFromMs: Long get() = ms(prevFrom)
    val prevToMs: Long get() = ms(prevToExclusive)
    val lastYearFromMs: Long get() = ms(lastYearFrom)
    val lastYearToMs: Long get() = ms(lastYearToExclusive)

    val dayCount: Int get() = (toExclusive.toEpochDay() - from.toEpochDay()).toInt()

    private fun ms(d: LocalDate) = GoodHiveTime.londonStartOfDay(d).toEpochMilli()
}

fun periodWindow(kind: ReportPeriodKind, offset: Int, today: LocalDate): PeriodWindow {
    require(offset >= 0)
    return when (kind) {
        ReportPeriodKind.WEEK -> {
            val from = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                .minusWeeks(offset.toLong())
            val to = minOf(from.plusWeeks(1), today.plusDays(1))
            val end = to.minusDays(1)
            PeriodWindow(
                kind, offset, from, to,
                prevFrom = from.minusDays(7), prevToExclusive = to.minusDays(7),
                lastYearFrom = from.minusYears(1), lastYearToExclusive = to.minusYears(1),
                label = rangeLabel(from, end),
                comparisonLabel = "previous week",
                isOpen = offset == 0,
            )
        }
        ReportPeriodKind.MONTH -> {
            val from = today.withDayOfMonth(1).minusMonths(offset.toLong())
            val toFull = from.plusMonths(1)
            val to = minOf(toFull, today.plusDays(1))
            val prevFrom = from.minusMonths(1)
            val prevTo = minOf(prevFrom.plusDays((to.toEpochDay() - from.toEpochDay())), from)
            PeriodWindow(
                kind, offset, from, to, prevFrom, prevTo,
                lastYearFrom = from.minusYears(1), lastYearToExclusive = to.minusYears(1),
                label = "${monthName(from)} ${from.year}",
                comparisonLabel = monthName(prevFrom),
                isOpen = offset == 0,
            )
        }
        ReportPeriodKind.YEAR -> {
            val from = LocalDate.of(today.year - offset, 1, 1)
            val toFull = from.plusYears(1)
            val to = minOf(toFull, today.plusDays(1))
            val prevFrom = from.minusYears(1)
            val prevTo = to.minusYears(1)
            PeriodWindow(
                kind, offset, from, to, prevFrom, prevTo,
                lastYearFrom = prevFrom, lastYearToExclusive = prevTo,
                label = from.year.toString(),
                comparisonLabel = prevFrom.year.toString(),
                isOpen = offset == 0,
            )
        }
    }
}

private fun monthName(d: LocalDate) = d.month.getDisplayName(TextStyle.FULL, Locale.UK)

private fun shortMonth(d: LocalDate) = d.month.getDisplayName(TextStyle.SHORT, Locale.UK)

/** "9 – 15 Oct", or "28 Sep – 4 Oct" across a month boundary (en dash, spaced). */
fun rangeLabel(first: LocalDate, last: LocalDate): String =
    if (first.month == last.month && first.year == last.year) {
        "${first.dayOfMonth} – ${last.dayOfMonth} ${shortMonth(last)}"
    } else {
        "${first.dayOfMonth} ${shortMonth(first)} – ${last.dayOfMonth} ${shortMonth(last)}"
    }
