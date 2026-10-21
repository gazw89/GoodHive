package com.gazw89.goodhive.util

import kotlin.test.Test
import kotlin.test.assertEquals

class NameFormatTest {
    @Test
    fun joinNames() {
        assertEquals("", joinNames(emptyList()))
        assertEquals("A", joinNames(listOf("A")))
        assertEquals("A and B", joinNames(listOf("A", "B")))
        assertEquals("A, B and C", joinNames(listOf("A", "B", "C")))
        assertEquals("A, B, C and D", joinNames(listOf("A", "B", "C", "D")))
    }

    @Test
    fun hodFiringReason() {
        assertEquals("Heat on Demand is firing the boiler", hodFiringReason(emptyList()))
        assertEquals("Study's Heat on Demand is firing the boiler", hodFiringReason(listOf("Study")))
        assertEquals(
            "Kitchen and Study's Heat on Demand is firing the boiler",
            hodFiringReason(listOf("Kitchen", "Study")),
        )
        assertEquals(
            "Heat on Demand from A, B and C is firing the boiler",
            hodFiringReason(listOf("A", "B", "C")),
        )
    }
}
