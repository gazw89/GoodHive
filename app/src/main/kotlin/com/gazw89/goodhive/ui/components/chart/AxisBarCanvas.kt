package com.gazw89.goodhive.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

/** A labelled dashed horizontal reference line, e.g. a day target or an average. */
data class ChartReferenceLine(val value: Double, val label: String)

/** Plot rectangle inside the canvas (px): excludes the y-label gutter and x-label strip. */
internal data class PlotArea(val left: Float, val top: Float, val width: Float, val height: Float) {
    fun y(value: Double, range: ChartRange): Float = top + mapY(value, range, height)
}

/**
 * Shared chrome for the bar charts: recessive gridlines with y labels, a dashed labelled reference
 * line, sparse x labels (by bar index) and the single-element accessibility description. Bars are
 * painted by [drawBars] between the gridlines and the reference line. Static, theme roles only.
 */
@Composable
internal fun AxisBarCanvas(
    count: Int,
    axis: NiceAxis,
    reference: ChartReferenceLine?,
    xLabels: List<Pair<Int, String>>,
    yLabel: (Double) -> String,
    contentDescription: String,
    modifier: Modifier,
    drawBars: DrawScope.(PlotArea) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val gridColor = colors.outlineVariant
    val refColor = colors.onSurfaceVariant
    val refBackdrop = colors.surface.copy(alpha = 0.85f)
    val measurer = rememberTextMeasurer()

    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        val yLayouts = axis.ticks.map { it to measurer.measure(yLabel(it), labelStyle) }
        val gutter = (yLayouts.maxOfOrNull { it.second.size.width } ?: 0) + GoodHiveSpacing.sm.toPx()
        val labelH = measurer.measure("00", labelStyle).size.height.toFloat()
        val gap = GoodHiveSpacing.xs.toPx()
        val plot = PlotArea(
            left = gutter,
            top = labelH / 2f,
            width = (size.width - gutter).coerceAtLeast(1f),
            height = (size.height - labelH - gap - labelH / 2f).coerceAtLeast(1f),
        )

        yLayouts.forEach { (tick, layout) ->
            val y = plot.y(tick, axis.range)
            drawLine(gridColor, Offset(plot.left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            drawText(
                layout,
                topLeft = Offset(gutter - GoodHiveSpacing.sm.toPx() - layout.size.width, y - layout.size.height / 2f),
            )
        }

        drawBars(plot)

        if (reference != null && reference.value >= axis.range.min && reference.value <= axis.range.max) {
            val y = plot.y(reference.value, axis.range)
            drawLine(
                refColor,
                Offset(plot.left, y),
                Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
            val layout = measurer.measure(reference.label, labelStyle)
            val pad = GoodHiveSpacing.xs.toPx()
            val boxW = layout.size.width + pad * 2
            val boxH = layout.size.height.toFloat()
            val boxLeft = size.width - boxW
            val above = y - boxH - GoodHiveSpacing.xxs.toPx()
            val boxTop = if (above >= 0f) above else y + GoodHiveSpacing.xxs.toPx()
            drawRect(refBackdrop, Offset(boxLeft, boxTop), Size(boxW, boxH))
            drawText(layout, topLeft = Offset(boxLeft + pad, boxTop))
        }

        xLabels.forEach { (index, text) ->
            val layout = measurer.measure(text, labelStyle)
            val center = plot.left + barSlot(index, count, plot.width, 0f).centerX
            val left = (center - layout.size.width / 2f).coerceIn(plot.left, (size.width - layout.size.width).coerceAtLeast(plot.left))
            drawText(layout, topLeft = Offset(left, plot.top + plot.height + gap))
        }
    }
}
