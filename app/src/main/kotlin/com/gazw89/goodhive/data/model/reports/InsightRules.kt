package com.gazw89.goodhive.data.model.reports

import com.gazw89.goodhive.data.model.TemperatureReading
import com.gazw89.goodhive.data.model.WeekSchedule

enum class InsightType {
    COLD_NIGHTS,
    SLOW_WARM_UP,
    VALVE_COOLS_FASTER,
    MISSING_READINGS,
    COLDER_THAN_LAST_YEAR,
    LOW_TIME_ON_TARGET,
}

enum class InsightSeverity { INFO, NOTICE, WARNING }

/**
 * A finding. [numbers] keys (all values Double) by [type]:
 * - SLOW_WARM_UP: slotStartMinutes, targetC, medianMinutes, iqrMinutes, samples, leadMinutes,
 *   suggestedStartMinutes
 * - COLD_NIGHTS: nights, observedNights, thresholdC, coldestC
 * - VALVE_COOLS_FASTER: rateCPerHour, otherValvesRateCPerHour, ratio, nights, otherValves
 * - MISSING_READINGS: totalHours, gaps, longestHours
 * - COLDER_THAN_LAST_YEAR: avgC, lastYearAvgC, differenceC, days, lastYearDays
 * - LOW_TIME_ON_TARGET: sharePercent, readings, coveredByLogPercent (estimate when below 90)
 */
data class Insight(
    val type: InsightType,
    val zoneId: String,
    val zoneName: String,
    val severity: InsightSeverity,
    val numbers: Map<String, Double>,
    val evidenceFromMs: Long,
    val evidenceToMs: Long,
)

/** The reporting window `[fromMs, toMs)`; [label] (e.g. "Week") is only echoed in the fact sheet. */
data class ReportPeriod(val fromMs: Long, val toMs: Long, val label: String = "")

/**
 * Everything the rules need about one room. [isValve] is true for TRVs (productType "trvcontrol")
 * and false for the primary thermostat. [currentMode] is the Hive mode now; "OFF" suppresses
 * warm-up and time-on-target insights. [lastYearReadings] covers the same calendar window a year
 * earlier, or null when unavailable.
 */
data class RoomReportInput(
    val zoneId: String,
    val zoneName: String,
    val isValve: Boolean,
    val currentMode: String?,
    val readings: List<TemperatureReading>,
    val schedule: WeekSchedule,
    val scheduleSnapshots: List<ScheduleAt> = emptyList(),
    val modeIntervals: List<ModeInterval> = emptyList(),
    val boostIntervals: List<TimeInterval> = emptyList(),
    val lastYearReadings: List<TemperatureReading>? = null,
)

/** Thresholds for [InsightRules.evaluate]; defaults follow docs/reports-plan.md section 2. */
data class InsightConfig(
    val coldNightThresholdC: Double = 16.0,
    val minColdNights: Int = 3,
    val minWarmUpSamples: Int = 7,
    val slowWarmUpMedianMinutes: Double = 30.0,
    val maxWarmUpIqrMinutes: Double = 15.0,
    val leadRoundingMinutes: Int = 15,
    val valveRatio: Double = 1.5,
    val minCoolDownNights: Int = 5,
    val minOtherValves: Int = 2,
    val missingTotalHours: Double = 4.0,
    val gapMinHours: Double = 2.0,
    val colderThanLastYearC: Double = 0.4,
    val minComparisonDays: Int = 5,
    val lowTimeOnTargetShare: Double = 0.5,
    val minTimeOnTargetReadings: Int = 100,
    // Experimental derivations are not calibrated against observed heating activity.
    // Near-target share also penalises rooms that are warmer than their target.
    val calibratedDerivedInsightsEnabled: Boolean = false,
)

