package com.surenjanath.crownfoundry.ui.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A line with nothing around it - no axis, no labels, no numbers. For the training loss, where
 * the shape of the trace is the whole message and the absolute value means little to a reader.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    strokeWidth: Dp = 2.dp
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (values.isEmpty()) return@Canvas

        val inset = strokeWidth.toPx() * 2f
        if (size.width <= inset * 2 || size.height <= inset * 2) return@Canvas

        drawSeries(
            series = ChartSeries(
                values = values,
                color = color,
                filled = true,
                strokeWidth = strokeWidth
            ),
            range = rangeOf(values).padded(0.15f),
            left = inset,
            right = size.width - inset,
            top = inset,
            bottom = size.height - inset
        )
    }
}
