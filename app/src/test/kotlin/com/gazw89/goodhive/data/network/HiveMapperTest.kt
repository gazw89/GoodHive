package com.gazw89.goodhive.data.network

import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.data.model.ProductType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Anonymised fixture built from the live payload shapes confirmed 2026-10-15 (hive-domain-reference §5). */
class HiveMapperTest {
    private fun physicalDevice(
        id: String = "valve", control: String? = "room", online: Boolean? = null,
        battery: kotlinx.serialization.json.JsonElement = JsonPrimitive(0),
    ) = HiveNode(
        id = id, type = "trv", parent = "hub",
        props = buildJsonObject {
            online?.let { put("online", it) }
            put("battery", battery)
        },
        state = buildJsonObject { control?.let { put("control", it) } },
    )

    private fun roomProduct(online: Boolean? = true, trvs: List<String> = emptyList()) = HiveNode(
        id = "room", type = "trvcontrol",
        props = buildJsonObject {
            online?.let { put("online", it) }
            put("trvs", buildJsonArray { trvs.forEach { add(JsonPrimitive(it)) } })
        },
        state = buildJsonObject { put("name", "Room"); put("mode", "SCHEDULE") },
    )

    @Test fun physicalValveOfflineOverridesOnlineProductAndKeepsZeroBattery() {
        val zone = HiveNodesResponse(listOf(roomProduct()), listOf(physicalDevice(online = false)))
            .toHeatingZones().single()
        assertFalse(zone.online)
        assertEquals(0, zone.batteryLevel)
    }

    @Test fun historySourceIdsUseDeviceIdsForTrvAndProductIdForHeating() {
        val heating = HiveNode(
            id = "thermo", type = "heating",
            state = buildJsonObject { put("name", "Thermostat") },
        )
        val zones = HiveNodesResponse(listOf(heating, roomProduct(trvs = listOf("valve-a", "valve-b"))))
            .toHeatingZones().associateBy { it.id }
        assertEquals(listOf("thermo"), zones.getValue("thermo").historySourceIds)
        assertEquals(listOf("valve-a", "valve-b"), zones.getValue("room").historySourceIds)
    }

    @Test fun historySourceIdsEmptyWhenTrvsMissingOrMalformed() {
        val noTrvs = HiveNode(id = "room", type = "trvcontrol", state = buildJsonObject { put("name", "Room") })
        val badTrvs = HiveNode(
            id = "bad", type = "trvcontrol",
            props = buildJsonObject { put("trvs", JsonPrimitive("not-a-list")) },
            state = buildJsonObject { put("name", "Bad") },
        )
        val zones = HiveNodesResponse(listOf(noTrvs, badTrvs)).toHeatingZones()
        assertEquals(2, zones.size)
        zones.forEach { assertTrue(it.historySourceIds.isEmpty()) }
    }

    @Test fun productOfflineIsNotOverriddenByOnlineDevice() {
        val zone = HiveNodesResponse(listOf(roomProduct(false)), listOf(physicalDevice(online = true)))
            .toHeatingZones().single()
        assertFalse(zone.online)
    }

    @Test fun physicalIdsLinkValveWhenControlMissing() {
        val zone = HiveNodesResponse(
            listOf(roomProduct(trvs = listOf("valve"))),
            listOf(physicalDevice(control = null, online = false)),
        ).toHeatingZones().single()
        assertFalse(zone.online)
        assertEquals(0, zone.batteryLevel)
    }

    @Test fun unrelatedDeviceAndHubDoNotChangeRoomHealth() {
        val zone = HiveNodesResponse(
            listOf(roomProduct()),
            listOf(physicalDevice(control = "other", online = false), physicalDevice(id = "hub", control = null, online = false)),
        ).toHeatingZones().single()
        assertTrue(zone.online)
        assertNull(zone.batteryLevel)
    }

    @Test fun invalidHealthFieldsDoNotDropRoomOrInventEmptyBattery() {
        for (battery in listOf(JsonNull, JsonPrimitive(-1), JsonPrimitive(101), buildJsonObject { put("value", 0) })) {
            val device = physicalDevice(battery = battery).copy(
                props = buildJsonObject { put("battery", battery); put("online", buildJsonObject {}) },
            )
            val zone = HiveNodesResponse(listOf(roomProduct(null)), listOf(device)).toHeatingZones().single()
            assertTrue(zone.online)
            assertNull(zone.batteryLevel)
        }
    }

    @Test fun multipleValvesUseLowestValidBatteryAndAnyOfflineEvidence() {
        val zone = HiveNodesResponse(listOf(roomProduct()), listOf(
            physicalDevice(id = "healthy", online = true, battery = JsonPrimitive(90)),
            physicalDevice(id = "flat", online = false),
        )).toHeatingZones().single()
        assertFalse(zone.online)
        assertEquals(0, zone.batteryLevel)
    }

    private val thermostatId = "thermostat-1"
    private val hodTrvId = "trv-hod"
    private val plainTrvId = "trv-plain"
    private val bareTrvId = "trv-bare"