object InsightRules {
    /**
     * Ranked insights for [period]: severity first (highest first), then type order, then room name
     * and id, so output is deterministic. Only readings inside the period are used.
     *
     * Valve comparisons are valve against valve only, using the median of the other valves'
     * median cooling rates, and need at least [InsightConfig.minOtherValves] other valves with
     * enough nights, so a single odd neighbour cannot define "normal".
     */
    fun evaluate(
        rooms: List<RoomReportInput>,
        period: ReportPeriod,
        config: InsightConfig = InsightConfig(),
    ): List<Insight> {
        val insights = mutableListOf<Insight>()
        val coolRates = mutableMapOf<String, List<CoolDownNight>>()

        for (room in rooms) {
            val rs = room.readings.cleaned().filter { it.epochMillis >= period.fromMs && it.epochMillis < period.toMs }
            val off = room.currentMode.equals("OFF", ignoreCase = true)

            // Cold nights
            val observed = nightMinima(rs)
            val cold = observed.filter { it.minC < config.coldNightThresholdC }
            if (cold.size >= config.minColdNights) {
                insights += Insight(
                    InsightType.COLD_NIGHTS, room.zoneId, room.zoneName,
                    if (cold.size >= 7) InsightSeverity.WARNING else InsightSeverity.NOTICE,
                    mapOf(
                        "nights" to cold.size.toDouble(),
                        "observedNights" to observed.size.toDouble(),
                        "thresholdC" to config.coldNightThresholdC,
                        "coldestC" to cold.minOf { it.minC },
                    ),
                    cold.minOf { it.minAtMs }, cold.maxOf { it.minAtMs },
                )
            }

            // Missing readings
            val gaps = readingGaps(rs, (config.gapMinHours * HOUR_MS).toLong(), period.fromMs, period.toMs)
            val totalH = gaps.sumOf { it.durationMs } / HOUR_MS.toDouble()
            if (gaps.isNotEmpty() && totalH >= config.missingTotalHours) {
                insights += Insight(
                    InsightType.MISSING_READINGS, room.zoneId, room.zoneName,
                    if (totalH >= 24) InsightSeverity.WARNING else InsightSeverity.NOTICE,
                    mapOf(
                        "totalHours" to totalH,
                        "gaps" to gaps.size.toDouble(),
                        "longestHours" to gaps.maxOf { it.durationMs } / HOUR_MS.toDouble(),
                    ),
                    gaps.minOf { it.startMs }, gaps.maxOf { it.endMs },
                )
            }

            // Cool-down (kept for the valve comparison; no insight on its own)
            if (config.calibratedDerivedInsightsEnabled) coolRates[room.zoneId] = coolDownNights(
                rs, room.schedule, room.scheduleSnapshots, room.modeIntervals, room.boostIntervals,
            )

            // Colder than last year
            room.lastYearReadings?.let { last ->
                val cmp = compareWindows(dailyStats(rs, nowMs = null), dailyStats(last))
                val diff = cmp.avgDifferenceC
                if (diff != null && diff <= -config.colderThanLastYearC + EPS &&
                    cmp.current.days >= config.minComparisonDays && cmp.previous.days >= config.minComparisonDays
                ) {
                    insights += Insight(
                        InsightType.COLDER_THAN_LAST_YEAR, room.zoneId, room.zoneName,
                        InsightSeverity.INFO,
                        mapOf(
                            "avgC" to cmp.current.avgC!!,
                            "lastYearAvgC" to cmp.previous.avgC!!,
                            "differenceC" to diff,
                            "days" to cmp.current.days.toDouble(),
                            "lastYearDays" to cmp.previous.days.toDouble(),
                        ),
                        period.fromMs, period.toMs,
                    )
                }
            }

            if (off || !config.calibratedDerivedInsightsEnabled) continue

            // Slow warm-up: worst qualifying slot per room
            val samples = warmUps(rs, room.schedule, room.scheduleSnapshots, room.modeIntervals)
            samples.groupBy { it.slotStartMinutes }.mapNotNull { (slotMin, group) ->
                val s = summariseWarmUps(group)
                val med = s.medianMinutes
                val iqr = s.iqrMinutes
                if (med != null && iqr != null && s.reachedCount >= config.minWarmUpSamples &&
                    med > config.slowWarmUpMedianMinutes && iqr <= config.maxWarmUpIqrMinutes
                ) Triple(slotMin, s, group) else null
            }.maxByOrNull { it.second.medianMinutes!! }?.let { (slotMin, s, group) ->
                val step = config.leadRoundingMinutes
                val lead = (Math.ceil(s.medianMinutes!! / step) * step).toInt()
                insights += Insight(
                    InsightType.SLOW_WARM_UP, room.zoneId, room.zoneName,
                    InsightSeverity.NOTICE,
                    mapOf(
                        "slotStartMinutes" to slotMin.toDouble(),
                        "targetC" to group.first().targetC,
                        "medianMinutes" to s.medianMinutes,
                        "iqrMinutes" to s.iqrMinutes!!,
                        "samples" to s.reachedCount.toDouble(),
                        "leadMinutes" to lead.toDouble(),
                        "suggestedStartMinutes" to Math.floorMod(slotMin - lead, 1440).toDouble(),
                    ),
                    group.minOf { it.slotStartMs }, group.maxOf { it.slotStartMs + 60 * MINUTE_MS },
                )
            }

            // Low time on target (estimate unless the mode log covers it)
            val tot = timeOnTarget(rs, room.schedule, room.scheduleSnapshots, room.modeIntervals)
            val share = tot.share
            if (share != null && tot.countedReadings >= config.minTimeOnTargetReadings &&
                share < config.lowTimeOnTargetShare
            ) {
                insights += Insight(
                    InsightType.LOW_TIME_ON_TARGET, room.zoneId, room.zoneName, InsightSeverity.INFO,
                    mapOf(
                        "sharePercent" to share * 100,
                        "readings" to tot.countedReadings.toDouble(),
                        "coveredByLogPercent" to tot.coveredByModeLog * 100,
                    ),
                    period.fromMs, period.toMs,
                )
            }
        }

        // Valve against valve only.
        val valves = rooms.filter { it.isValve }.mapNotNull { r ->
            val nights = coolRates[r.zoneId].orEmpty()
            if (nights.size >= config.minCoolDownNights) {
                r to summariseCoolDown(nights).medianRateCPerHour!!
            } else null
        }
        for ((room, rate) in valves) {
            val others = valves.filter { it.first.zoneId != room.zoneId }.map { it.second }
            if (others.size < config.minOtherValves) continue
            val ref = median(others)!!
            if (ref > EPS && rate > EPS && rate >= config.valveRatio * ref - EPS) {
                val nights = coolRates[room.zoneId].orEmpty()
                insights += Insight(
                    InsightType.VALVE_COOLS_FASTER, room.zoneId, room.zoneName, InsightSeverity.NOTICE,
                    mapOf(
                        "rateCPerHour" to rate,
                        "otherValvesRateCPerHour" to ref,
                        "ratio" to rate / ref,
                        "nights" to nights.size.toDouble(),
                        "otherValves" to others.size.toDouble(),
                    ),
                    period.fromMs, period.toMs,
                )
            }
        }

        return insights.sortedWith(
            compareByDescending<Insight> { it.severity.ordinal }
                .thenBy { it.type.ordinal }
                .thenBy { it.zoneName }
                .thenBy { it.zoneId },
        )
    }
}
