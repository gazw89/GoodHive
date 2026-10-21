package com.gazw89.goodhive.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ThemePreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val themeMode: Flow<ThemeMode> = context.themeDataStore.data.map { prefs ->
        prefs[SettingsKeys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    /** Material You wallpaper colors; branded palette when false or unsupported. Default off. */
    val dynamicColor: Flow<Boolean> = context.themeDataStore.data.map { prefs ->
        prefs[SettingsKeys.DYNAMIC_COLOR] ?: false
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { prefs ->
            prefs[SettingsKeys.THEME_MODE] = mode.name
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.themeDataStore.edit { prefs ->
            prefs[SettingsKeys.DYNAMIC_COLOR] = enabled
        }
    }
}
