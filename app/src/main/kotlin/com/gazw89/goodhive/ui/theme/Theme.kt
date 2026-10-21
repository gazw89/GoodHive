package com.gazw89.goodhive.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = HoneyAmberDeep,
    onPrimary = Color.White,
    primaryContainer = HoneyAmberLight,
    onPrimaryContainer = Color(0xFF3D2200),
    secondary = HoneyAmber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE3BC),
    onSecondaryContainer = Color(0xFF3D2200),
    background = CreamBackground,
    onBackground = Color(0xFF241C15),
    surface = CreamBackground,
    onSurface = Color(0xFF241C15),
    surfaceVariant = CreamSurface,
    onSurfaceVariant = Color(0xFF5A4C3B),
    surfaceContainer = CreamSurface,
    surfaceContainerHigh = CreamSurfaceHigh,
    surfaceContainerHighest = Color(0xFFF6DFB4),
    surfaceContainerLow = CreamSurfaceLow,
    surfaceContainerLowest = CreamSurfaceLowest,
    surfaceBright = CreamSurfaceLowest,
    surfaceDim = CreamSurfaceHigh,
    outline = Color(0xFFB8A88C),
    outlineVariant = Color(0xFFDCCBB0),
    scrim = Color(0xFF000000),
    error = Ember,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    tertiary = TertiaryLight,
    onTertiary = Color.White,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    inverseSurface = Color(0xFF382E22),
    inverseOnSurface = Color(0xFFFCEEDA),
    inversePrimary = HoneySunrise,
)

private val DarkColors = darkColorScheme(
    primary = HoneySunrise,
    onPrimary = Color(0xFF3D2200),
    primaryContainer = HoneyAmberDeep,
    onPrimaryContainer = Color(0xFFFFE3BC),
    secondary = HoneyAmberLight,
    onSecondary = Color(0xFF3D2200),
    secondaryContainer = Color(0xFF5A3A10),
    onSecondaryContainer = Color(0xFFFFE3BC),
    background = EspressoBackground,
    onBackground = EspressoOnSurface,
    surface = EspressoBackground,
    onSurface = EspressoOnSurface,
    surfaceVariant = EspressoSurface,
    onSurfaceVariant = Color(0xFFD8C7AF),
    surfaceContainer = EspressoSurface,
    surfaceContainerHigh = EspressoSurfaceHigh,
    surfaceContainerHighest = Color(0xFF3E3123),
    surfaceContainerLow = EspressoSurfaceLow,
    surfaceContainerLowest = EspressoSurfaceLowest,
    surfaceBright = EspressoSurfaceHigh,
    surfaceDim = EspressoBackground,
    outline = Color(0xFF7A6A54),
    outlineVariant = Color(0xFF4A3D2E),
    scrim = Color(0xFF000000),
    error = Color(0xFFFF8A72),
    onError = Color(0xFF3D0B00),
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    tertiary = TertiaryDark,
    onTertiary = Color(0xFF0E2C47),
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    inverseSurface = EspressoOnSurface,
    inverseOnSurface = Color(0xFF241C15),
    inversePrimary = HoneyAmberDeep,
)

/**
 * Material 3 theme. Branded honey/espresso palette by default; optional Material You dynamic
 * color (wallpaper-derived) when [dynamicColor] is true and the platform supports it.
 */
@Composable
fun GoodHiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val reducedMotion = rememberReducedMotion()
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GoodHiveTypography,
            shapes = GoodHiveShapes,
            content = content,
        )
    }
}
