package com.gazw89.goodhive.data.prefs

/**
 * User-facing appearance preference. [SYSTEM] follows the device setting;
 * [LIGHT] / [DARK] force the app palette regardless of system night mode.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}
