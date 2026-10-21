package com.gazw89.goodhive.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ChartColorsTest {
    @Test fun divergingEndsAndMidpoint() {
        assertEquals(-1f, divergingFraction(10.0, 15.0, 18.5, 22.0))
        assertEquals(-1f, divergingFraction(15.0, 15.0, 18.5, 22.0))
        assertEquals(0f, divergingFraction(18.5, 15.0, 18.5, 22.0))
        assertEquals(1f, divergingFraction(22.0, 15.0, 18.5, 22.0))
        assertEquals(1f, divergingFraction(30.0, 15.0, 18.5, 22.0))
    }

    @Test fun divergingIsLinearEitherSide() {
        assertEquals(-0.5f, divergingFraction(16.75, 15.0, 18.5, 22.0), 1e-4f)
        assertEquals(0.5f, divergingFraction(20.25, 15.0, 18.5, 22.0), 1e-4f)
    }

    @Test fun divergingDegenerateSpansAreNeutral() {
        assertEquals(0f, divergingFraction(10.0, 20.0, 18.0, 22.0))
        assertEquals(0f, divergingFraction(25.0, 15.0, 18.0, 17.0))
    }

    @Test fun heatmapColorHitsAnchors() {
        val p = HeatmapPalette(cold = Color.Blue, neutral = Color.Gray, warm = Color.Red)
        assertEquals(Color.Blue, p.heatmapColor(0.0, 15.0, 18.5, 22.0))
        assertEquals(Color.Gray, p.heatmapColor(18.5, 15.0, 18.5, 22.0))
        assertEquals(Color.Red, p.heatmapColor(99.0, 15.0, 18.5, 22.0))
    }
}
