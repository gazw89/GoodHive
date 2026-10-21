package com.gazw89.goodhive.ui.components.chart

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gazw89.goodhive.ui.theme.ChartBarCorner
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.GoodHiveTheme
import kotlin.math.max
import kotlin.math.roundToInt

/** One day of a [RangeBarChart]. [dimmed] marks days with missing readings (drawn at reduced alpha). */
data class RangeBarDatum(
    val low: Double,
    val high: Double,
    val average: Double? = null,
    val dimmed: Boolean = false,
)

/** One bar of a zero-based [BarChart]. */
data class BarDatum(val value: Double, val dimmed: Boolean = false)

private const val DimAlpha = 0.4f
private const val MaxYTicks = 5

private fun degreesLabel(v: Double) = "${v.roundToInt()}°"
private fun plainLabel(v: Double) = "${v.roundToInt()}"

/**
 * Floating daily range bars (low to high) with an average tick, optional dashed [reference] line,
 * y gridlines labelled via [yLabel] and sparse [xLabels] keyed by bar index. Static; described to
 * accessibility as one element by [contentDescription] (pair with [ChartTable] for "Show as table").
 */
@Composable
fun RangeBarChart(
    bars: List<RangeBarDatum>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    reference: ChartReferenceLine? = null,
    xLabels: List<Pair<Int, String>> = emptyList(),
    yLabel: (Double) -> String = ::degreesLabel,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.surface
    val axis = rangeBarAxis(bars.map { it.low }, bars.map { it.high }, reference?.value, MaxYTicks)

    AxisBarCanvas(
        count = bars.size,
        axis = axis,
        reference = reference,
        xLabels = xLabels,
        yLabel = yLabel,
        contentDescription = contentDescription,
        modifier = modifier,
    ) { plot ->
        val corner = ChartBarCorner.toPx()
        val gap = GoodHiveSpacing.xxs.toPx()
        val tickH = 2.dp.toPx()
        bars.forEachIndexed { i, bar ->
            val slot = barSlot(i, bars.size, plot.width, gap)
            val yHigh = plot.y(bar.high, axis.range)
            val yLow = plot.y(bar.low, axis.range)
            // A day with low == high would be a sliver; keep at least two corner radii of body.
            val h = max(yLow - yHigh, corner * 2f)
            val color = if (bar.dimmed) barColor.copy(alpha = DimAlpha) else barColor
            drawRoundRect(
                color = color,
                topLeft = Offset(plot.left + slot.left, yHigh),
                size = androidx.compose.ui.geometry.Size(slot.width, h),
                cornerRadius = CornerRadius(minOf(corner, slot.width / 2f)),
            )
            val avg = bar.average
            if (avg != null && avg >= bar.low && avg <= bar.high) {
                val y = (plot.y(avg, axis.range)).coerceIn(yHigh + tickH / 2f, yHigh + h - tickH / 2f)
                drawLine(
                    color = tickColor,
                    start = Offset(plot.left + slot.left, y),
                    end = Offset(plot.left + slot.left + slot.width, y),
                    strokeWidth = tickH,
                )
            }
        }
    }
}

/**
 * Zero-based vertical bars with rounded tops, optional dashed [reference] line (e.g. "Average 40
 * min"), y labels via [yLabel] and sparse [xLabels] keyed by bar index. Static, one a11y element.
 */
