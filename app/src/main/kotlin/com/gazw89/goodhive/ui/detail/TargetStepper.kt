package com.gazw89.goodhive.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.PillShape
import com.gazw89.goodhive.ui.theme.TEMPERATURE_STEP_C

private val StepperButtonSize = 64.dp
private val StepperIconSize = 28.dp

/** WCAG contrast ratio between two opaque colours. */
private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** Whichever of [light]/[dark] (both theme roles) reads better on [container]. */
internal fun readableOn(container: Color, light: Color, dark: Color): Color =
    if (contrast(container, light) >= contrast(container, dark)) light else dark

/**
 * Full-width pill with big − / + buttons around the target number. Tapping the number opens the
 * exact-temperature picker. [accent] tints the "+" button; its icon colour is picked from theme
 * roles for contrast.
 */
@Composable
fun TargetStepper(
    targetC: Double,
    minC: Double,
    maxC: Double,
    accent: Color,
    isApplying: Boolean,
    onTargetChange: (Double) -> Unit,
    onOpenPicker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val onAccent = readableOn(accent, light = scheme.surface, dark = scheme.onSurface)
    val valueText = "%.1f".format(targetC)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(scheme.surfaceContainer)
            .padding(GoodHiveSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
    ) {
        FilledTonalIconButton(
            onClick = { onTargetChange((targetC - TEMPERATURE_STEP_C).coerceIn(minC, maxC)) },
            enabled = targetC > minC,
            modifier = Modifier.size(StepperButtonSize),
        ) {
            Icon(Icons.Filled.Remove, contentDescription = "Lower target", modifier = Modifier.size(StepperIconSize))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(PillShape)
                .clickable(onClickLabel = "Choose temperature", role = Role.Button, onClick = onOpenPicker)
                .semantics(mergeDescendants = true) {
                    contentDescription = "Target temperature"
                    stateDescription = "$valueText degrees"
                }
                .padding(vertical = GoodHiveSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "$valueText°",
                style = MaterialTheme.typography.displaySmall,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (isApplying) "Applying…" else "Target",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }

        FilledIconButton(
            onClick = { onTargetChange((targetC + TEMPERATURE_STEP_C).coerceIn(minC, maxC)) },
            enabled = targetC < maxC,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accent,
                contentColor = onAccent,
            ),
            modifier = Modifier.size(StepperButtonSize),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Raise target", modifier = Modifier.size(StepperIconSize))
        }
    }
}
