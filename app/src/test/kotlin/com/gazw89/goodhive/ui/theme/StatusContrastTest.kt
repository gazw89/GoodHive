package com.gazw89.goodhive.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/** WCAG contrast for the fixed status roles' badge text on its container (Color.kt). */
class StatusContrastTest {
    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun assertReadable(label: String, text: Color, container: Color) {
        val ratio = contrast(text, container)
        assertTrue(ratio >= 4.5f, "$label contrast was $ratio, needs 4.5:1")
    }

    @Test
    fun calmBadgeTextIsReadable() {
        assertReadable("calm light", OnCalmContainerLight, CalmContainerLight)
        assertReadable("calm dark", OnCalmContainerDark, CalmContainerDark)
    }

    @Test
    fun overrideBadgeTextIsReadable() {
        assertReadable("override light", OnOverrideContainerLight, OverrideContainerLight)
        assertReadable("override dark", OnOverrideContainerDark, OverrideContainerDark)
    }

    @Test
    fun heatBadgeTextIsReadable() {
        assertReadable("heat light", OnHeatLight, HeatLight)
        assertReadable("heat dark", OnHeatDark, HeatDark)
    }
}
