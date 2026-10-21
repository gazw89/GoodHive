package com.gazw89.goodhive.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.GoodHiveTheme

/** A series plus how to paint it. [dimBeforeX] draws the part left of that x at reduced alpha. */
data class ChartSeriesStyle(
    val series: ChartSeries,
    val color: Color,
    val strokeWidth: Dp = 2.dp,
    val dashed: Boolean = false,
    val dimBeforeX: Double? = null,
)

private const val DimAlpha = 0.4f
private const val MaxYTicks = 5

/**
 * Domain-generic line/step chart. X is any numeric domain ([xDomain]); the y range is derived from
 * the series. Series colours come from the caller; chrome uses MaterialTheme roles only. Static
 * (no animation), exposed to accessibility as a single element described by [contentDescription].
 *
 * Zoom is opt-in: pass a [viewport] (a sub-window of [xDomain], minutes of a day) and
 * [onViewportChange] to enable pinch-zoom / pan / double-tap-reset on the horizontal axis. While the
 * viewport is the full domain the chart renders exactly as it does without zoom support (caller's
 * [xLabels], y range over all series, no y labels, no clipping). While zoomed, ticks adapt to the
 * visible span, the y range follows the visible data, y labels appear, and drawing is clipped to the plot.
 */
@Composable
fun TemperatureChart(
    xDomain: ChartRange,
    series: List<ChartSeriesStyle>,
    modifier: Modifier = Modifier,
    nowX: Double? = null,
    xLabels: List<Pair<Double, String>> = emptyList(),
    contentDescription: String,
    viewport: ChartViewport? = null,
    onViewportChange: ((ChartViewport) -> Unit)? = null,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val nowColor = MaterialTheme.colorScheme.onSurface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()

    val zoomed = viewport != null && !viewport.isFullDay
    val visible = if (viewport != null && zoomed) viewport.visibleRange else xDomain
    var widthPx by remember { mutableIntStateOf(0) }
    val xLabelWidthPx = remember(measurer, labelStyle) { measurer.measure("00:00", labelStyle).size.width }

    // Derived from the committed window only; keyed so a recomposition without a window change is free.
    val yRange = remember(series, visible, zoomed) {
        val all = series.map { it.series }
        if (zoomed) yRangeForWindow(all, visible) else yRangeFor(all)
    }
    val yTicks = remember(yRange, zoomed) { niceTicks(yRange, MaxYTicks, fine = zoomed) }
    val yTickLabels = remember(yTicks, zoomed) { if (zoomed) yTicks.map { it to yTickLabel(it) } else emptyList() }
    val axisLabels = remember(visible, zoomed, widthPx, xLabelWidthPx, xLabels) {
        if (zoomed) {
            adaptiveXTicks(visible, maxXTickCount(widthPx.toFloat(), xLabelWidthPx.toFloat()))
                .map { it.value to it.label }
        } else {
            xLabels
        }
    }

    // One pointerInput for the lifetime of the chart; it always reads the latest inputs.
    val latestViewport by rememberUpdatedState(viewport)
    val latestOnChange by rememberUpdatedState(onViewportChange)
    val zoomEnabled = viewport != null && onViewportChange != null
    val gestureModifier = if (zoomEnabled) {
        Modifier.pointerInput(Unit) {
            detectChartZoomGestures(
                currentViewport = { latestViewport ?: ChartViewport.full(xDomain) },
                onViewportChange = { latestOnChange?.invoke(it) },
            )
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { if (widthPx != it.width) widthPx = it.width }
            .then(gestureModifier)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val labelHeight = measurer.measure("00", labelStyle).size.height.toFloat()
        val labelGap = GoodHiveSpacing.xs.toPx()
        val plotH = (size.height - labelHeight - labelGap).coerceAtLeast(1f)
        val plotW = size.width

        yTicks.forEach { t ->
            val y = mapY(t, yRange, plotH)
            drawLine(gridColor, Offset(0f, y), Offset(plotW, y), strokeWidth = 1.dp.toPx())
        }
        yTickLabels.forEach { (value, text) ->
            val layout = measurer.measure(text, labelStyle)
            val top = (mapY(value, yRange, plotH) - layout.size.height).coerceAtLeast(0f)
            drawText(layout, topLeft = Offset(labelGap, top))
        }

        clipToPlotIf(zoomed, plotW, plotH) {
            series.forEach { style ->
                val path = buildPath(style.series, visible, yRange, plotW, plotH)
                val stroke = Stroke(
                    width = style.strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (style.dashed) {
                        PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                    } else {
                        null
                    },
                )
                // Isolated observed days/months still need a visible mark; a one-point path has no stroke.
                if (style.series.points.size == 1) {
                    val point = style.series.points.single()
                    drawCircle(style.color, radius = style.strokeWidth.toPx() * 1.5f,
                        center = Offset(mapX(point.x, visible, plotW), mapY(point.y, yRange, plotH)))
                }
                val dim = style.dimBeforeX
                if (dim == null) {
                    drawPath(path, style.color, style = stroke)
                } else {
                    val split = mapX(clampX(dim, visible), visible, plotW)
                    clipRect(left = 0f, top = 0f, right = split, bottom = plotH) {
                        drawPath(path, style.color.copy(alpha = style.color.alpha * DimAlpha), style = stroke)
                    }
                    clipRect(left = split, top = 0f, right = plotW, bottom = plotH) {
                        drawPath(path, style.color, style = stroke)
                    }
                }
            }
        }

        nowMarkerX(nowX, visible)?.let { now ->
            val x = mapX(now, visible, plotW)
            drawLine(nowColor, Offset(x, 0f), Offset(x, plotH), strokeWidth = 1.5.dp.toPx())
        }

        axisLabels.forEach { (value, text) ->
            val layout = measurer.measure(text, labelStyle)
            val cx = mapX(value, visible, plotW)
            val left = (cx - layout.size.width / 2f).coerceIn(0f, (plotW - layout.size.width).coerceAtLeast(0f))
            drawText(layout, topLeft = Offset(left, plotH + labelGap))
        }
    }
}

/** Clips to the plot rect only when [enabled], so the default (un-zoomed) drawing is unchanged. */
private inline fun DrawScope.clipToPlotIf(enabled: Boolean, w: Float, h: Float, crossinline block: DrawScope.() -> Unit) {
    if (enabled) clipRect(0f, 0f, w, h) { block() } else block()
}

private fun buildPath(
    series: ChartSeries,
    xDomain: ChartRange,
    yRange: ChartRange,
    w: Float,
    h: Float,
): Path {
    val path = Path()
    val pts = series.points
    if (pts.isEmpty()) return path
    when (series) {
        is ChartSeries.Line -> pts.forEachIndexed { i, p ->
            val px = mapX(p.x, xDomain, w)
            val py = mapY(p.y, yRange, h)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        is ChartSeries.Step -> pts.forEachIndexed { i, p ->
            val px = mapX(p.x, xDomain, w)
            val py = mapY(p.y, yRange, h)
            if (i == 0) {
                path.moveTo(px, py)
            } else {
                // Hold the previous y until this x, then jump (the closing point does not jump).
                path.lineTo(px, mapY(pts[i - 1].y, yRange, h))
                if (i < pts.lastIndex) path.lineTo(px, py)
            }
        }
    }
    return path
}

@Composable
private fun ChartPreviewContent() {
    val schedule = ChartSeries.Step(
        listOf(
            ChartPoint(0.0, 13.0),
            ChartPoint(390.0, 19.0),
            ChartPoint(1320.0, 13.0),
            ChartPoint(1440.0, 13.0),
        ),
    )
    val room = ChartSeries.Line(
        listOf(
            ChartPoint(0.0, 19.7),
            ChartPoint(200.0, 19.9),
            ChartPoint(400.0, 20.2),
            ChartPoint(680.0, 19.9),
        ),
    )
    Surface {
        Box(Modifier.padding(GoodHiveSpacing.lg)) {
            TemperatureChart(
                xDomain = ChartRange(0.0, 1440.0),
                series = listOf(
                    ChartSeriesStyle(schedule, MaterialTheme.colorScheme.primary, dimBeforeX = 680.0),
                    ChartSeriesStyle(room, MaterialTheme.colorScheme.tertiary, strokeWidth = 3.dp),
                ),
                nowX = 680.0,
                xLabels = listOf(0.0 to "00", 360.0 to "06", 720.0 to "12", 1080.0 to "18", 1440.0 to "24"),
                contentDescription = "Today's schedule and room temperature",
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun TemperatureChartLightPreview() {
    GoodHiveTheme(darkTheme = false) { ChartPreviewContent() }
}

@Preview(name = "Dark", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun TemperatureChartDarkPreview() {
    GoodHiveTheme(darkTheme = true) { ChartPreviewContent() }
}
