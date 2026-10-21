package com.gazw89.goodhive.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Whether the system "Remove animations" accessibility setting is on. Defaults to false. */
val LocalReducedMotion = compositionLocalOf { false }

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Shared, tuned motion specs — calm/short, not bouncy-cartoonish, per the app's motion language. */
object GoodHiveMotion {
    /** Settling into place after a drag/gesture release — quick, no overshoot. */
    fun <T> settleSpring(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    /** Slightly softer spring for larger, less frequent transitions (e.g. sweep-fraction catch-up). */
    fun <T> gentleSpring(reducedMotion: Boolean): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)

    /** Press-scale spring — subtle, near-critically-damped so it reads as tactile, not springy. */
    fun pressSpring(reducedMotion: Boolean): FiniteAnimationSpec<Float> =
        if (reducedMotion) snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        )

    /** Crisp, non-springy timing for digit rolls. */
    fun <T> quickTween(reducedMotion: Boolean, durationMs: Int = 150): FiniteAnimationSpec<T> =
        if (reducedMotion) snap() else tween(durationMillis = durationMs)

    /** Entrance fade/slide duration; snaps when reduced motion is on. */
    fun <T> entranceTween(
        reducedMotion: Boolean,
        durationMs: Int = 500,
        delayMs: Int = 0,
    ): FiniteAnimationSpec<T> =
        if (reducedMotion) {
            snap()
        } else {
            tween(durationMillis = durationMs, delayMillis = delayMs, easing = EmphasizedDecelerate)
        }

    // Material 3's "emphasized" easing pair — two-cubic approximation of the spec's
    // multi-segment curve. Decelerate for things entering/settling, accelerate for things
    // leaving, rather than one symmetric curve for both (which reads as generic/linear-ish).
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    // Material "shared axis" timings: the outgoing screen fades out quickly while both screens
    // translate together in the navigation direction; the incoming screen's fade is delayed so
    // it doesn't cross-dissolve with the outgoing one (that overlap is what read as a "flash").
    const val NAV_TOTAL_MS = 300
    const val NAV_EXIT_FADE_MS = 90
    const val NAV_ENTER_FADE_MS = 210
    const val NAV_ENTER_FADE_DELAY_MS = NAV_TOTAL_MS - NAV_ENTER_FADE_MS
}
