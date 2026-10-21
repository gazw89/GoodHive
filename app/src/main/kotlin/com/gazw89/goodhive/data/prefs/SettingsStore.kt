package com.gazw89.goodhive.data.prefs

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

internal val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "goodhive_settings",
    produceMigrations = { listOf(DropUnknownSettings) },
)

/** Every key stored in `goodhive_settings`. A key missing from [all] is deleted on the next start. */
internal object SettingsKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    val COLD_NIGHT_THRESHOLD = doublePreferencesKey("cold_night_threshold_c")

    val all: Set<Preferences.Key<*>> = setOf(THEME_MODE, DYNAMIC_COLOR, COLD_NIGHT_THRESHOLD)
}

/** Removes settings left behind by features that no longer exist. */
internal object DropUnknownSettings : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        currentData.asMap().keys.any { it !in SettingsKeys.all }

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply {
            asMap().keys.filter { it !in SettingsKeys.all }.forEach { remove(it) }
        }.toPreferences()

    override suspend fun cleanUp() = Unit
}
