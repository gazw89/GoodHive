package com.gazw89.goodhive.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import com.gazw89.goodhive.ui.components.HeatProgress
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.gazw89.goodhive.data.model.HeatingZone
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.PillShape
import com.gazw89.goodhive.ui.theme.heat
import com.gazw89.goodhive.ui.theme.heatContainer
import com.gazw89.goodhive.ui.theme.heatInnerSurface
import com.gazw89.goodhive.ui.theme.onHeat
import com.gazw89.goodhive.ui.theme.onHeatContainer
import com.gazw89.goodhive.util.joinNames

/** Minimum height of a tappable source row (>= 48dp touch target, matches the design mock). */
private val SourceRowMinHeight = 56.dp
private val HeaderIconSize = 40.dp
private val HeaderGlyphSize = 22.dp
private val NoteIconSize = 18.dp

/** Source rows shown before the rest collapse into "+N more" (keeps the card on one screen). */
private const val MAX_SOURCE_ROWS = 3

/** "20" for whole degrees, "17.2" otherwise — Hive TRVs report 0.5 steps / 0.1 readings. */
private fun formatDegrees(valueC: Double?): String = when {
    valueC == null -> "–"
    valueC % 1.0 == 0.0 -> "%.0f".format(valueC)
    else -> "%.1f".format(valueC)
}

/**
 * Compact card shown at the top of the thermostat detail while a Heat on Demand boost is running
 * (docs/heat-on-demand-and-status-ui.md §5): which radiator(s) started it, when it will stop, and a
 * tappable row per source TRV (up to [MAX_SOURCE_ROWS]), and a "Stop boost" button that puts every
 * source back to its schedule. It never blocks the thermostat controls.
 */
@Composable
fun HeatOnDemandCard(
    sources: List<HeatingZone>,
    onOpenZone: (String) -> Unit,
    onStopBoost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = when (sources.size) {
        0 -> "Heat on Demand is running your heating"
        1 -> "${sources.first().name} is running your heating"
        else -> "${sources.size} radiators are running your heating"
    }
    val sentence = when (sources.size) {
        0 -> "This room is at target. The boiler stays on until the radiator that asked for heat is warm, " +
            "then your schedule takes over again."
        1 -> {
            val source = sources.first()
            "This room is at target. The boiler stays on until ${source.name} reaches " +
                "${formatDegrees(source.targetTemperatureC)}°, then your schedule takes over again."
        }
        else -> "This room is at target. The boiler stays on until they reach their targets, " +
            "then your schedule takes over again."
    }
    val summary = "Heat on Demand. $title. $sentence"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = summary
            },
        color = MaterialTheme.colorScheme.heatContainer,
        contentColor = MaterialTheme.colorScheme.onHeatContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(GoodHiveSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
        ) {
            // The container's contentDescription above is the single announcement; keep the visual
            // header/sentence out of the accessibility tree so TalkBack doesn't read them twice.
            Column(
                modifier = Modifier.clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(HeaderIconSize)
                            .clip(PillShape)
                            .background(MaterialTheme.colorScheme.heat),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.ViewWeek,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onHeat,
                            modifier = Modifier.size(HeaderGlyphSize),
                        )
                    }
                    Column {
                        Text(
                            text = "HEAT ON DEMAND",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.heat,
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                Text(text = sentence, style = MaterialTheme.typography.bodyMedium)
            }

            sources.take(MAX_SOURCE_ROWS).forEach { source -> HodSourceRow(source, onOpenZone) }
            val hidden = sources.size - MAX_SOURCE_ROWS
            if (hidden > 0) {
                Text(
                    text = "+$hidden more",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = GoodHiveSpacing.lg),
                )
            }

            if (sources.isNotEmpty()) {
                StopBoostAction(sources = sources, onStopBoost = onStopBoost)
            }
        }
    }
}

/**
 * Lowers every source TRV to its schedule (Heat on Demand stays enabled). Accent `heat` container
 * with `onHeat` content so it reads clearly on [heatContainer] in light and dark.
 */
@Composable
private fun StopBoostAction(sources: List<HeatingZone>, onStopBoost: () -> Unit) {
    val names = joinNames(sources.map { it.name })
    val supporting = if (sources.size == 1) {
        "Puts $names back to its schedule. Heat on Demand stays on."
    } else {
        "Puts $names back to their schedules. Heat on Demand stays on."
    }
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
        FilledTonalButton(
            onClick = onStopBoost,
            modifier = Modifier.heightIn(min = SourceRowMinHeight),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.heat,
                contentColor = MaterialTheme.colorScheme.onHeat,
            ),
        ) {
            Icon(Icons.Filled.StopCircle, contentDescription = null, modifier = Modifier.size(NoteIconSize))
            Spacer(Modifier.width(GoodHiveSpacing.sm))
            Text("Stop boost")
        }
        Text(text = supporting, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HodSourceRow(source: HeatingZone, onOpenZone: (String) -> Unit) {
    val current = source.currentTemperatureC
    val target = source.targetTemperatureC
    val description = buildString {
        append(source.name)
        if (current != null) append(", ${formatDegrees(current)} degrees")
        if (target != null) append(", target ${formatDegrees(target)}")
        append(". Opens ${source.name}.")
    }
    // Purely decorative fill: how far the radiator has warmed between its minimum and its target.
    val fraction = if (current != null && target != null && target > source.minTemperatureC) {
        ((current - source.minTemperatureC) / (target - source.minTemperatureC)).toFloat().coerceIn(0f, 1f)
    } else {
        null
    }
    val heat = MaterialTheme.colorScheme.heat
    val progressDescription = if (current != null && target != null && target > current) {
        "${"%.1f".format(target - current)} degrees below target"
    } else {
        "Warming towards target"
    }

    Surface(
        onClick = { onOpenZone(source.id) },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SourceRowMinHeight)
            .semantics { contentDescription = description },
        color = MaterialTheme.colorScheme.heatInnerSurface,
        contentColor = MaterialTheme.colorScheme.onHeatContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(
                start = GoodHiveSpacing.lg,
                end = GoodHiveSpacing.sm,
                top = GoodHiveSpacing.sm,
                bottom = GoodHiveSpacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.tight),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(GoodHiveSpacing.sm))
                    Text(
                        text = "${formatDegrees(current)}° → ${formatDegrees(target)}°",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (fraction != null) {
                    HeatProgress(
                        progress = fraction,
                        color = heat,
                        description = progressDescription,
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onHeatContainer,
            )
        }
    }
}
