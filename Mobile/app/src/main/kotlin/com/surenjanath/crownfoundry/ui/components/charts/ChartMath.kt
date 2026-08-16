package com.surenjanath.crownfoundry.ui.components.charts

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The arithmetic every chart in this app runs on, deliberately kept free of Compose.
 *
 * A learning curve starts empty, spends its first match as a single point, and spends its first
 * ten looking perfectly flat. Those are the normal states of this data, not edge cases - so they
 * are decided once, here, where they can be tested, instead of inside five different draw calls.
 */

/** The span of values a chart's vertical axis covers. Never zero-width once built. */
data class ValueRange(val min: Float, val max: Float) {
    val span: Float get() = max - min
}

/**
 * The range that holds [values]. Empty gives a unit range, and a series that never moves gets
 * padding either side so it draws as a line through the middle rather than a divide by zero.
 */
fun rangeOf(values: List<Float>): ValueRange {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return ValueRange(0f, 1f)

    val low = finite.min()
    val high = finite.max()

    if (low == high) {
        val padding = if (low == 0f) 1f else abs(low) * 0.25f
        return ValueRange(low - padding, high + padding)
    }

    return ValueRange(low, high)
}

/** The range over several series at once - the axis they will share. */
fun rangeOfAll(series: List<List<Float>>): ValueRange = rangeOf(series.flatten())

/** Widens the range so [value] - a zero baseline, a 50% reference - is inside it. */
fun ValueRange.including(value: Float): ValueRange =
    if (!value.isFinite()) this else ValueRange(minOf(min, value), maxOf(max, value))

/** Breathing room above and below, so the extremes of a series are not drawn on the edge. */
fun ValueRange.padded(fraction: Float = 0.08f): ValueRange {
    val padding = span * fraction
    return if (padding <= 0f || !padding.isFinite()) this else
        ValueRange(min - padding, max + padding)
}

/**
 * Where [value] sits in [range], from 0 at the bottom to 1 at the top. A zero-width range - which
 * [rangeOf] will not produce, but a caller-supplied range can - reads as the middle, and anything
 * outside the range is pinned to the edge so a reference line cannot escape the canvas.
 */
fun fractionOf(value: Float, range: ValueRange): Float {
    val span = range.span
    if (span <= 0f || !span.isFinite() || !value.isFinite()) return 0.5f
    return ((value - range.min) / span).coerceIn(0f, 1f)
}

/** Vertical pixel for [value]; [top] is the smaller coordinate, as canvases count downwards. */
fun yFor(value: Float, range: ValueRange, top: Float, bottom: Float): Float =
    bottom - fractionOf(value, range) * (bottom - top)

/** Horizontal pixel for point [index] of [count]. One point sits in the middle, not at the edge. */
fun xFor(index: Int, count: Int, left: Float, right: Float): Float {
    if (count <= 1) return (left + right) / 2f
    val clamped = index.coerceIn(0, count - 1)
    return left + (right - left) * (clamped.toFloat() / (count - 1))
}

/**
 * The trailing mean over [window] points. The first points average over what exists rather than
 * being dropped, because the beginning of the curve is the part worth looking at.
 */
fun rollingAverage(values: List<Float>, window: Int): List<Float> {
    if (values.isEmpty()) return emptyList()
    if (window <= 1) return values.toList()

    return values.indices.map { index ->
        val from = maxOf(0, index - window + 1)
        var sum = 0f
        for (i in from..index) sum += values[i]
        sum / (index - from + 1)
    }
}

/** The running mean from the first point to each point - the AI's record so far, match by match. */
fun cumulativeAverage(values: List<Float>): List<Float> {
    if (values.isEmpty()) return emptyList()

    var sum = 0f
    return values.mapIndexed { index, value ->
        sum += value
        sum / (index + 1)
    }
}

/**
 * Round axis values inside [range] - steps of 1, 2 or 5 times a power of ten, roughly [desired]
 * of them.
 */
fun niceTicks(range: ValueRange, desired: Int = 3): List<Float> {
    if (desired <= 0) return emptyList()

    val span = range.span
    if (span <= 0f || !span.isFinite()) return listOf(range.min)

    val rough = span / desired
    val magnitude = 10.0.pow(floor(log10(rough.toDouble())))
    val normalised = rough / magnitude
    val step = magnitude * when {
        normalised <= 1.0 -> 1.0
        normalised <= 2.0 -> 2.0
        normalised <= 5.0 -> 5.0
        else -> 10.0
    }

    if (step <= 0.0 || !step.isFinite()) return listOf(range.min)

    val first = ceil(range.min / step) * step
    val ticks = mutableListOf<Float>()
    var tick = first
    // The epsilon keeps a tick that lands exactly on the top of the range from being dropped by
    // floating point drift.
    while (tick <= range.max + step * 1e-9 && ticks.size <= desired * 4) {
        ticks += tick.toFloat()
        tick += step
    }

    return ticks
}

/** Axis labels: as few decimals as the step between ticks actually needs. */
fun formatTick(value: Float, decimals: Int): String {
    if (!value.isFinite()) return ""
    if (decimals <= 0) return kotlin.math.round(value).toInt().toString()

    val factor = 10.0.pow(decimals)
    val rounded = kotlin.math.round(value * factor) / factor
    // Locale.ROOT: an axis reading "0,5" because the phone is set to French would be a bug.
    return String.format(java.util.Locale.ROOT, "%.${decimals}f", rounded)
}
