package com.gazw89.goodhive.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Anchor colours of the diverging heatmap scale. Diverging around a comfort midpoint (not a
 * rainbow): the eye reads "colder than comfortable" and "warmer than comfortable" as two clear
 * directions fading through a quiet neutral, and nothing in the middle competes for attention.
 */
data class HeatmapPalette(val cold: Color, val neutral: Color, val warm: Color)

/**
 * Cold end = [calm] (cool blue), warm end = [heat] (ember), midpoint = `surfaceContainerHighest`.
 * `calm` and `heat` already have light and dark variants (the dark ones are lighter tones), so the
 * ends stay clearly distinct from the neutral on both cream and espresso surfaces, and dynamic
 * colour only nudges their hue (never flips cold/warm). The neutral is a surface role so cells
 * near the comfort value recede into the card in both themes.
 */
val ColorScheme.heatmapPalette: HeatmapPalette
    @Composable
    @ReadOnlyComposable
    get() = HeatmapPalette(cold = calm, neutral = surfaceContainerHighest, warm = heat)

/**
 * Signed position of [valueC] on a diverging scale: -1 at/below [coldC], 0 at [neutralC],
 * +1 at/above [warmC]. Degenerate spans (cold >= neutral or neutral >= warm) collapse that side to 0.
 */
fun divergingFraction(valueC: Double, coldC: Double, neutralC: Double, warmC: Double): Float {
    return when {
        valueC < neutralC -> {
            val span = neutralC - coldC
            if (span <= 0.0) 0f else -((neutralC - valueC) / span).toFloat().coerceIn(0f, 1f)
        }
        valueC > neutralC -> {
            val span = warmC - neutralC
            if (span <= 0.0) 0f else ((valueC - neutralC) / span).toFloat().coerceIn(0f, 1f)
        }
        else -> 0f
    }
}

/** Heatmap cell colour for [valueC]: cold anchor at [coldC], neutral at [neutralC], warm at [warmC]. */
fun HeatmapPalette.heatmapColor(valueC: Double, coldC: Double, neutralC: Double, warmC: Double): Color {
    val f = divergingFraction(valueC, coldC, neutralC, warmC)
    return if (f < 0f) lerp(neutral, cold, -f) else lerp(neutral, warm, f)
}
