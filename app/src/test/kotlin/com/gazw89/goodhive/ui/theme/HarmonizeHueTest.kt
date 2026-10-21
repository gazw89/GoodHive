package com.gazw89.goodhive.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarmonizeHueTest {
    private fun hue(c: Color): Float {
        val max = maxOf(c.red, c.green, c.blue)
        val min = minOf(c.red, c.green, c.blue)
        val d = max - min
        return when {
            d == 0f -> 0f
            max == c.red -> 60f * (((c.green - c.blue) / d + 6f) % 6f)
            max == c.green -> 60f * ((c.blue - c.red) / d + 2f)
            else -> 60f * ((c.red - c.green) / d + 4f)
        }
    }

    @Test
    fun shiftIsCappedAtFifteenDegrees() {
        val shifted = harmonizeHue(HeatLight, Color(0xFF2F6FB0))
        val diff = abs(hue(shifted) - hue(HeatLight))
        assertTrue(diff in 14f..16f, "hue shift was $diff")
    }

    @Test
    fun nearbyHueMovesAllTheWayWithoutOvershoot() {
        // Target hue is ~5 degrees from heat's: land on it, not 15 degrees past it.
        val heat = HeatLight
        val target = Color.hsv(hue(heat) + 5f, 0.8f, 0.8f)
        assertEquals(hue(target), hue(harmonizeHue(heat, target)), 1f)
    }

    @Test
    fun greyPrimaryLeavesHeatUnchanged() {
        assertEquals(HeatDark, harmonizeHue(HeatDark, Color(0xFF808080)))
    }

    @Test
    fun calmStaysCoolUnderWarmDynamicPrimary() {
        // A peach wallpaper primary turned tertiary peach before; calm must stay out of heat's hues.
        val peach = Color(0xFFFFB68F)
        val heatHue = hue(HeatLight)
        for (calm in listOf(CalmLight, CalmContainerLight, CalmDark, CalmContainerDark)) {
            val shifted = hue(harmonizeHue(calm, peach))
            assertTrue(shifted in 180f..240f, "calm hue drifted to $shifted")
            assertTrue(abs(shifted - heatHue) > 90f, "calm hue $shifted too close to heat $heatHue")
        }
    }
}
