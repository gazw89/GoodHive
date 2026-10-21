package com.gazw89.goodhive.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gazw89.goodhive.data.model.HeatingMode
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.LocalReducedMotion
import com.gazw89.goodhive.ui.theme.PillShape

private const val PULSE_MIN_ALPHA = 0.6f
private const val PULSE_HALF_PERIOD_MS = 1200
private const val TRACK_ALPHA = 0.24f

/**
 * Icon that gently pulses its alpha (~0.6 to 1.0) while [pulse] is true. Static when the system
 * "remove animations" setting is on ([LocalReducedMotion]).
 */
@Composable
fun PulsingIcon(
    icon: ImageVector,
    pulse: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (pulse && !LocalReducedMotion.current) {
        val transition = rememberInfiniteTransition(label = "icon-pulse")
        val alpha by transition.animateFloat(
            initialValue = 1f,
            targetValue = PULSE_MIN_ALPHA,
            animationSpec = infiniteRepeatable(
                animation = tween(PULSE_HALF_PERIOD_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "icon-pulse-alpha",
        )
        Icon(icon, contentDescription = contentDescription, modifier = modifier.alpha(alpha))
    } else {
        Icon(icon, contentDescription = contentDescription, modifier = modifier)
    }
}

/** Icon for a heating mode, shared by the dashboard badges and the detail screen's mode selector. */
fun modeIcon(mode: String): ImageVector = when (mode) {
    HeatingMode.MANUAL -> Icons.Filled.TouchApp
    HeatingMode.OFF -> Icons.Filled.PowerSettingsNew
    HeatingMode.BOOST -> Icons.Filled.Bolt
    else -> Icons.Filled.Schedule
}

/**
 * Read-only status badge (one status signal per card). A flat, non-interactive label rather than a
 * chip so it never reads as a button next to real controls. Filled when [borderColor] is null,
 * outlined when a [borderColor] is given. Label, icon and fill/outline differ per state so colour is
 * never the only signal.
 */
@Composable
fun StatusBadge(
    label: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    pulseIcon: Boolean = false,
    contentDescription: String = label,
) {
    Surface(
        shape = PillShape,
        color = containerColor,
        contentColor = contentColor,
        border = borderColor?.let { BorderStroke(1.dp, it) },
        modifier = modifier.clearAndSetSemantics { this.contentDescription = contentDescription },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = GoodHiveSpacing.md, vertical = GoodHiveSpacing.tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.tight),
        ) {
            PulsingIcon(icon, pulse = pulseIcon, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Determinate "getting there" bar in the heat colour with a 24% track. Deliberately flat: the
 * expressive wavy variant flattens itself near the ends of its range, so two cards in the same
 * state could look different for no reason a user could read. [progress] is animated (snaps under
 * reduced motion).
 */
@Composable
fun HeatProgress(
    progress: Float,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = LocalReducedMotion.current
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = GoodHiveMotion.quickTween(reducedMotion, durationMs = 400),
        label = "heat-progress",
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        color = color,
        trackColor = color.copy(alpha = TRACK_ALPHA),
    )
}
