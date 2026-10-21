package com.gazw89.goodhive.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User-tunable thresholds for the Reports layer. Collect [coldNightThresholdC] to feed `coldNights(...)`. */
@Singleton
class ReportPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** Nights whose minimum falls below this count as cold. Always a 0.5 step within 12-18. */
    val coldNightThresholdC: Flow<Double> = context.themeDataStore.data.map { prefs ->
        normalizeColdNightThreshold(prefs[SettingsKeys.COLD_NIGHT_THRESHOLD] ?: DEFAULT_COLD_NIGHT_C)
    }

    suspend fun setColdNightThresholdC(valueC: Double) {
        context.themeDataStore.edit { it[SettingsKeys.COLD_NIGHT_THRESHOLD] = normalizeColdNightThreshold(valueC) }
    }

    companion object {
        const val DEFAULT_COLD_NIGHT_C = 16.0
        const val MIN_COLD_NIGHT_C = 12.0
        const val MAX_COLD_NIGHT_C = 18.0
        const val COLD_NIGHT_STEP_C = 0.5

        /** Every selectable threshold, 12.0, 12.5 ... 18.0. */
        val coldNightChoices: List<Double> =
            generateSequence(MIN_COLD_NIGHT_C) { it + COLD_NIGHT_STEP_C }.takeWhile { it <= MAX_COLD_NIGHT_C }.toList()

        /** Clamps into range and snaps to the nearest 0.5 step. */
        fun normalizeColdNightThreshold(valueC: Double): Double {
            val clamped = valueC.coerceIn(MIN_COLD_NIGHT_C, MAX_COLD_NIGHT_C)
            return (clamped / COLD_NIGHT_STEP_C).roundToInt() * COLD_NIGHT_STEP_C
        }
    }
}
