package com.gazw89.goodhive.data.prefs

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DropUnknownSettingsTest {
    private val leftover = booleanPreferencesKey("removed_feature_enabled")

    @Test fun removesUnknownKeysAndKeepsKnownOnes() = runTest {
        val before = preferencesOf(
            leftover to true,
            SettingsKeys.THEME_MODE to "DARK",
            SettingsKeys.DYNAMIC_COLOR to true,
            SettingsKeys.COLD_NIGHT_THRESHOLD to 15.5,
        )
        assertTrue(DropUnknownSettings.shouldMigrate(before))
        val after = DropUnknownSettings.migrate(before)
        assertFalse(leftover in after)
        assertEquals("DARK", after[SettingsKeys.THEME_MODE])
        assertEquals(true, after[SettingsKeys.DYNAMIC_COLOR])
        assertEquals(15.5, after[SettingsKeys.COLD_NIGHT_THRESHOLD]!!, 0.0)
        assertFalse(DropUnknownSettings.shouldMigrate(after))
    }

    @Test fun nothingToDoWhenOnlyKnownKeysExist() = runTest {
        assertFalse(DropUnknownSettings.shouldMigrate(preferencesOf(SettingsKeys.THEME_MODE to "LIGHT")))
    }
}
