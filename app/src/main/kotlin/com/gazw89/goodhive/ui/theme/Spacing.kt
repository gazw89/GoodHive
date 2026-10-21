package com.gazw89.goodhive.ui.theme

import androidx.compose.ui.unit.dp

/** Shared spacing scale — use instead of ad hoc dp literals so rhythm stays consistent app-wide. */
object GoodHiveSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    /** Compact control padding between xs and sm (e.g. 6.dp). */
    val tight = 6.dp
    /** Between sm and md (e.g. 10.dp). */
    val cozy = 10.dp
    /** Between md and lg (e.g. 14.dp). */
    val roomy = 14.dp
    /** Between lg and xl (e.g. 20.dp). */
    val section = 20.dp
    /** Large empty-state / hero spacer. */
    val hero = 52.dp

    /** Round icon container in cards and list rows (40dp, Material list-item leading size). */
    val iconContainer = 40.dp
    /** Minimum interactive target (Material 3). */
    val touchTarget = 48.dp
    /** Height of line / bar charts in report cards. */
    val chartHeight = 180.dp
    /** Height of the hour-by-weekday heat map. */
    val heatmapHeight = 200.dp
    /** Width of a legend swatch. */
    val legendSwatch = 20.dp
}
