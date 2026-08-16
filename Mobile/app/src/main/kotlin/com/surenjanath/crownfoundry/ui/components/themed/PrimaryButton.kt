package com.surenjanath.crownfoundry.ui.components.themed

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.ui.styling.primaryButton

@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    @DrawableRes iconId: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val (colorPalette) = LocalAppearance.current

    Box(
        modifier = modifier
            .size(62.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colorPalette.primaryButton)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Image(
            painter = painterResource(iconId),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.text),
            modifier = Modifier
                .align(Alignment.Center)
                .size(20.dp)
        )
    }
}
