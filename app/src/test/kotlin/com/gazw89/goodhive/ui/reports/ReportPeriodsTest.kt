package com.gazw89.goodhive.ui.reports

import java.time.LocalDate
import org.junit.Test
import kotlin.test.*

class ReportPeriodsTest {
    @Test fun `week starts Monday with elapsed weekday comparison`() {
        val w = periodWindow(ReportPeriodKind.WEEK, 0, LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 10, 12), w.from)
        assertEquals(4, w.dayCount)
        assertEquals(LocalDate.of(2026, 10, 9), w.prevToExclusive)
        assertFalse(w.canGoNext)
        assertEquals(w.from.minusDays(7), w.prevFrom)
    }
    @Test fun `previous week is the full Monday to Sunday handcheck window`() {
        val w = periodWindow(ReportPeriodKind.WEEK, 1, LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 10, 5), w.from)
        assertEquals(LocalDate.of(2026, 10, 12), w.toExclusive)
        assertEquals(7, w.dayCount)
    }
    @Test fun `open month last year stops on matching elapsed date`() {
        val w = periodWindow(ReportPeriodKind.MONTH, 0, LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 10, 16), w.toExclusive)
        assertEquals(LocalDate.of(2025, 10, 16), w.lastYearToExclusive)
        assertEquals(LocalDate.of(2026, 9, 16), w.prevToExclusive)
    }
    @Test fun `year never compares elapsed months to whole previous year`() {
        val w = periodWindow(ReportPeriodKind.YEAR, 0, LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2025, 10, 16), w.prevToExclusive)
    }
    @Test fun `history remains navigable and honors London DST day length`() {
        val w = periodWindow(ReportPeriodKind.WEEK, 1, LocalDate.of(2026, 11, 1))
        assertTrue(w.canGoNext)
        assertFalse(w.isOpen)
        assertEquals(169L, (w.toMs - w.fromMs) / 3_600_000)
    }
}
