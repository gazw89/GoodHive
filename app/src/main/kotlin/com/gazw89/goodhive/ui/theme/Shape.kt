package com.gazw89.goodhive.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Softer, more generous rounding than Material default — reads as friendly/premium
// rather than boxy-utilitarian.
val GoodHiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Full-pill / stadium shape for chips and banners (named token — do not use ad-hoc RoundedCornerShape(50)). */
val PillShape = RoundedCornerShape(percent = 50)

/** Chart mark corner radii (Canvas-drawn marks cannot use [GoodHiveShapes]; these are the named tokens). */
val ChartBarCorner = 4.dp
val ChartCellCorner = 2.dp
