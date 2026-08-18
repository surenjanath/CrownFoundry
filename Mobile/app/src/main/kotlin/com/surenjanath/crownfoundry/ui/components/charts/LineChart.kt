package com.surenjanath.crownfoundry.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.medium

/** One line on a chart. [filled] shades the area under it, for the series that carries the point. */
@Immutable
data class ChartSeries(
    val values: List<Float>,
    val color: Color,
    val filled: Boolean = false,
    val strokeWidth: Dp = 2.dp,
    val dashed: Boolean = false
)

/** A horizontal line the reader is meant to compare against - 50%, or zero. */
@Immutable
data class ReferenceLine(
    val value: Float,
    val label: String
)

/**
 * A line chart drawn by hand: no chart library exists in this app and none is coming.
 *
 * [range] overrides the range derived from the data, for the charts where the axis means
 * something fixed - a win rate is always 0 to 1 whatever the numbers happen to be.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    range: ValueRange? = null,
    referenceLine: ReferenceLine? = null,
    height: Dp = 168.dp,
    tickCount: Int = 3,
    tickDecimals: Int = 0,
    tickFormatter: ((Float) -> String)? = null,
    startLabel: String? = null,
    endLabel: String? = null,
    /** Index into the first series to call out - the ply the reader is looking at. */
    marker: Int? = null
) {
    val (colorPalette, typography) = LocalAppearance.current
    val textMeasurer = rememberTextMeasurer()

    val labelStyle = typography.xxs.medium.color(colorPalette.textSecondary)
    val gridColor = colorPalette.background2

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val axisRange = resolveRange(series, range, referenceLine)
        val ticks = niceTicks(axisRange, tickCount)

        val tickLabels = ticks.map { tickFormatter?.invoke(it) ?: formatTick(it, tickDecimals) }
        val gutter = tickLabels.maxOfOrNull {
            textMeasurer.measure(AnnotatedString(it), labelStyle).size.width.toFloat()
        } ?: 0f

        val hasFootLabels = startLabel != null || endLabel != null
        val footHeight = if (hasFootLabels) {
            textMeasurer.measure(AnnotatedString("0"), labelStyle).size.height.toFloat() + 4.dp.toPx()
        } else {
            0f
        }

        val left = gutter + 6.dp.toPx()
        val right = size.width - 2.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - footHeight - 2.dp.toPx()

        if (right <= left || bottom <= top) return@Canvas

        ticks.forEachIndexed { index, tick ->
            val y = yFor(tick, axisRange, top, bottom)

            drawLine(
                color = gridColor,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1.dp.toPx()
            )

            val layout = textMeasurer.measure(AnnotatedString(tickLabels[index]), labelStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = gutter - layout.size.width,
                    y = (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                )
            )
        }

        referenceLine?.let { reference ->
            val y = yFor(reference.value, axisRange, top, bottom)

            drawLine(
                color = colorPalette.textDisabled,
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(4.dp.toPx(), 4.dp.toPx())
                )
            )

            if (reference.label.isNotEmpty()) {
                val layout = textMeasurer.measure(AnnotatedString(reference.label), labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = right - layout.size.width,
                        y = (y - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f)
                    )
                )
            }
        }

        series.forEach { drawSeries(it, axisRange, left, right, top, bottom) }

        marker?.let { index ->
            val values = series.firstOrNull()?.values?.filter { it.isFinite() }.orEmpty()
            val value = values.getOrNull(index)

            if (value != null) {
                val x = xFor(index, values.size, left, right)
                val y = yFor(value, axisRange, top, bottom)

                drawLine(
                    color = colorPalette.textDisabled,
                    start = Offset(x, top),
                    end = Offset(x, bottom),
                    strokeWidth = 1.dp.toPx()
                )

                drawCircle(color = colorPalette.accent, radius = 4.dp.toPx(), center = Offset(x, y))
            }
        }

        if (hasFootLabels) {
            val y = size.height - footHeight + 2.dp.toPx()

            startLabel?.let {
                drawText(
                    textLayoutResult = textMeasurer.measure(AnnotatedString(it), labelStyle),
                    topLeft = Offset(left, y)
                )
            }

            endLabel?.let {
                val layout = textMeasurer.measure(AnnotatedString(it), labelStyle)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(right - layout.size.width, y)
                )
            }
        }
    }
}

/**
 * The axis the whole chart shares: the caller's if they gave one, otherwise the data's own with a
 * little padding, always widened to keep the reference line visible.
 */
internal fun resolveRange(
    series: List<ChartSeries>,
    range: ValueRange?,
    referenceLine: ReferenceLine?
): ValueRange {
    val base = range ?: rangeOfAll(series.map { it.values }).padded()
    return referenceLine?.let { base.including(it.value) } ?: base
}

internal fun DrawScope.drawSeries(
    series: ChartSeries,
    range: ValueRange,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float
) {
    val values = series.values.filter { it.isFinite() }
    if (values.isEmpty()) return

    val points = values.mapIndexed { index, value ->
        Offset(
            x = xFor(index, values.size, left, right),
            y = yFor(value, range, top, bottom)
        )
    }

    // A single match is a dot, not a line: there is nothing yet to join it to.
    if (points.size == 1) {
        drawCircle(
            color = series.color,
            radius = series.strokeWidth.toPx() * 1.75f,
            center = points.first()
        )
        return
    }

    if (series.filled) {
        val area = Path().apply {
            moveTo(points.first().x, bottom)
            points.forEach { lineTo(it.x, it.y) }
            lineTo(points.last().x, bottom)
            close()
        }

        drawPath(path = area, color = series.color.copy(alpha = 0.12f))
    }

    val line = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }

    drawPath(
        path = line,
        color = series.color,
        style = Stroke(
            width = series.strokeWidth.toPx(),
            pathEffect = if (series.dashed) {
                PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
            } else {
                null
            }
        )
    )
}

private fun TextMeasurer.measure(text: AnnotatedString, style: TextStyle) =
    measure(text = text, style = style, maxLines = 1)
