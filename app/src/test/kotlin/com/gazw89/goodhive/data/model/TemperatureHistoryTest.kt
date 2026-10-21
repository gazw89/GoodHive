package com.gazw89.goodhive.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemperatureHistoryTest {
    @Test
    fun summaryOfEmptyListIsNull() {
        assertNull(emptyList<TemperatureReading>().summary())
    }

    @Test
    fun summaryReportsMinMaxAndLatestByTime() {
        val readings = listOf(
            TemperatureReading(3_000, 19.5),
            TemperatureReading(1_000, 17.0),
            TemperatureReading(2_000, 21.5),
        )
        val s = readings.summary()!!
        assertEquals(17.0, s.minC)
        assertEquals(21.5, s.maxC)
        assertEquals(TemperatureReading(3_000, 19.5), s.latest)
    }

    @Test
    fun resolutionAndAggregateWireValues() {
        assertEquals("MINUTES" to 5, HistoryResolution.FIVE_MINUTES.let { it.timeUnit to it.rate })
        assertEquals("HOURS" to 1, HistoryResolution.HOURLY.let { it.timeUnit to it.rate })
        assertEquals("DAYS" to 1, HistoryResolution.DAILY.let { it.timeUnit to it.rate })
        assertEquals("AVG", HistoryAggregate.AVG.wire)
        assertEquals("MAX", HistoryAggregate.MAX.wire)
        assertEquals("MIN", HistoryAggregate.MIN.wire)
    }

    @Test
    fun mergeAveragesPerFiveMinuteBucketAndSorts() {
        val bucket = READING_BUCKET_MS
        val a = listOf(TemperatureReading(bucket * 2, 18.0), TemperatureReading(bucket, 17.0))
        val b = listOf(TemperatureReading(bucket * 2 + 40_000, 20.0), TemperatureReading(bucket * 3 + 1_000, 22.0))
        assertEquals(
            listOf(
                TemperatureReading(bucket, 17.0),
                TemperatureReading(bucket * 2, 19.0),
                TemperatureReading(bucket * 3, 22.0),
            ),
            mergeSourceReadings(listOf(a, b)),
        )
    }

    @Test
    fun mergeOfOneOrNoSourcesIsPassThrough() {
        val a = listOf(TemperatureReading(1_234, 18.0), TemperatureReading(100, 17.0))
        assertEquals(a.sortedBy { it.epochMillis }, mergeSourceReadings(listOf(a, emptyList())))
        assertEquals(emptyList(), mergeSourceReadings(emptyList()))
    }
}
