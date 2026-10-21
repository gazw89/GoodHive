package com.gazw89.goodhive.ui.components.chart

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Pure (Compose-free) maths for zooming / panning a day chart horizontally. Zoom is only ever a view
 * transform over data that is already loaded: the visible window is always inside the domain.
 */

const val MinutesPerDayD = 1440.0
val FullDayDomain = ChartRange(0.0, MinutesPerDayD)

/** Smallest visible span when zoomed in, in minutes. */
const val MinZoomSpanMinutes = 15.0

/** After a gesture ends, a window this close (as a fraction of the domain) to the full domain snaps to it. */
const val SnapToFullFraction = 0.02

private const val FloatEpsilon = 1e-9

/**
 * The visible x window `[start, end]` of a chart whose data spans [domain]. Invariants maintained by
 * every operation: `domain.min <= start < end <= domain.max`, and span <= the domain span. Fully
 * zoomed out is represented exactly as the domain (no residual offset).
 */
data class ChartViewport(
    val start: Double,
    val end: Double,
    val domain: ChartRange = FullDayDomain,
) {
    val span: Double get() = end - start
    val visibleRange: ChartRange get() = ChartRange(start, end)

    /** True when the whole domain is visible (the default, un-zoomed view). */
    val isFullDay: Boolean get() = start <= domain.min + FloatEpsilon && end >= domain.max - FloatEpsilon

    /** The domain value at [fraction] (0..1) across the visible window. */
    fun valueAt(fraction: Double): Double = start + fraction * span

    /**
     * Zoom by [scale] (> 1 zooms in) around the point at [focalFraction] of the window, then shift the
     * content by [panFraction] of the (new) window (positive moves content right). The domain value
     * under the focal point ends up under `focalFraction + panFraction`, which is exactly what a
     * two-finger pinch needs: the data under the fingers stays under the fingers. The result is clamped
     * to the domain with a span in `[minSpan, domain span]`.
     */
    fun transformed(
        scale: Double,
        focalFraction: Double,
        panFraction: Double = 0.0,
        minSpan: Double = MinZoomSpanMinutes,
    ): ChartViewport {
        if (!scale.isFinite() || scale <= 0.0 || !focalFraction.isFinite() || !panFraction.isFinite()) return this
        val f = focalFraction.coerceIn(0.0, 1.0)
        val focal = valueAt(f)
        val newSpan = (span / scale).coerceIn(min(minSpan, domain.span), domain.span)
        return clamped(focal - (f + panFraction) * newSpan, newSpan)
    }

    /** Zoom by [scale] around [focalFraction]; the focal value stays fixed (until a domain edge clamps it). */
    fun zoomed(scale: Double, focalFraction: Double, minSpan: Double = MinZoomSpanMinutes): ChartViewport =
        transformed(scale, focalFraction, 0.0, minSpan)

    /** Shift so content moves by [dxFraction] of the window (dragging right shows earlier times). */
    fun panned(dxFraction: Double): ChartViewport = transformed(1.0, 0.0, dxFraction)

    /** Snap to the full domain when within [fraction] of its span (call when a gesture ends). */
    fun settled(fraction: Double = SnapToFullFraction): ChartViewport =
        if (span >= domain.span * (1.0 - fraction)) reset() else this

    fun reset(): ChartViewport = full(domain)

    private fun clamped(newStart: Double, newSpan: Double): ChartViewport {
        if (newSpan >= domain.span - FloatEpsilon) return reset()
        val s = newStart.coerceIn(domain.min, domain.max - newSpan)
        return ChartViewport(s, s + newSpan, domain)
    }

    companion object {
        val FullDay = ChartViewport(FullDayDomain.min, FullDayDomain.max, FullDayDomain)
        fun full(domain: ChartRange): ChartViewport =
            if (domain == FullDayDomain) FullDay else ChartViewport(domain.min, domain.max, domain)
    }
}

/** The x of a "now" marker if it lies inside the visible [window], otherwise null. */
fun nowMarkerX(nowX: Double?, window: ChartRange): Double? =
    nowX?.takeIf { it >= window.min && it <= window.max }

// ---- gesture maths ---------------------------------------------------------------------------

/**
 * Applies one two-pointer transform step. Positions are in plot pixels; the previous and current
 * centroid x and finger spread (distance between the two pointers) come from the same two pointers.
 * The value under the previous centroid ends up under the current centroid.
 */
