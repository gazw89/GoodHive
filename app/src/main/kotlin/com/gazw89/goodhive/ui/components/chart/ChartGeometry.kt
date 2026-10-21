package com.gazw89.goodhive.ui.components.chart

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A data point in caller units (e.g. minutes since midnight, or epoch ms for x; degrees C for y). */
data class ChartPoint(val x: Double, val y: Double)

sealed interface ChartSeries {
    val points: List<ChartPoint>

    /** Points joined by straight segments (e.g. measured room temperature). */
    data class Line(override val points: List<ChartPoint>) : ChartSeries

    /**
     * Piecewise-constant series: y holds from a point's x until the next point's x. The caller
     * supplies a closing point whose y is ignored for drawing but whose x ends the last step.
     */
    data class Step(override val points: List<ChartPoint>) : ChartSeries
}

data class ChartRange(val min: Double, val max: Double) {
    val span: Double get() = max - min
}

val DefaultYRange = ChartRange(10.0, 25.0)

/** Y range covering all series, padded by [padding] and widened to at least [minSpan], centred on data. */
fun yRangeFor(series: List<ChartSeries>, padding: Double = 1.0, minSpan: Double = 4.0): ChartRange {
    val ys = series.flatMap { s -> s.points.map { it.y } }
    return paddedYRange(ys, padding, minSpan) ?: DefaultYRange
}

private fun paddedYRange(ys: List<Double>, padding: Double, minSpan: Double): ChartRange? {
    if (ys.isEmpty()) return null
    var lo = ys.min() - padding
    var hi = ys.max() + padding
    if (hi - lo < minSpan) {
        val mid = (lo + hi) / 2.0
        lo = mid - minSpan / 2.0
        hi = mid + minSpan / 2.0
    }
    return ChartRange(lo, hi)
}

/** Padding / minimum span used for the y axis while the x axis is zoomed (tighter than the full-day view). */
const val ZoomedYPadding = 0.5
const val ZoomedYMinSpan = 2.0

/**
 * Y range for a zoomed x [window]: only what is visible there drives the scale.
 *
 * - [ChartSeries.Step]: the step in force at the window's left edge (the last point at or before
 *   it) plus every step that starts inside the window. The closing point's y is ignored, as when drawing.
 * - [ChartSeries.Line]: readings inside the window plus the value of a drawn segment where it crosses
 *   a window edge (interpolated along the segment that is actually drawn, so nothing is invented and
 *   the line is not clipped misleadingly).
 *
 * When nothing falls in the window the range for the whole series is returned so the scale stays
 * stable (and [DefaultYRange] when every series is empty).
 */
fun yRangeForWindow(
    series: List<ChartSeries>,
    window: ChartRange,
    padding: Double = ZoomedYPadding,
    minSpan: Double = ZoomedYMinSpan,
): ChartRange {
    val ys = ArrayList<Double>()
    series.forEach { s ->
        when (s) {
            is ChartSeries.Step -> collectStepYs(s.points, window, ys)
            is ChartSeries.Line -> collectLineYs(s.points, window, ys)
        }
    }
    return paddedYRange(ys, padding, minSpan) ?: yRangeFor(series)
}

private fun collectStepYs(pts: List<ChartPoint>, window: ChartRange, out: MutableList<Double>) {
    // Last point is the closing point: it only ends the final step.
    for (i in 0 until pts.lastIndex) {
        val p = pts[i]
        val next = pts[i + 1]
        // Step i is in force over [p.x, next.x); include it when that overlaps the window.
        if (p.x < window.max && next.x > window.min) out.add(p.y)
    }
}

private fun collectLineYs(pts: List<ChartPoint>, window: ChartRange, out: MutableList<Double>) {
    pts.forEach { p -> if (p.x >= window.min && p.x <= window.max) out.add(p.y) }
    for (i in 0 until pts.lastIndex) {
        val a = pts[i]
        val b = pts[i + 1]
        if (b.x <= a.x) continue
        if (a.x < window.min && b.x > window.min) out.add(lerpY(a, b, window.min))
        if (a.x < window.max && b.x > window.max) out.add(lerpY(a, b, window.max))
    }
}

private fun lerpY(a: ChartPoint, b: ChartPoint, x: Double): Double =
    a.y + (b.y - a.y) * ((x - a.x) / (b.x - a.x))

/** Maps [x] within [domain] to 0..[widthPx]. A degenerate domain maps to 0. */
fun mapX(x: Double, domain: ChartRange, widthPx: Float): Float {
    if (domain.span <= 0.0) return 0f
    return (((x - domain.min) / domain.span) * widthPx).toFloat()
}

/** Maps [y] within [range] to pixels with y inverted (range.max at 0, range.min at [heightPx]). */
fun mapY(y: Double, range: ChartRange, heightPx: Float): Float {
    if (range.span <= 0.0) return heightPx
    return (((range.max - y) / range.span) * heightPx).toFloat()
}

/**
 * Tick values inside [range], at most [maxTicks], on a 1/2/5 x 10^n step (whole numbers). With [fine]
 * a 0.5 step is also considered, for tightly zoomed ranges.
 */
fun niceTicks(range: ChartRange, maxTicks: Int, fine: Boolean = false): List<Double> {
    if (maxTicks < 1 || range.span <= 0.0) return emptyList()
    fun fits(candidate: Double): Boolean {
        val first = ceil(range.min / candidate) * candidate
        val count = floor((range.max - first) / candidate + 1e-9).toInt() + 1
        return count <= maxTicks
    }
    val multipliers = doubleArrayOf(1.0, 2.0, 5.0)
    var step = if (fine && fits(0.5)) 0.5 else 0.0
    var power = 1.0
    while (step == 0.0) {
        for (m in multipliers) {
            val candidate = m * power
            if (fits(candidate)) {
                step = candidate
                break
            }
        }
        power *= 10.0
    }
    val out = ArrayList<Double>()
    var v = ceil(range.min / step) * step
    while (v <= range.max + 1e-9) {
        out.add(v)
        v += step
    }
    return out
}

internal fun clampX(x: Double, domain: ChartRange): Double = min(max(x, domain.min), domain.max)
