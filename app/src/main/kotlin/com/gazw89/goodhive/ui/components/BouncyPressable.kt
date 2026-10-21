package com.gazw89.goodhive.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import com.gazw89.goodhive.ui.theme.rememberReducedMotion

/**
 * Shared "scale down slightly while pressed" tactile feedback — replaces the three near-identical
 * copies of this pattern that used to live in Login/Dashboard/DeviceDetail.
 */
fun Modifier.bouncyPressable(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.95f,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reducedMotion = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = GoodHiveMotion.pressSpring(reducedMotion),
        label = "bouncy-press-scale",
    )
    this.scale(scale)
}
