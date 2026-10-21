package com.gazw89.goodhive.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HiveWriteLogTest {
    @Test
    fun onlyBeekeeperPostsAreLogged() {
        assertTrue(shouldLogHiveWrite("POST", "beekeeper.hivehome.com"))
        assertTrue(shouldLogHiveWrite("post", "Beekeeper.HiveHome.com"))
        assertFalse(shouldLogHiveWrite("GET", "beekeeper.hivehome.com"))
        assertFalse(shouldLogHiveWrite("POST", "cognito-idp.eu-west-1.amazonaws.com"))
        assertFalse(shouldLogHiveWrite("POST", "sso.hivehome.com"))
    }

    @Test
    fun pathDropsHostAndApiPrefix() {
        assertEquals("nodes/trvcontrol/abc", hiveRequestPath("/1.0/nodes/trvcontrol/abc", null))
        assertEquals("nodes/heating/abc?x=1", hiveRequestPath("/1.0/nodes/heating/abc", "x=1"))
        assertEquals("abc", zoneIdFromHivePath("nodes/trvcontrol/abc"))
    }

    @Test
    fun ordinaryWriteBodyIsKept() {
        val body = """{"mode":"MANUAL","target":21}"""
        assertEquals(body, sanitizeHiveBody(body))
    }

    @Test
    fun authorizationAndTokensAreRemoved() {
        val header = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature"
        assertFalse(sanitizeHiveBody(header).contains("eyJ"))
        assertFalse(sanitizeHiveBody(header).contains("Bearer"))

        val json = """{"token":"eyJhbGciOiJIUzI1NiJ9.payload.signature","mode":"OFF"}"""
        val sanitised = sanitizeHiveBody(json)
        assertFalse(sanitised.contains("eyJ"))
        assertTrue(sanitised.contains("[redacted]"))
        assertTrue(sanitised.contains("OFF"))

        val address = """{"address":"1 High Street","postcode":"SW1A 1AA","target":19}"""
        val redactedAddress = sanitizeHiveBody(address)
        assertFalse(redactedAddress.contains("High Street"))
        assertFalse(redactedAddress.contains("SW1A"))
        assertTrue(redactedAddress.contains("19"))
    }

    @Test
    fun longBodyIsTruncated() {
        val body = "x".repeat(HIVE_WRITE_BODY_CAP + 20)
        val sanitised = sanitizeHiveBody(body)
        assertTrue(sanitised.endsWith("\n… truncated"))
        assertEquals(HIVE_WRITE_BODY_CAP + "\n… truncated".length, sanitised.length)
    }

    @Test
    fun changeKindFollowsPriority() {
        assertEquals(HiveWriteKind.Temperature, classifyHiveWrite("""{"target":21.5}"""))
        assertEquals(HiveWriteKind.Mode, classifyHiveWrite("""{"mode":"MANUAL","target":21}"""))
        assertEquals(HiveWriteKind.Mode, classifyHiveWrite("""{"mode":"OFF","target":7}"""))
        assertEquals(HiveWriteKind.Boost, classifyHiveWrite("""{"mode":"BOOST","boost":30,"target":22}"""))
        assertEquals(HiveWriteKind.Schedule, classifyHiveWrite("""{"schedule":{"monday":[]}}"""))
        assertEquals(HiveWriteKind.HeatOnDemand, classifyHiveWrite("""{"autoBoost":"ENABLED","zone":"z"}"""))
        assertEquals(HiveWriteKind.HeatOnDemand, classifyHiveWrite("""{"zone":""}"""))
        assertEquals("Manual 21°", hiveWriteSummary("""{"mode":"MANUAL","target":21}"""))
        assertEquals("21.5°", hiveWriteSummary("""{"target":21.5}"""))
        assertEquals("Boost 30 min · 22°", hiveWriteSummary("""{"mode":"BOOST","boost":30,"target":22}"""))
        assertEquals("Heat on Demand on", hiveWriteSummary("""{"autoBoost":"ENABLED","zone":"z"}"""))
        assertEquals("Heat on Demand off", hiveWriteSummary("""{"zone":""}"""))
        assertEquals("Schedule", hiveWriteSummary("""{"schedule":{"monday":[]}}"""))
    }

    @Test
    fun filtersMatchResultAndKind() {
        assertTrue(HiveWriteResultFilter.Succeeded.matches(200))
        assertFalse(HiveWriteResultFilter.Succeeded.matches(400))
        assertFalse(HiveWriteResultFilter.Succeeded.matches(null))
        assertTrue(HiveWriteResultFilter.Failed.matches(null))
        assertTrue(HiveWriteResultFilter.Failed.matches(400))
        assertTrue(HiveWriteKindFilter.Mode.matches(HiveWriteKind.Mode))
        assertFalse(HiveWriteKindFilter.Temperature.matches(HiveWriteKind.Mode))
        assertTrue(HiveWriteKindFilter.All.matches(null))
    }

    @Test
    fun listTimeUsesLondonToday() {
        val now = 1_779_000_000_000L
        assertTrue(formatHiveWriteListTime(now, now).startsWith("Today "))
        val detail = formatHiveWriteDetailTime(now)
        assertTrue(detail.contains("2026"))
    }
}
