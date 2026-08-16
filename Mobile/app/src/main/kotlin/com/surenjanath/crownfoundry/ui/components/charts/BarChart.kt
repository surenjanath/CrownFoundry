package com.surenjanath.crownfoundry.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.medium
import kotlin.math.abs
import kotlin.math.min

/** One match's worth of a paired bar chart: what the AI took, what you took. */
@Immutable
data class BarGroup(
    val primary: Float,
    val secondary: Float
)

/**
 * Paired bars sharing a zero baseline, so a negative value grows downwards instead of vanishing.
 * Bars thin out as matches accumulate; below one pixel they stop thinning and start overlapping,
 * which is the honest failure mode for a chart that has outgrown its width.
 */
@Composable
fun BarChart(
    groups: List<BarGroup>,
    primaryColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 168.dp,
    tickCount: Int = 3,
    tickDecimals: Int = 0,
    startLabel: String? = null,
    endLabel: String? = null
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
        val values = groups.flatMap { listOf(it.primary, it.secondary) }
        val axisRange = rangeOf(values).including(0f).padded()
        val ticks = niceTicks(axisRange, tickCount)
        val tickLabels = ticks.map { formatTick(it, tickDecimals) }

        val gutter = tickLabels.maxOfOrNull {
            textMeasurer.measure(AnnotatedString(it), labelStyle, maxLines = 1).size.width.toFloat()
        } ?: 0f

        val hasFootLabels = startLabel != null || endLabel != null
        val footHeight = if (hasFootLabels) {
            textMeasurer.measure(AnnotatedString("0"), labelStyle, maxLines = 1)
                .size.height.toFloat() + 4.dp.toPx()
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

            val layout = textMeasurer.measure(
                AnnotatedString(tickLabels[index]),
                labelStyle,
                maxLines = 1
            )

            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    x = gutter - layout.size.width,
                    y = (y - layout.size.height / 2f).coerceIn(0f, size.height - layout.size.height)
                )
            )
        }

        if (groups.isNotEmpty()) {
            val baseline = yFor(0f, axisRange, top, bottom)
            val slot = (right - left) / groups.size
            val barWidth = min(slot * 0.38f, 9.dp.toPx()).coerceAtLeast(1f)

            groups.forEachIndexed { index, group ->
                val centre = left + slot * (index + 0.5f)

                drawBar(
                    value = group.primary,
                    range = axisRange,
                    top = top,
                    bottom = bottom,
                    baseline = baseline,
                    x = centre - barWidth * 0.55f - barWidth / 2f,
                    width = barWidth,
                    color = primaryColor
                )

                drawBar(
                    value = group.secondary,
                    range = axisRange,
                    top = top,
                    bottom = bottom,
                    baseline = baseline,
                    x = centre + barWidth * 0.55f - barWidth / 2f,
                    width = barWidth,
                    color = secondaryColor
                )
            }

            drawLine(
                color = colorPalette.textDisabled,
                start = Offset(left, baseline),
                end = Offset(right, baseline),
                strokeWidth = 1.dp.toPx()
            )
        }

        if (hasFootLabels) {
            val y = size.height - footHeight + 2.dp.toPx()

            startLabel?.let {
                drawText(
                    textLayoutResult = textMeasurer.measure(
                        AnnotatedString(it),
                        labelStyle,
                        maxLines = 1
                    ),
                    topLeft = Offset(left, y)
                )
            }

            endLabel?.let {
                val layout = textMeasurer.measure(AnnotatedString(it), labelStyle, maxLines = 1)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(right - layout.size.width, y)
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBar(
    value: Float,
    range: ValueRange,
    top: Float,
    bottom: Float,
    baseline: Float,
    x: Float,
    width: Float,
    color: Color
) {
    if (!value.isFinite()) return

    val y = yFor(value, range, top, bottom)
    // A zero-height bar would be invisible; a hairline says "this match, nothing taken".
    val barHeight = abs(y - baseline).coerceAtLeast(1f)

    drawRect(
        color = color,
        topLeft = Offset(x, min(y, baseline)),
        size = Size(width, barHeight)
    )
}
