package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InsightRulesTest {
    private fun evaluateExperimental(
        rooms: List<RoomReportInput>, period: ReportPeriod, config: InsightConfig = InsightConfig(),
    ) = InsightRules.evaluate(rooms, period, config.copy(calibratedDerivedInsightsEnabled = true))

    @Test fun uncalibratedDerivedRecommendationsAreDisabledByDefault() {
        val room = rampRoom("a", minutes = List(10) { 60 })
        val types = InsightRules.evaluate(listOf(room), period).map { it.type }
        assertTrue(InsightType.SLOW_WARM_UP !in types)
        assertTrue(InsightType.LOW_TIME_ON_TARGET !in types)
        assertTrue(InsightType.VALVE_COOLS_FASTER !in types)
    }

    private val period = ReportPeriod(oct(1), oct(11), "Week")
    private val morning = everyDay(360 to 19.0, 1320 to 16.0)

    private fun ramp(d: Int, m: Int): List<TemperatureReading> =
        series(oct(d, 5, 30), oct(d, 8), 5) {
            if (it <= oct(d, 6)) 17.5 else minOf(19.0, 17.5 + 1.2 * (it - oct(d, 6)) / (m * 60_000.0))
        }

    private fun rampRoom(id: String, mode: String? = "SCHEDULE", minutes: List<Int>) = RoomReportInput(
        zoneId = id, zoneName = "Room $id", isValve = false, currentMode = mode,
        readings = minutes.mapIndexed { i, m -> ramp(i + 1, m) }.flatten(), schedule = morning,
    )

    private val slowMinutes = listOf(40, 42, 44, 40, 45, 41, 43, 40, 42, 44)

    private fun of(list: List<Insight>, type: InsightType) = list.filter { it.type == type }

    @Test
    fun slowWarmUpSuggestsEarlierStart() {
        val ins = of(evaluateExperimental(listOf(rampRoom("a", minutes = slowMinutes)), period), InsightType.SLOW_WARM_UP).single()
        assertEquals(10.0, ins.numbers.getValue("samples"))
        assertEquals(45.0, ins.numbers.getValue("medianMinutes"), 1e-9)
        assertEquals(360.0, ins.numbers.getValue("slotStartMinutes"))
        assertEquals(45.0, ins.numbers.getValue("leadMinutes"))
        assertEquals(315.0, ins.numbers.getValue("suggestedStartMinutes"))
        val text = templateText(ins)
        assertTrue("about 45 minutes" in text, text)
        assertTrue("05:15" in text, text)
        assertTrue("06:00" in text, text)
    }

    @Test
    fun slowWarmUpNeedsSevenSamplesAndStableSpread() {
        val few = rampRoom("a", minutes = slowMinutes.take(6))
        assertTrue(of(evaluateExperimental(listOf(few), period), InsightType.SLOW_WARM_UP).isEmpty())
        val fast = rampRoom("a", minutes = List(10) { 25 })
        assertTrue(of(evaluateExperimental(listOf(fast), period), InsightType.SLOW_WARM_UP).isEmpty())
        val erratic = rampRoom("a", minutes = listOf(35, 35, 35, 35, 90, 90, 90, 90, 35, 90))
        assertTrue(of(evaluateExperimental(listOf(erratic), period), InsightType.SLOW_WARM_UP).isEmpty())
    }

    @Test
    fun roomsInOffModeGetNoWarmUpOrTimeOnTargetInsights() {
        val flat = RoomReportInput(
            "f", "Flat", false, "SCHEDULE",
            series(oct(1), oct(11), 10) { 17.0 }, morning,
        )
        val rooms = listOf(rampRoom("a", minutes = slowMinutes), flat)
        val on = evaluateExperimental(rooms, period)
        assertEquals(1, of(on, InsightType.SLOW_WARM_UP).size)
        assertEquals(1, of(on, InsightType.LOW_TIME_ON_TARGET).size)
        val off = evaluateExperimental(rooms.map { it.copy(currentMode = "OFF") }, period)
        assertTrue(of(off, InsightType.SLOW_WARM_UP).isEmpty())
        assertTrue(of(off, InsightType.LOW_TIME_ON_TARGET).isEmpty())
    }

    @Test
    fun lowTimeOnTargetIsMarkedEstimateWithoutModeLog() {
        val flat = RoomReportInput("f", "Flat", false, "SCHEDULE", series(oct(1), oct(11), 10) { 17.0 }, morning)
        val ins = of(evaluateExperimental(listOf(flat), period), InsightType.LOW_TIME_ON_TARGET).single()
        assertEquals(0.0, ins.numbers.getValue("sharePercent"), 1e-9)
        assertTrue("an estimate" in templateText(ins))
    }

    private fun nightsRoom(id: String, name: String, rate: Double, isValve: Boolean, nights: Int = 7) = RoomReportInput(
        zoneId = id, zoneName = name, isValve = isValve, currentMode = "SCHEDULE",
        readings = (1..nights).flatMap { coolingNight(it, rate) }, schedule = standardSchedule,
    )

    @Test
    fun coldNightsNeedThree() {
        val three = evaluateExperimental(listOf(nightsRoom("a", "A", 1.0, false, nights = 3)), period)
        val ins = of(three, InsightType.COLD_NIGHTS).single()
        assertEquals(3.0, ins.numbers.getValue("nights"))
        assertEquals(12.5, ins.numbers.getValue("coldestC"), 1e-9)
        assertEquals(InsightSeverity.NOTICE, ins.severity)
        assertTrue("dropped below 16°C on 3 of 3 nights" in templateText(ins), templateText(ins))
        val two = evaluateExperimental(listOf(nightsRoom("a", "A", 1.0, false, nights = 2)), period)
        assertTrue(of(two, InsightType.COLD_NIGHTS).isEmpty())
        val warm = evaluateExperimental(listOf(nightsRoom("a", "A", 0.2, false)), period)
        assertTrue(of(warm, InsightType.COLD_NIGHTS).isEmpty())
    }

    @Test
    fun valveCoolingFasterThanOtherValves() {
        val rooms = listOf(
            nightsRoom("v1", "Fast valve", 1.0, true),
            nightsRoom("v2", "Valve 2", 0.4, true),
            nightsRoom("v3", "Valve 3", 0.45, true),
            nightsRoom("v4", "Valve 4", 0.35, true),
            nightsRoom("t", "Thermostat", 1.3, false), // faster still, but a different kind of sensor
        )
        val ins = of(evaluateExperimental(rooms, period), InsightType.VALVE_COOLS_FASTER).single()
        assertEquals("v1", ins.zoneId)
        assertEquals(2.5, ins.numbers.getValue("ratio"), 1e-6)
        assertEquals(3.0, ins.numbers.getValue("otherValves"))
        val text = templateText(ins)
        assertTrue("faster than your other valves" in text, text)
    }

    @Test
    fun thermostatIsNeverTheReferenceForAValve() {
        val rooms = listOf(
            nightsRoom("v1", "Valve 1", 1.0, true),
            nightsRoom("v2", "Valve 2", 0.2, false), // thermostat, slow
            nightsRoom("v3", "Valve 3", 0.2, false),
            nightsRoom("v4", "Valve 4", 0.2, false),
        )
        assertTrue(of(evaluateExperimental(rooms, period), InsightType.VALVE_COOLS_FASTER).isEmpty())
    }

    @Test
    fun valveNeedsEnoughNightsAndEnoughOtherValves() {
        val twoValves = listOf(nightsRoom("v1", "V1", 1.0, true), nightsRoom("v2", "V2", 0.3, true))
        assertTrue(of(evaluateExperimental(twoValves, period), InsightType.VALVE_COOLS_FASTER).isEmpty())
        val fewNights = listOf(
            nightsRoom("v1", "V1", 1.0, true, nights = 4),
            nightsRoom("v2", "V2", 0.3, true), nightsRoom("v3", "V3", 0.3, true), nightsRoom("v4", "V4", 0.3, true),
        )
        assertTrue(of(evaluateExperimental(fewNights, period), InsightType.VALVE_COOLS_FASTER).isEmpty())
        val notFastEnough = listOf(
            nightsRoom("v1", "V1", 0.55, true),
            nightsRoom("v2", "V2", 0.4, true), nightsRoom("v3", "V3", 0.4, true), nightsRoom("v4", "V4", 0.4, true),
        )
        assertTrue(of(evaluateExperimental(notFastEnough, period), InsightType.VALVE_COOLS_FASTER).isEmpty())
    }

    private fun continuous(gapFrom: Long?, gapHours: Int): RoomReportInput {
        val rs = series(oct(1), oct(4), 10) { 19.0 }.filterNot {
            gapFrom != null && it.epochMillis >= gapFrom && it.epochMillis < gapFrom + gapHours * 3_600_000L
        }
        return RoomReportInput("m", "Missing", false, "SCHEDULE", rs, everyDay(0 to 19.0))
    }

    @Test
    fun missingReadingsNeedFourHoursInTotal() {
        val p = ReportPeriod(oct(1), oct(4))
        val five = of(evaluateExperimental(listOf(continuous(oct(2, 10), 5)), p), InsightType.MISSING_READINGS).single()
        assertEquals(5.0 + 10 / 60.0, five.numbers.getValue("totalHours"), 1e-9)
        assertEquals(1.0, five.numbers.getValue("gaps"))
        assertTrue("about 5 hours" in templateText(five), templateText(five))
        assertTrue(of(evaluateExperimental(listOf(continuous(oct(2, 10), 3)), p), InsightType.MISSING_READINGS).isEmpty())
        assertTrue(of(evaluateExperimental(listOf(continuous(null, 0)), p), InsightType.MISSING_READINGS).isEmpty())
    }

    @Test
    fun twoSmallerGapsAddUp() {
        val p = ReportPeriod(oct(1), oct(4))
        val rs = series(oct(1), oct(4), 10) { 19.0 }.filterNot {
            it.epochMillis in oct(1, 8)..oct(1, 10, 30) || it.epochMillis in oct(2, 8)..oct(2, 10, 30)
        }
        val ins = of(
            evaluateExperimental(listOf(RoomReportInput("m", "M", false, "SCHEDULE", rs, everyDay(0 to 19.0))), p),
            InsightType.MISSING_READINGS,
        ).single()
        assertEquals(2.0, ins.numbers.getValue("gaps"))
        assertTrue(ins.numbers.getValue("totalHours") >= 4.0)
    }

    @Test
    fun colderThanLastYear() {
        val p = ReportPeriod(oct(1), oct(8))
        fun room(lastYearC: Double) = RoomReportInput(
            "a", "Lounge", false, "SCHEDULE",
            series(oct(1), oct(8), 30) { 18.0 }, everyDay(0 to 18.0),
            lastYearReadings = series(ms(2025, 10, 1), ms(2025, 10, 8), 30) { lastYearC },
        )
        val ins = of(evaluateExperimental(listOf(room(19.0)), p), InsightType.COLDER_THAN_LAST_YEAR).single()
        assertEquals(-1.0, ins.numbers.getValue("differenceC"), 1e-9)
        assertEquals(7.0, ins.numbers.getValue("days"))
        assertTrue("1.0°C colder than the same period last year" in templateText(ins), templateText(ins))
        assertTrue(of(evaluateExperimental(listOf(room(18.3)), p), InsightType.COLDER_THAN_LAST_YEAR).isEmpty())
        assertTrue(of(evaluateExperimental(listOf(room(18.4)), p), InsightType.COLDER_THAN_LAST_YEAR).size == 1)
        val noHistory = room(19.0).copy(lastYearReadings = null)
        assertTrue(of(evaluateExperimental(listOf(noHistory), p), InsightType.COLDER_THAN_LAST_YEAR).isEmpty())
    }

    @Test
    fun emptyInputGivesNothing() {
        assertTrue(evaluateExperimental(emptyList(), period).isEmpty())
        val room = RoomReportInput("e", "Empty", true, "SCHEDULE", emptyList(), standardSchedule)
        assertTrue(of(evaluateExperimental(listOf(room), period), InsightType.SLOW_WARM_UP).isEmpty())
    }

    @Test
    fun rankingIsBySeverityThenTypeAndDeterministic() {
        val rooms = listOf(nightsRoom("b", "Beta", 1.0, false), nightsRoom("a", "Alpha", 1.0, false))
        val first = evaluateExperimental(rooms, period)
        assertEquals(first, evaluateExperimental(rooms.reversed(), period))
        assertTrue(first.zipWithNext().all { (x, y) -> x.severity.ordinal >= y.severity.ordinal })
        assertEquals(InsightType.COLD_NIGHTS, first[0].type)
        assertEquals("Alpha", first[0].zoneName)
        assertEquals(InsightSeverity.WARNING, first[0].severity)
    }

    // ---- fact sheet and text ----------------------------------------------------------------

    private fun sample(i: Int, type: InsightType = InsightType.SLOW_WARM_UP) = Insight(
        type, "z$i", "Room $i", InsightSeverity.NOTICE,
        mapOf(
            "medianMinutes" to 42.456, "iqrMinutes" to 3.75, "samples" to 10.0, "slotStartMinutes" to 360.0,
            "targetC" to 19.0, "leadMinutes" to 45.0, "suggestedStartMinutes" to 315.0,
        ),
        oct(1), oct(8),
    )

    @Test
    fun templatesAvoidSpeculationAndUseUkEnglish() {
        val all = listOf(
            sample(1),
            Insight(InsightType.COLD_NIGHTS, "z", "Hall", InsightSeverity.NOTICE,
                mapOf("nights" to 4.0, "observedNights" to 7.0, "thresholdC" to 16.0, "coldestC" to 13.24), 0, 1),
            Insight(InsightType.VALVE_COOLS_FASTER, "z", "Hall", InsightSeverity.NOTICE,
                mapOf("ratio" to 1.96, "rateCPerHour" to 0.8, "otherValvesRateCPerHour" to 0.4), 0, 1),
            Insight(InsightType.MISSING_READINGS, "z", "Hall", InsightSeverity.NOTICE,
                mapOf("totalHours" to 6.4, "gaps" to 2.0), 0, 1),
            Insight(InsightType.COLDER_THAN_LAST_YEAR, "z", "Hall", InsightSeverity.INFO,
                mapOf("avgC" to 18.04, "differenceC" to -0.56), 0, 1),
            Insight(InsightType.LOW_TIME_ON_TARGET, "z", "Hall", InsightSeverity.INFO,
                mapOf("sharePercent" to 41.6, "coveredByLogPercent" to 100.0), 0, 1),
        )
        val texts = all.map { templateText(it) }
        texts.forEach {
            assertFalse("draught" in it.lowercase(), it)
            assertTrue(it.endsWith("."), it)
        }
        assertEquals("Hall dropped below 16°C on 4 of 7 nights, down to 13.2°C at the coldest.", texts[1])
        assertEquals(
            "Hall cools overnight about 2.0 times faster than your other valves (about 0.8°C an hour compared with 0.4°C).",
            texts[2],
        )
        assertEquals(
            "Hall has no readings for about 6 hours in total, over 2 gaps. It may be worth checking that it is online and its battery is fine.",
            texts[3],
        )
        assertEquals("Hall averaged about 18.0°C, which is 0.6°C colder than the same period last year.", texts[4])
        assertEquals("Hall spent about 42% of its scheduled heating time within 0.5°C of target.", texts[5])
        assertNotNull(texts[0])
        assertNull(null)
    }
}
