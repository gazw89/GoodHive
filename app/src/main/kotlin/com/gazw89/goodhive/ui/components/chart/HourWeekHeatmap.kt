package com.gazw89.goodhive.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gazw89.goodhive.ui.theme.ChartCellCorner
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.GoodHiveTheme
import com.gazw89.goodhive.ui.theme.heatmapColor
import com.gazw89.goodhive.ui.theme.heatmapPalette
import kotlin.math.roundToInt

private val DayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val HourLabels = listOf(0 to "00", 6 to "06", 12 to "12", 18 to "18")
private const val Days = 7
private const val Hours = 24

/**
 * 7 (Mon..Sun) x 24 (hour of day) heat map of typical temperature. [values] is row-major by day;
 * a null cell (no readings) is drawn as an outlined empty cell. Colours are diverging: [coldC] and
 * below is fully cold, [neutralC] is the neutral midpoint (comfort), [warmC] and above fully warm.
 * Pair with [HeatmapLegend]. Static; one accessibility element described by [contentDescription].
 */
@Composable
fun HourWeekHeatmap(
    values: List<List<Double?>>,
    coldC: Double,
    neutralC: Double,
    warmC: Double,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val palette = MaterialTheme.colorScheme.heatmapPalette
    val outline = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()

    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val dayLayouts = DayLabels.map { measurer.measure(it, labelStyle) }
        val hourLayouts = HourLabels.map { (h, text) -> h to measurer.measure(text, labelStyle) }
        val gutter = dayLayouts.maxOf { it.size.width } + GoodHiveSpacing.sm.toPx()
        val labelH = dayLayouts.first().size.height.toFloat()
        val gap = GoodHiveSpacing.xxs.toPx()
        val plotW = (size.width - gutter).coerceAtLeast(1f)
        val plotH = (size.height - labelH - GoodHiveSpacing.xs.toPx()).coerceAtLeast(1f)
        val radius = CornerRadius(ChartCellCorner.toPx())
        val stroke = Stroke(width = 1.dp.toPx())

        for (row in 0 until Days) {
            val rowValues = values.getOrNull(row)
            dayLayouts[row].let { layout ->
                val cell = heatmapCell(row, 0, Days, Hours, plotW, plotH, gap)
                drawText(layout, topLeft = Offset(0f, cell.top + (cell.height - layout.size.height) / 2f))
            }
            for (col in 0 until Hours) {
                val cell = heatmapCell(row, col, Days, Hours, plotW, plotH, gap)
                val topLeft = Offset(gutter + cell.left, cell.top)
                val cellSize = Size(cell.width, cell.height)
                val v = rowValues?.getOrNull(col)
                if (v == null) {
                    // Inset by half the stroke so the outline stays inside the cell.
                    val inset = stroke.width / 2f
                    drawRoundRect(
                        outline,
                        Offset(topLeft.x + inset, topLeft.y + inset),
                        Size((cellSize.width - stroke.width).coerceAtLeast(1f), (cellSize.height - stroke.width).coerceAtLeast(1f)),
                        radius,
                        stroke,
                    )
                } else {
                    drawRoundRect(palette.heatmapColor(v, coldC, neutralC, warmC), topLeft, cellSize, radius)
                }
            }
        }

        hourLayouts.forEach { (hour, layout) ->
            val cell = heatmapCell(0, hour, Days, Hours, plotW, plotH, gap)
            val left = (gutter + cell.left).coerceAtMost(size.width - layout.size.width)
            drawText(layout, topLeft = Offset(left, plotH + GoodHiveSpacing.xs.toPx()))
        }
    }
}

/**
 * Gradient bar (cold -> neutral -> warm) with end labels, for [HourWeekHeatmap]. Uses the same
 * palette as the cells. [coldLabel]/[warmLabel] default to the anchor temperatures.
 */
@Composable
fun HeatmapLegend(
    coldC: Double,
    warmC: Double,
    modifier: Modifier = Modifier,
    coldLabel: String = "${coldC.roundToInt()}° or colder",
    warmLabel: String = "${warmC.roundToInt()}° or warmer",
) {
    val palette = MaterialTheme.colorScheme.heatmapPalette
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
    ) {
        Text(coldLabel, style = style, color = color)
        Box(
            Modifier
                .weight(1f)
                .height(GoodHiveSpacing.md)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(Brush.horizontalGradient(listOf(palette.cold, palette.neutral, palette.warm))),
        )
        Text(warmLabel, style = style, color = color)
    }
}

// --- Previews -----------------------------------------------------------------------------

/** Typical UK October: ~16 overnight, warm mornings/evenings, warmer all day at weekends, a few gaps. */
private fun sampleHeatmap(): List<List<Double?>> = List(Days) { day ->
    List(Hours) { hour ->
        val weekend = day >= 5
        val base = when (hour) {
            in 0..5 -> 16.0
            in 6..8 -> if (weekend) 17.5 else 19.5
            in 9..15 -> if (weekend) 20.0 else 17.5
            in 16..22 -> 20.5
            else -> 17.5
        }
        if (day == 3 && hour in 2..4) null else base + ((day * 7 + hour * 3) % 5 - 2) * 0.25
    }
}

@Composable
private fun HeatmapPreviewContent() {
    Surface {
        Column(Modifier.padding(GoodHiveSpacing.lg), verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
            HourWeekHeatmap(
                values = sampleHeatmap(),
                coldC = 15.0,
                neutralC = 18.5,
                warmC = 22.0,
                contentDescription = "Typical temperature by hour and weekday",
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
            HeatmapLegend(coldC = 15.0, warmC = 22.0)
        }
    }
}

@Preview(name = "Heatmap light", showBackground = true)
@Composable
private fun HeatmapLightPreview() {
    GoodHiveTheme(darkTheme = false) { HeatmapPreviewContent() }
}

@Preview(name = "Heatmap dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun HeatmapDarkPreview() {
    GoodHiveTheme(darkTheme = true) { HeatmapPreviewContent() }
}
