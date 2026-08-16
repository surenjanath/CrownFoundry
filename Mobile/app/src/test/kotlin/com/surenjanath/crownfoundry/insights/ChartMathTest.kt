package com.surenjanath.crownfoundry.insights

import com.surenjanath.crownfoundry.ui.components.charts.ValueRange
import com.surenjanath.crownfoundry.ui.components.charts.cumulativeAverage
import com.surenjanath.crownfoundry.ui.components.charts.formatTick
import com.surenjanath.crownfoundry.ui.components.charts.fractionOf
import com.surenjanath.crownfoundry.ui.components.charts.including
import com.surenjanath.crownfoundry.ui.components.charts.niceTicks
import com.surenjanath.crownfoundry.ui.components.charts.padded
import com.surenjanath.crownfoundry.ui.components.charts.rangeOf
import com.surenjanath.crownfoundry.ui.components.charts.rangeOfAll
import com.surenjanath.crownfoundry.ui.components.charts.rollingAverage
import com.surenjanath.crownfoundry.ui.components.charts.xFor
import com.surenjanath.crownfoundry.ui.components.charts.yFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The states this data actually spends its early life in - empty, one point, ten identical points
 * - are the ones that divide by zero if nobody decided what they should do.
 */
class ChartMathTest {

    private val tolerance = 0.0001f

    // --- ranges -----------------------------------------------------------------------------

    @Test
    fun `an empty series gets a unit range rather than a zero-width one`() {
        val range = rangeOf(emptyList())

        assertEquals(0f, range.min, tolerance)
        assertEquals(1f, range.max, tolerance)
        assertTrue(range.span > 0f)
    }

    @Test
    fun `a single point gets a range it sits in the middle of`() {
        val range = rangeOf(listOf(7f))

        assertTrue(range.span > 0f)
        assertEquals(0.5f, fractionOf(7f, range), tolerance)
    }

    @Test
    fun `a flat series never divides by a zero span`() {
        val range = rangeOf(List(10) { 0.4f })

        assertTrue("a flat series must still have height", range.span > 0f)
        assertEquals(0.5f, fractionOf(0.4f, range), tolerance)
    }

    @Test
    fun `a flat series of zeroes is padded too`() {
        val range = rangeOf(List(4) { 0f })

        assertTrue(range.span > 0f)
        assertEquals(0.5f, fractionOf(0f, range), tolerance)
    }

    @Test
    fun `negative values are inside the range, not clipped to zero`() {
        val range = rangeOf(listOf(-4f, -1f, 2f))

        assertEquals(-4f, range.min, tolerance)
        assertEquals(2f, range.max, tolerance)
        assertEquals(0f, fractionOf(-4f, range), tolerance)
        assertEquals(1f, fractionOf(2f, range), tolerance)
    }

    @Test
    fun `an all-negative series keeps its own range`() {
        val range = rangeOf(listOf(-10f, -5f, -8f))

        assertEquals(-10f, range.min, tolerance)
        assertEquals(-5f, range.max, tolerance)
    }

    @Test
    fun `infinities and NaN are ignored rather than swallowing the axis`() {
        val range = rangeOf(listOf(1f, Float.NaN, 3f, Float.POSITIVE_INFINITY))

        assertEquals(1f, range.min, tolerance)
        assertEquals(3f, range.max, tolerance)
    }

    @Test
    fun `a range over several series covers all of them`() {
        val range = rangeOfAll(listOf(listOf(1f, 2f), listOf(-3f), emptyList()))

        assertEquals(-3f, range.min, tolerance)
        assertEquals(2f, range.max, tolerance)
    }

    @Test
    fun `including widens the range to hold the reference line`() {
        val range = rangeOf(listOf(0.6f, 0.8f)).including(0.5f)

        assertEquals(0.5f, range.min, tolerance)
        assertEquals(0.8f, range.max, tolerance)
    }

    @Test
    fun `including a value already inside changes nothing`() {
        val base = rangeOf(listOf(0f, 1f))

        assertEquals(base, base.including(0.5f))
    }

    @Test
    fun `padding a zero-width range leaves it alone instead of collapsing it`() {
        val flat = ValueRange(1f, 1f)

        assertEquals(flat, flat.padded())
    }

    // --- value to pixel ---------------------------------------------------------------------

    @Test
    fun `the top of the range maps to the top of the canvas`() {
        val range = ValueRange(0f, 100f)

        assertEquals(10f, yFor(100f, range, top = 10f, bottom = 210f), tolerance)
        assertEquals(210f, yFor(0f, range, top = 10f, bottom = 210f), tolerance)
        assertEquals(110f, yFor(50f, range, top = 10f, bottom = 210f), tolerance)
    }

    @Test
    fun `a zero-width range reads as the middle of the canvas`() {
        val range = ValueRange(5f, 5f)

        assertEquals(0.5f, fractionOf(5f, range), tolerance)
        assertEquals(100f, yFor(5f, range, top = 0f, bottom = 200f), tolerance)
    }

    @Test
    fun `a value outside the range is pinned to the edge`() {
        val range = ValueRange(0f, 1f)

        assertEquals(0f, fractionOf(-3f, range), tolerance)
        assertEquals(1f, fractionOf(9f, range), tolerance)
    }