fun ChartViewport.pinched(
    prevCentroidX: Float,
    prevSpread: Float,
    centroidX: Float,
    spread: Float,
    widthPx: Float,
): ChartViewport {
    if (widthPx <= 0f || prevSpread <= 0f || spread <= 0f) return this
    return transformed(
        scale = (spread / prevSpread).toDouble(),
        focalFraction = (prevCentroidX / widthPx).toDouble(),
        panFraction = ((centroidX - prevCentroidX) / widthPx).toDouble(),
    )
}

/** Pan by a horizontal finger movement of [dxPx] (dragging right reveals earlier times). */
fun ChartViewport.draggedBy(dxPx: Float, widthPx: Float): ChartViewport =
    if (widthPx <= 0f) this else panned((dxPx / widthPx).toDouble())

enum class DragIntent { Undecided, Horizontal, Vertical }

/**
 * Classifies a single-pointer drag from its accumulated movement. Below [slopPx] nothing is decided;
 * beyond it, mostly-horizontal drags are [DragIntent.Horizontal] and everything else is
 * [DragIntent.Vertical] (left to the parent scroll container).
 */
fun classifyDrag(totalDx: Float, totalDy: Float, slopPx: Float): DragIntent = when {
    hypot(totalDx, totalDy) < slopPx -> DragIntent.Undecided
    abs(totalDx) > abs(totalDy) -> DragIntent.Horizontal
    else -> DragIntent.Vertical
}

/** True when a second tap lands soon enough and close enough to the previous one to be a double-tap. */
fun isDoubleTap(
    prevUpMillis: Long,
    prevX: Float,
    prevY: Float,
    upMillis: Long,
    x: Float,
    y: Float,
    timeoutMillis: Long,
    slopPx: Float,
): Boolean = upMillis - prevUpMillis in 0..timeoutMillis && hypot(x - prevX, y - prevY) <= slopPx

// ---- adaptive x axis -------------------------------------------------------------------------

/** A labelled tick on the x axis. */
data class AxisTick(val value: Double, val label: String)

/** Tick intervals (minutes) the zoomed day axis may use, finest first. */
val DayTickIntervals = listOf(5, 10, 15, 30, 60, 120, 180, 360)

/** Upper bound on zoomed x ticks regardless of width, to keep the axis calm. */
const val MaxDayTicks = 8

private const val FullDayTickInterval = 360

/** How many labels of [labelWidthPx] fit across [widthPx] with a gap of about half a label between them. */
fun maxXTickCount(widthPx: Float, labelWidthPx: Float): Int {
    if (widthPx <= 0f || labelWidthPx <= 0f) return 2
    return (widthPx / (labelWidthPx * 1.6f)).toInt().coerceIn(2, MaxDayTicks)
}

/**
 * Ticks for a visible window of the day (minutes since midnight).
 *
 * At the full day this is exactly the classic 00/06/12/18/24 axis. Zoomed, it picks the smallest
 * interval from [DayTickIntervals] that keeps the number of ticks within [maxTicks] (so labels don't
 * overlap), aligns ticks to multiples of that interval, and labels them HH:mm (24:00 at the end of
 * the day). If even the largest interval has too many ticks, the largest is used.
 */
fun adaptiveXTicks(visible: ChartRange, maxTicks: Int): List<AxisTick> {
    if (visible.span >= MinutesPerDayD - 1e-6) {
        return (0..MinutesPerDayD.toInt() step FullDayTickInterval).map { m ->
            AxisTick(m.toDouble(), if (m == 1440) "24" else "%02d".format(m / 60))
        }
    }
    val interval = DayTickIntervals.firstOrNull { tickCount(visible, it) <= maxTicks } ?: DayTickIntervals.last()
    val first = ceil(visible.min / interval - 1e-9).toLong()
    val last = floor(visible.max / interval + 1e-9).toLong()
    return (first..last).map { k ->
        val m = (k * interval).toInt()
        AxisTick(m.toDouble(), "%02d:%02d".format(m / 60, m % 60))
    }
}

private fun tickCount(visible: ChartRange, interval: Int): Int {
    val first = ceil(visible.min / interval - 1e-9)
    val last = floor(visible.max / interval + 1e-9)
    return max(0, (last - first).toInt() + 1)
}

/** "09:15" style label for a (possibly fractional) minute of the day, rounded to the nearest minute. */
fun minuteOfDayLabel(minutes: Double): String {
    val m = minutes.roundToInt().coerceIn(0, 1440)
    return "%02d:%02d".format(m / 60, m % 60)
}

/** Label for a y tick: whole numbers plain, otherwise one decimal. */
fun yTickLabel(value: Double): String =
    if (abs(value - Math.rint(value)) < 1e-9) Math.rint(value).toInt().toString() else String.format(Locale.UK, "%.1f", value)