    private fun trvList(vararg ids: String): JsonArray = buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }

    private val response = HiveNodesResponse(
        products = listOf(
            HiveNode(
                id = thermostatId,
                type = "heating",
                props = buildJsonObject {
                    put("temperature", 20.0)
                    put("working", true)
                    put("zone", "zone-1")
                    put("autoBoost", buildJsonObject {
                        put("active", true)
                        put("duration", 30)
                        put("trvs", trvList(hodTrvId))
                    })
                    put("previous", buildJsonObject {
                        put("mode", "MANUAL")
                        put("target", 18)
                    })
                },
                state = buildJsonObject {
                    put("name", "Thermostat")
                    put("mode", "BOOST")
                    put("target", 25.0)
                    put("boost", 30)
                    put("autoBoost", "ENABLED")
                },
            ),
            HiveNode(
                id = hodTrvId,
                type = "trvcontrol",
                props = buildJsonObject {
                    put("temperature", 19.8)
                    put("working", true)
                    put("autoBoost", buildJsonObject {
                        put("active", true)
                        put("duration", 30)
                        put("trvs", trvList(hodTrvId))
                    })
                },
                state = buildJsonObject {
                    put("name", "Kitchen")
                    put("mode", "SCHEDULE")
                    put("target", 23.0)
                    put("autoBoost", "ENABLED")
                    put("zone", "zone-1")
                },
            ),
            HiveNode(
                id = plainTrvId,
                type = "trvcontrol",
                props = buildJsonObject {
                    put("temperature", 19.8)
                    put("working", true)
                    put("previous", buildJsonObject {})
                },
                state = buildJsonObject {
                    put("name", "Lounge")
                    put("mode", "SCHEDULE")
                    put("target", 21.0)
                },
            ),
            HiveNode(
                id = bareTrvId,
                type = "trvcontrol",
                props = buildJsonObject { put("temperature", 18.0) },
                state = buildJsonObject {
                    put("name", "Spare Room")
                    put("mode", "SCHEDULE")
                    put("target", 16.0)
                },
            ),
        ),
    )

    private val zones: Map<String, HeatingZone> = response.toHeatingZones().associateBy { it.id }

    @Test
    fun thermostatBoostFieldsMap() {
        val t = zones.getValue(thermostatId)
        assertEquals(ProductType.HEATING, t.productType)
        assertEquals(true, t.working)
        assertEquals(true, t.autoBoostActive)
        assertEquals(listOf(hodTrvId), t.autoBoostTrvIds)
        assertEquals("MANUAL", t.previousMode)
        assertEquals(18.0, t.previousTargetC)
        assertEquals(30, t.boostMinutesLeft)
        assertEquals("BOOST", t.mode)
    }

    @Test
    fun hodTrvMapsAutoBoostObjectAndKeepsStringSetting() {
        val trv = zones.getValue(hodTrvId)
        assertEquals(true, trv.working)
        assertEquals(true, trv.autoBoostActive)
        assertEquals(listOf(hodTrvId), trv.autoBoostTrvIds)
        // state.autoBoost string still feeds heatOnDemand, independent of the props object.
        assertEquals("ENABLED", trv.heatOnDemand)
        assertNull(trv.previousMode)
        assertNull(trv.boostMinutesLeft)
    }

    @Test
    fun nonHodTrvKeepsWorkingButHasNoAutoBoost() {
        val trv = zones.getValue(plainTrvId)
        assertEquals(true, trv.working)
        assertNull(trv.autoBoostActive)
        assertTrue(trv.autoBoostTrvIds.isEmpty())
        // props.previous == {} yields nothing, not an error.
        assertNull(trv.previousMode)
        assertNull(trv.previousTargetC)
    }

    @Test
    fun missingFieldsYieldNullOrEmptyWithoutThrowing() {
        val trv = zones.getValue(bareTrvId)
        assertNull(trv.working)
        assertNull(trv.autoBoostActive)
        assertTrue(trv.autoBoostTrvIds.isEmpty())
        assertNull(trv.previousMode)
        assertNull(trv.previousTargetC)
        assertNull(trv.boostMinutesLeft)
        assertFalse(trv.heatOnDemand != null)
    }

    @Test
    fun wrongTypedFieldsAreIgnoredNotThrown() {
        val weird = HiveNodesResponse(
            products = listOf(
                HiveNode(
                    id = "odd",
                    type = "heating",
                    props = buildJsonObject {
                        put("working", buildJsonObject { put("x", 1) })
                        put("autoBoost", JsonPrimitive("yes"))
                        put("previous", JsonArray(emptyList()))
                    },
                    state = buildJsonObject {
                        put("name", "Odd")
                        put("boost", JsonNull)
                    },
                ),
                HiveNode(
                    id = "odd2",
                    type = "trvcontrol",
                    props = buildJsonObject {
                        put("working", "maybe")
                        put("autoBoost", buildJsonObject {
                            put("active", "nope")
                            put("trvs", JsonPrimitive("not-a-list"))
                        })
                        put("previous", buildJsonObject {
                            put("mode", JsonNull)
                            put("target", "warm")
                        })
                    },
                    state = buildJsonObject {
                        put("name", "Odd 2")
                        put("boost", "soon")
                    },
                ),
            ),
        )
        val mapped = weird.toHeatingZones()
        assertEquals(2, mapped.size)
        mapped.forEach { z ->
            assertNull(z.working)
            assertNull(z.autoBoostActive)
            assertTrue(z.autoBoostTrvIds.isEmpty())
            assertNull(z.previousMode)
            assertNull(z.previousTargetC)
            assertNull(z.boostMinutesLeft)
        }
        assertNotNull(mapped.first())
    }

    @Test
    fun previousOffModeWithoutTargetMaps() {
        val r = HiveNodesResponse(
            products = listOf(
                HiveNode(
                    id = "t",
                    type = "heating",
                    props = buildJsonObject { put("previous", buildJsonObject { put("mode", "OFF") }) },
                    state = buildJsonObject { put("name", "T") },
                ),
            ),
        )
        val z = r.toHeatingZones().single()
        assertEquals("OFF", z.previousMode)
        assertNull(z.previousTargetC)
    }
}