@Composable
fun BarChart(
    bars: List<BarDatum>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    reference: ChartReferenceLine? = null,
    xLabels: List<Pair<Int, String>> = emptyList(),
    yLabel: (Double) -> String = ::plainLabel,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val axis = zeroBasedAxis(bars.map { it.value }, reference?.value, MaxYTicks)

    AxisBarCanvas(
        count = bars.size,
        axis = axis,
        reference = reference,
        xLabels = xLabels,
        yLabel = yLabel,
        contentDescription = contentDescription,
        modifier = modifier,
    ) { plot ->
        val corner = ChartBarCorner.toPx()
        val gap = GoodHiveSpacing.xxs.toPx()
        val baseline = plot.y(0.0, axis.range)
        bars.forEachIndexed { i, bar ->
            if (bar.value <= 0.0) return@forEachIndexed
            val slot = barSlot(i, bars.size, plot.width, gap)
            val top = plot.y(bar.value, axis.range)
            val r = CornerRadius(minOf(corner, slot.width / 2f, baseline - top))
            val left = plot.left + slot.left
            val path = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = left,
                        top = top,
                        right = left + slot.width,
                        bottom = baseline,
                        topLeftCornerRadius = r,
                        topRightCornerRadius = r,
                        bottomLeftCornerRadius = CornerRadius.Zero,
                        bottomRightCornerRadius = CornerRadius.Zero,
                    ),
                )
            }
            drawPath(path, if (bar.dimmed) barColor.copy(alpha = DimAlpha) else barColor)
        }
    }
}

/** [BarChart] preset for warm-up / run durations: y labels are whole minutes. */
@Composable
fun MinutesBarChart(
    minutes: List<BarDatum>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    reference: ChartReferenceLine? = null,
    xLabels: List<Pair<Int, String>> = emptyList(),
) = BarChart(minutes, contentDescription, modifier, reference, xLabels, yLabel = ::plainLabel)

// --- Previews -----------------------------------------------------------------------------

private val SampleLows = listOf(15.2, 15.8, 16.1, 15.5, 15.0, 16.4, 16.9, 16.2, 15.7, 15.4, 16.0, 16.6, 17.0, 16.3)
private val SampleHighs = listOf(19.4, 20.1, 20.6, 19.8, 19.2, 20.9, 21.0, 20.3, 19.9, 19.5, 20.4, 20.8, 21.1, 20.2)

private fun sampleRangeBars() = SampleLows.indices.map { i ->
    RangeBarDatum(
        low = SampleLows[i],
        high = SampleHighs[i],
        average = (SampleLows[i] + SampleHighs[i]) / 2.0 + 0.3,
        dimmed = i == 4 || i == 9,
    )
}

private val SampleMinutes = listOf(32.0, 41.0, 38.0, 55.0, 47.0, 29.0, 36.0, 44.0, 52.0, 40.0, 33.0, 39.0, 48.0, 35.0)

private val SampleXLabels = listOf(0 to "1 Oct", 6 to "7 Oct", 13 to "14 Oct")

@Composable
private fun RangeBarPreviewContent() {
    Surface {
        Box(Modifier.padding(GoodHiveSpacing.lg)) {
            RangeBarChart(
                bars = sampleRangeBars(),
                reference = ChartReferenceLine(19.0, "Day target 19°"),
                xLabels = SampleXLabels,
                contentDescription = "Daily low to high temperature, 1 to 14 October",
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }
    }
}

@Composable
private fun BarPreviewContent() {
    Surface {
        Box(Modifier.padding(GoodHiveSpacing.lg)) {
            MinutesBarChart(
                minutes = SampleMinutes.mapIndexed { i, v -> BarDatum(v, dimmed = i == 5) },
                reference = ChartReferenceLine(40.0, "Average 40 min"),
                xLabels = SampleXLabels,
                contentDescription = "Daily warm-up minutes, 1 to 14 October",
                modifier = Modifier.fillMaxWidth().height(200.dp),
            )
        }
    }
}

@Preview(name = "RangeBar light", showBackground = true)
@Composable
private fun RangeBarLightPreview() {
    GoodHiveTheme(darkTheme = false) { RangeBarPreviewContent() }
}

@Preview(name = "RangeBar dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun RangeBarDarkPreview() {
    GoodHiveTheme(darkTheme = true) { RangeBarPreviewContent() }
}

@Preview(name = "BarChart light", showBackground = true)
@Composable
private fun BarChartLightPreview() {
    GoodHiveTheme(darkTheme = false) { BarPreviewContent() }
}

@Preview(name = "BarChart dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun BarChartDarkPreview() {
    GoodHiveTheme(darkTheme = true) { BarPreviewContent() }
}
