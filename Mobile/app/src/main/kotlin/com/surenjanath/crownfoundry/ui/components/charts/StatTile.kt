package com.surenjanath.crownfoundry.ui.components.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

/**
 * One number with its name under it. The number is what the reader came for, so it is the large
 * type and the accent is spent on it only when it is the number the screen is about.
 */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    accented: Boolean = false
) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .background(color = colorPalette.background1, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .widthIn(min = 92.dp)
    ) {
        BasicText(
            text = value,
            style = typography.l.semiBold.color(
                if (accented) colorPalette.accent else colorPalette.text
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        BasicText(
            text = label,
            style = typography.xxs.medium.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        detail?.let {
            BasicText(
                text = it,
                style = typography.xxs.medium.color(colorPalette.textDisabled),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Which colour is whose. Without it a two-series chart is a decoration. */
@Composable
fun ChartLegend(
    entries: List<Pair<String, Color>>,
    modifier: Modifier = Modifier
) {
    val (_, typography) = LocalAppearance.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        entries.forEach { (label, color) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(
                    modifier = Modifier
                        .background(color = color, shape = CircleShape)
                        .size(8.dp)
                )

                BasicText(
                    text = label,
                    style = typography.xxs.medium.secondary,
                    maxLines = 1
                )
            }
        }
    }
}
