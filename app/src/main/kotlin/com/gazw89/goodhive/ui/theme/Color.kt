package com.gazw89.goodhive.ui.theme

import androidx.compose.ui.graphics.Color

// Brand: warm honey amber, matching the hexagon hive launcher mark (#FF8F00).
val HoneyAmber = Color(0xFFFF8F00)
val HoneyAmberDeep = Color(0xFFC96A00)
val HoneyAmberLight = Color(0xFFFFC46B)
val HoneySunrise = Color(0xFFFFB74D)

// Warm neutrals — cream/parchment in light mode, deep espresso charcoal in dark mode,
// rather than the cold grey Material default. This is what makes the app read as "warm
// consumer product" instead of "utilitarian tool".
val CreamBackground = Color(0xFFFFFBF3)
val CreamSurface = Color(0xFFFFF6E6)
val CreamSurfaceHigh = Color(0xFFFCEDCE)
val EspressoBackground = Color(0xFF191410)
val EspressoSurface = Color(0xFF241C15)
val EspressoSurfaceHigh = Color(0xFF33281C)
val EspressoOnSurface = Color(0xFFF3E9DA)

// One step below/above the base surface, for nested tonal hierarchy (e.g. a bottom sheet
// sitting on a page, or a card sitting on a sheet) without reaching for another bordered box.
val CreamSurfaceLow = Color(0xFFFFFDF8)
val CreamSurfaceLowest = Color(0xFFFFFFFF)
val EspressoSurfaceLow = Color(0xFF161109)
val EspressoSurfaceLowest = Color(0xFF100C08)

val Ember = Color(0xFFD8452B) // errors / offline — warm red, not clinical
val Sage = Color(0xFF3E8E63) // success / online

// Temperature scale: cool blue -> honey amber -> ember red, used for dial/timeline gradients.
val TempCold = Color(0xFF4C8FE3)
val TempMild = Color(0xFFFFB74D)
val TempHot = Color(0xFFE85D3F)

// Cool "status" accent (tertiary role) — distinct from the warm heat-call palette above, used
// for schedule/idle/system chips so warm color stays meaningful for active heating only.
val TertiaryLight = Color(0xFF2F6FB0)
val TertiaryContainerLight = Color(0xFFD8E7FB)
val OnTertiaryContainerLight = Color(0xFF0E2C47)
val TertiaryDark = Color(0xFFA9CBF2)
val TertiaryContainerDark = Color(0xFF1E3A5C)
val OnTertiaryContainerDark = Color(0xFFD8E7FB)

val ErrorContainerLight = Color(0xFFFBDCD4)
val OnErrorContainerLight = Color(0xFF410E00)
val ErrorContainerDark = Color(0xFF5C1B0C)
val OnErrorContainerDark = Color(0xFFFFDBCF)

// "Heat" role (active heating / boost / Heat on Demand) — a warm extended role distinct from the
// error and honey brand colours. Surfaced via ColorScheme.heat* in TemperatureVisuals.kt.
// Contrast (docs/heat-on-demand-and-status-ui.md §3): HeatLight 5.98:1 on white; HeatDark 7.72:1
// vs OnHeatDark; heat text on its container 4.64:1 light / 6.98:1 dark; onHeatContainer 13.3:1
// light / 9.24:1 dark.
val HeatLight = Color(0xFFA94400)
val OnHeatLight = Color(0xFFFFFFFF)
val HeatContainerLight = Color(0xFFFFDCC6)
val OnHeatContainerLight = Color(0xFF351000)
val HeatDark = Color(0xFFFFB689)
val OnHeatDark = Color(0xFF552000)
val HeatContainerDark = Color(0xFF5F2600)
val OnHeatContainerDark = Color(0xFFFFDCC6)

// "Calm" role (room following its schedule) — cool blue, the same tones as the tertiary tokens above
// but surfaced as a fixed extended role (ColorScheme.calm* in TemperatureVisuals.kt) so dynamic colour
// can't turn it peach the way it did tertiaryContainer. Contrast is asserted in StatusContrastTest.
val CalmLight = TertiaryLight
val CalmContainerLight = TertiaryContainerLight
val OnCalmContainerLight = OnTertiaryContainerLight
val CalmDark = TertiaryDark
val CalmContainerDark = TertiaryContainerDark
val OnCalmContainerDark = OnTertiaryContainerDark

// "Override" role (room on Manual, schedule bypassed) — violet, distinct from both the calm blue and
// the warm heat role. Surfaced via ColorScheme.override* in TemperatureVisuals.kt.
val OverrideLight = Color(0xFF6B4EA2)
val OverrideContainerLight = Color(0xFFEADDFF)
val OnOverrideContainerLight = Color(0xFF25005A)
val OverrideDark = Color(0xFFD3BBFF)
val OverrideContainerDark = Color(0xFF4A3477)
val OnOverrideContainerDark = Color(0xFFEADDFF)
