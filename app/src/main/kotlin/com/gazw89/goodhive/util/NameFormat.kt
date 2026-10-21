package com.gazw89.goodhive.util

/** "A", "A and B", "A, B and C" (no Oxford comma, UK style). Empty list gives an empty string. */
fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

/**
 * Household-summary reason while Heat on Demand fires the boiler. One or two names use the
 * possessive ("Study's …", "Kitchen and Study's …"); three or more read better as
 * "Heat on Demand from A, B and C …" than a long possessive.
 */
fun hodFiringReason(sourceNames: List<String>): String = when (sourceNames.size) {
    0 -> "Heat on Demand is firing the boiler"
    1, 2 -> "${joinNames(sourceNames)}'s Heat on Demand is firing the boiler"
    else -> "Heat on Demand from ${joinNames(sourceNames)} is firing the boiler"
}
