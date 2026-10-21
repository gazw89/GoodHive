package com.gazw89.goodhive.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** The UI-chosen temperature step — the backend accepts arbitrary Doubles, this is purely a UX choice. */
const val TEMPERATURE_STEP_C = 0.5

/** Builds the discrete list of selectable values (e.g. for a WheelPicker), inclusive of both ends. */
fun temperatureSteps(minC: Double, maxC: Double, stepC: Double = TEMPERATURE_STEP_C): List<Double> {
    if (maxC <= minC) return listOf(minC)
    val count = ((maxC - minC) / stepC).toInt()
    return (0..count).map { (minC + it * stepC).coerceAtMost(maxC) }
}

/** Snaps [valueC] to the nearest selectable step inside [[minC], [maxC]]. */
fun clampToTemperatureStep(valueC: Double, minC: Double, maxC: Double): Double {
    val steps = temperatureSteps(minC, maxC)
    return steps.minBy { kotlin.math.abs(it - valueC) }
}

/**
 * Cool-blue -> honey -> ember mapping for dials/timelines.
 * Same stops in light and dark; mid tones stay readable on both cream and espresso surfaces.
 */
fun temperatureColor(valueC: Double, minC: Double, maxC: Double): Color {
    val fraction = ((valueC - minC) / (maxC - minC).coerceAtLeast(0.01)).toFloat().coerceIn(0f, 1f)
    return if (fraction < 0.5f) {
        lerp(TempCold, TempMild, fraction / 0.5f)
    } else {
        lerp(TempMild, TempHot, (fraction - 0.5f) / 0.5f)
    }
}

/**
 * Heat role (active heating / boost / Heat on Demand) — see Color.kt for contrast notes.
 * Picks the light or dark variant from background luminance. When the
 * scheme isn't the branded honey one (i.e. dynamic colour), `heat`/`heatContainer` hues are
 * nudged toward `primary` via [harmonizeHue] so they sit with the wallpaper palette; the
 * `on*` roles keep their fixed tones so contrast is unaffected.
 */
val ColorScheme.heat: Color
    @Composable
    @ReadOnlyComposable
    get() = harmonizedWithPrimary(if (isDarkScheme()) HeatDark else HeatLight)

val ColorScheme.onHeat: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isDarkScheme()) OnHeatDark else OnHeatLight

val ColorScheme.heatContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = harmonizedWithPrimary(if (isDarkScheme()) HeatContainerDark else HeatContainerLight)

val ColorScheme.onHeatContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isDarkScheme()) OnHeatContainerDark else OnHeatContainerLight

/**
 * Calm role (room following its schedule) — fixed cool blue, harmonised like [heat] so dynamic
 * colour can nudge it but never turn it warm. `on*` keeps fixed tones for contrast.
 */
val ColorScheme.calm: Color
    @Composable
    @ReadOnlyComposable
    get() = harmonizedWithPrimary(if (isDarkScheme()) CalmDark else CalmLight)

val ColorScheme.calmContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = harmonizedWithPrimary(if (isDarkScheme()) CalmContainerDark else CalmContainerLight)

val ColorScheme.onCalmContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isDarkScheme()) OnCalmContainerDark else OnCalmContainerLight

/** Override role (room on Manual, schedule bypassed) — fixed violet, harmonised like [heat]. */
val ColorScheme.override: Color
    @Composable
    @ReadOnlyComposable
    get() = harmonizedWithPrimary(if (isDarkScheme()) OverrideDark else OverrideLight)

val ColorScheme.overrideContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = harmonizedWithPrimary(if (isDarkScheme()) OverrideContainerDark else OverrideContainerLight)

val ColorScheme.onOverrideContainer: Color
    @Composable
    @ReadOnlyComposable
    get() = if (isDarkScheme()) OnOverrideContainerDark else OnOverrideContainerLight

/** Alpha of the [heatInnerSurface] tint; keeps onHeatContainer text well above 4.5:1. */
private const val HEAT_INNER_SURFACE_ALPHA = 0.10f

/**
 * Tonal layer for controls and rows sitting inside a [heatContainer] card (dashboard stepper buttons,
 * "Boosted by" row, Heat on Demand source rows). A translucent [onHeatContainer] tint, so it reads
 * as a slightly deeper/lighter step of the card in both light and dark rather than a near-black
 * `surfaceContainerLowest` slab. Pair with [onHeatContainer] content and [heat] accent icons.
 */
val ColorScheme.heatInnerSurface: Color
    @Composable
    @ReadOnlyComposable
    get() = onHeatContainer.copy(alpha = HEAT_INNER_SURFACE_ALPHA)

private fun ColorScheme.isDarkScheme(): Boolean = background.luminance() < 0.5f

/** Branded schemes (Theme.kt) use these primaries; anything else is a dynamic scheme. */
private fun ColorScheme.isBrandedScheme(): Boolean = primary == HoneyAmberDeep || primary == HoneySunrise

private fun ColorScheme.harmonizedWithPrimary(heat: Color): Color =
    if (isBrandedScheme()) heat else harmonizeHue(heat, primary)

/** Largest hue shift (degrees) [harmonizeHue] will apply. */
private const val MAX_HARMONIZE_SHIFT_DEG = 15f

/** Below this HSV saturation a colour has no meaningful hue to harmonise toward. */
private const val MIN_HARMONIZE_SATURATION = 0.15f

/**
 * Shifts [color]'s hue up to [MAX_HARMONIZE_SHIFT_DEG] degrees (the short way round the wheel)
 * toward [toward]'s hue, keeping saturation and value (alpha too). Pure and dependency-free so it
 * is unit-testable. Greys (nearly no saturation) in [toward] leave [color] unchanged.
 */
fun harmonizeHue(color: Color, toward: Color): Color {
    val (hue, sat, value) = color.toHsv()
    val (targetHue, targetSat, _) = toward.toHsv()
    if (targetSat < MIN_HARMONIZE_SATURATION) return color
    var delta = (targetHue - hue) % 360f
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    val shift = delta.coerceIn(-MAX_HARMONIZE_SHIFT_DEG, MAX_HARMONIZE_SHIFT_DEG)
    return hsvToColor((hue + shift + 360f) % 360f, sat, value, color.alpha)
}

private fun Color.toHsv(): Triple<Float, Float, Float> {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == red -> 60f * (((green - blue) / delta + 6f) % 6f)
        max == green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return Triple(hue, if (max == 0f) 0f else delta / max, max)
}

private fun hsvToColor(hue: Float, saturation: Float, value: Float, alpha: Float): Color {
    fun channel(n: Int): Float {
        val k = (n + hue / 60f) % 6f
        return value - value * saturation * maxOf(0f, minOf(k, 4f - k, 1f))
    }
    return Color(channel(5), channel(3), channel(1), alpha)
}
