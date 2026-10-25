package com.gazw89.goodhive.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Whether the starter routine was already created, so deleting it never brings it back (reset on
 * sign-out), and whether the dashboard's Routines row is collapsed (kept across accounts).
 */
@Singleton
open class RoutinePreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    open suspend fun isSeeded(): Boolean = context.themeDataStore.data.first()[SettingsKeys.ROUTINES_SEEDED] == true

    open suspend fun setSeeded(seeded: Boolean) {
        context.themeDataStore.edit { it[SettingsKeys.ROUTINES_SEEDED] = seeded }
    }

    open fun observeCollapsed(): Flow<Boolean> = context.themeDataStore.data.map { it[SettingsKeys.ROUTINES_COLLAPSED] == true }

    open suspend fun setCollapsed(collapsed: Boolean) {
        context.themeDataStore.edit { it[SettingsKeys.ROUTINES_COLLAPSED] = collapsed }
    }
}
