package com.gazw89.goodhive.ui.components.chart

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Horizontal slot of one bar: [left] and [width] in px, relative to the plot's left edge. */
data class BarSlot(val left: Float, val width: Float) {
    val centerX: Float get() = left + width / 2f
}

/**
 * Slot of bar [index] of [count] across [plotWidthPx]: the plot is split into equal slots and each
 * bar is inset by half of [gapPx] on both sides (so neighbouring bars are [gapPx] apart).
 */
fun barSlot(index: Int, count: Int, plotWidthPx: Float, gapPx: Float): BarSlot {
    if (count <= 0) return BarSlot(0f, 0f)
    val slot = plotWidthPx / count
    val width = max(slot - gapPx, 1f)
    return BarSlot(left = index * slot + (slot - width) / 2f, width = width)
}

/** A y axis whose [range] is aligned to [step] multiples, so its [ticks] sit exactly on the edges. */
data class NiceAxis(val range: ChartRange, val step: Double, val ticks: List<Double>)

/**
 * Smallest 1/2/5 x 10^n step for which the range covering [min]..[max] (expanded outward to step
 * multiples) needs at most [maxTicks] ticks. A zero-span input is widened to +-1 first.
 */
fun niceAxis(min: Double, max: Double, maxTicks: Int): NiceAxis {
    var lo = min
    var hi = max
    if (hi - lo < 1e-9) {
        lo -= 1.0
        hi += 1.0
    }
    val limit = max(maxTicks, 2)
    var power = 0.1
    while (true) {
        for (m in doubleArrayOf(1.0, 2.0, 5.0)) {
            val step = m * power
            val first = floor(lo / step + 1e-9) * step
            val last = ceil(hi / step - 1e-9) * step
            val count = ((last - first) / step).roundToInt() + 1
            if (count <= limit) {
                val ticks = (0 until count).map { first + it * step }
                return NiceAxis(ChartRange(first, last), step, ticks)
            }
        }
        power *= 10.0
    }
}

/** Y axis for daily low..high bars: data extent plus an optional reference value, on nice ticks. */
fun rangeBarAxis(lows: List<Double>, highs: List<Double>, reference: Double? = null, maxTicks: Int = 5): NiceAxis {
    val all = lows + highs + listOfNotNull(reference)
    if (all.isEmpty()) return niceAxis(DefaultYRange.min, DefaultYRange.max, maxTicks)
    return niceAxis(all.min(), all.max(), maxTicks)
}

/** Zero-based y axis for count/duration bars; the top is the nice tick at or above the max/reference. */
fun zeroBasedAxis(values: List<Double>, reference: Double? = null, maxTicks: Int = 5): NiceAxis {
    val top = (values + listOfNotNull(reference)).maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
    val axis = niceAxis(0.0, if (top <= 0.0) 1.0 else top, maxTicks)
    return axis.copy(range = ChartRange(0.0, axis.range.max), ticks = axis.ticks.filter { it >= 0.0 })
}

/** Rectangle of heatmap cell ([row], [col]) in px, with [gapPx] between cells. */
data class CellRect(val left: Float, val top: Float, val width: Float, val height: Float)

fun heatmapCell(
    row: Int,
    col: Int,
    rows: Int,
    cols: Int,
    widthPx: Float,
    heightPx: Float,
    gapPx: Float,
): CellRect {
    if (rows <= 0 || cols <= 0) return CellRect(0f, 0f, 0f, 0f)
    val cw = widthPx / cols
    val ch = heightPx / rows
    return CellRect(
        left = col * cw + gapPx / 2f,
        top = row * ch + gapPx / 2f,
        width = max(cw - gapPx, 1f),
        height = max(ch - gapPx, 1f),
    )
}
