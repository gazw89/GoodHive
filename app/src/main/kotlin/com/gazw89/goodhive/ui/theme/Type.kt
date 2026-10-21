package com.gazw89.goodhive.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

// Bolder display/headline weights than Material's default — the temperature readouts are
// the hero content on most screens and should feel confident, not thin/utilitarian.
private val Base = Typography()

val GoodHiveTypography = Base.copy(
    displayLarge = Base.displayLarge.copy(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
    displayMedium = Base.displayMedium.copy(fontWeight = FontWeight.Bold),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
)

/** Schedule time labels — monospace for aligned columns; prefer this over ad-hoc FontFamily. */
val ScheduleTimeTextStyle = GoodHiveTypography.titleMedium.copy(fontFamily = FontFamily.Monospace)