    @Test
    fun `the fifty percent reference line lands halfway up a unit axis`() {
        val y = yFor(0.5f, ValueRange(0f, 1f), top = 0f, bottom = 160f)

        assertEquals(80f, y, tolerance)
    }

    @Test
    fun `NaN reads as the middle rather than poisoning the canvas`() {
        assertEquals(0.5f, fractionOf(Float.NaN, ValueRange(0f, 1f)), tolerance)
    }

    @Test
    fun `a single point is drawn in the middle, not on the left edge`() {
        assertEquals(50f, xFor(0, count = 1, left = 0f, right = 100f), tolerance)
        assertEquals(50f, xFor(0, count = 0, left = 0f, right = 100f), tolerance)
    }

    @Test
    fun `points spread from edge to edge`() {
        assertEquals(0f, xFor(0, count = 5, left = 0f, right = 100f), tolerance)
        assertEquals(50f, xFor(2, count = 5, left = 0f, right = 100f), tolerance)
        assertEquals(100f, xFor(4, count = 5, left = 0f, right = 100f), tolerance)
    }

    @Test
    fun `an index past the end is clamped rather than drawn off the canvas`() {
        assertEquals(100f, xFor(99, count = 5, left = 0f, right = 100f), tolerance)
        assertEquals(0f, xFor(-3, count = 5, left = 0f, right = 100f), tolerance)
    }

    // --- averages ---------------------------------------------------------------------------

    @Test
    fun `a rolling average over an empty series is empty`() {
        assertEquals(emptyList<Float>(), rollingAverage(emptyList(), 10))
        assertEquals(emptyList<Float>(), cumulativeAverage(emptyList()))
    }

    @Test
    fun `a window of one or less is the series itself`() {
        val values = listOf(1f, 0f, 1f)

        assertEquals(values, rollingAverage(values, 1))
        assertEquals(values, rollingAverage(values, 0))
        assertEquals(values, rollingAverage(values, -5))
    }

    @Test
    fun `the first points average over what exists so far`() {
        val rolling = rollingAverage(listOf(1f, 0f, 1f, 1f), window = 3)

        assertEquals(4, rolling.size)
        assertEquals(1f, rolling[0], tolerance)
        assertEquals(0.5f, rolling[1], tolerance)
        assertEquals(2f / 3f, rolling[2], tolerance)
        assertEquals(2f / 3f, rolling[3], tolerance)
    }

    @Test
    fun `a window wider than the series is the cumulative average`() {
        val values = listOf(1f, 0f, 1f, 0f, 1f)

        assertEquals(cumulativeAverage(values), rollingAverage(values, window = 50))
    }

    @Test
    fun `the cumulative average is the record so far`() {
        val cumulative = cumulativeAverage(listOf(0f, 0f, 1f, 1f))

        assertEquals(0f, cumulative[0], tolerance)
        assertEquals(0f, cumulative[1], tolerance)
        assertEquals(1f / 3f, cumulative[2], tolerance)
        assertEquals(0.5f, cumulative[3], tolerance)
    }

    @Test
    fun `rolling averages handle negatives`() {
        val rolling = rollingAverage(listOf(-2f, -4f), window = 2)

        assertEquals(-2f, rolling[0], tolerance)
        assertEquals(-3f, rolling[1], tolerance)
    }

    // --- ticks ------------------------------------------------------------------------------

    @Test
    fun `ticks land on round numbers inside the range`() {
        val ticks = niceTicks(ValueRange(0f, 100f), desired = 3)

        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it >= 0f && it <= 100f })
        assertEquals(listOf(0f, 50f, 100f), ticks)
    }

    @Test
    fun `a unit axis gets ticks a percentage label can use`() {
        val ticks = niceTicks(ValueRange(0f, 1f), desired = 3)

        assertTrue(ticks.contains(0f))
        assertTrue(ticks.contains(0.5f))
        assertTrue(ticks.contains(1f))
    }

    @Test
    fun `a zero-width range gives one tick and does not hang`() {
        assertEquals(listOf(3f), niceTicks(ValueRange(3f, 3f), desired = 3))
    }

    @Test
    fun `asking for no ticks gives none`() {
        assertEquals(emptyList<Float>(), niceTicks(ValueRange(0f, 10f), desired = 0))
    }

    @Test
    fun `a negative range gets negative ticks`() {
        val ticks = niceTicks(ValueRange(-10f, -2f), desired = 3)

        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.all { it >= -10f && it <= -2f })
        assertTrue(ticks.any { it < 0f })
    }

    @Test
    fun `a range straddling zero includes zero`() {
        val ticks = niceTicks(ValueRange(-4f, 4f), desired = 4)

        assertTrue(ticks.contains(0f))
    }

    @Test
    fun `tick counts stay in the same order as the ask`() {
        val ticks = niceTicks(ValueRange(0f, 37f), desired = 3)

        assertTrue("got ${ticks.size} ticks", ticks.size in 2..7)
    }

    @Test
    fun `tick labels are rounded and locale-independent`() {
        assertEquals("50", formatTick(50.4f, 0))
        assertEquals("0.50", formatTick(0.5f, 2))
        assertEquals("-3", formatTick(-3f, 0))
        assertEquals("", formatTick(Float.NaN, 0))
        assertNotEquals("0,50", formatTick(0.5f, 2))
    }
}
