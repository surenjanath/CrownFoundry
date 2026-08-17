package com.surenjanath.crownfoundry.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.offline.EngineState
import com.surenjanath.crownfoundry.offline.EngineStatus
import com.surenjanath.crownfoundry.ui.components.themed.SecondaryTextButton
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

/**
 * The one place the player is told whether they can play without a connection.
 *
 * It leads with the answer rather than with the diagnostics, because "can I play right now?" is
 * the only question most people are asking. The version numbers are underneath for the people who
 * want them - and because a stale engine is a *fine* opponent, the card says so instead of
 * treating "out of date" as an error.
 */
@Composable
fun EngineCard(
    state: EngineState,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    val tint = when (state.status) {
        EngineStatus.Ready -> colorPalette.accent
        EngineStatus.Stale -> colorPalette.text
        EngineStatus.Missing, EngineStatus.Incompatible -> colorPalette.red
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colorPalette.background1)
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Image(
                painter = painterResource(
                    if (state.canPlayOffline) R.drawable.brain else R.drawable.arrow_up_circle
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(18.dp)
            )

            BasicText(
                text = state.headline,
                style = typography.xs.semiBold.color(tint),
                modifier = Modifier.weight(1f)
            )

            if (state.busy) {
                BasicText(text = "Updating…", style = typography.xxs.secondary)
            }
        }

        BasicText(text = state.detail, style = typography.xxs.secondary)

        if (state.pendingUploads > 0) {
            BasicText(
                text = "${state.pendingUploads} offline " +
                        (if (state.pendingUploads == 1) "game is" else "games are") +
                        " waiting to be sent to the referee, so it can train on them too.",
                style = typography.xxs.secondary
            )
        }

        state.message?.takeIf { state.status == EngineStatus.Incompatible }?.let {
            BasicText(text = it, style = typography.xxs.color(colorPalette.red))
        }

        if (state.needsUpdate && !state.busy) {
            SecondaryTextButton(
                text = if (state.status == EngineStatus.Missing) "Download engine" else "Update now",
                onClick = onUpdate
            )
        }
    }
}

/**
 * The badge on the board while a match is being refereed here rather than by the server.
 *
 * Small and unalarming on purpose: playing offline is a supported way to play, not a degraded one.
 * The player is told which opponent they are facing, and left alone.
 */
@Composable
fun OfflineBadge(
    versionLabel: String,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colorPalette.background2)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.brain),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.accent),
            modifier = Modifier.size(11.dp)
        )

        BasicText(
            text = "On-device $versionLabel",
            style = typography.xxs.medium.color(colorPalette.accent),
            maxLines = 1
        )
    }
}

/** A tiny status dot for rows that have no room for the whole card. */
@Composable
fun EngineDot(status: EngineStatus, modifier: Modifier = Modifier) {
    val (colorPalette) = LocalAppearance.current

    val color: Color = when (status) {
        EngineStatus.Ready -> colorPalette.accent
        EngineStatus.Stale -> colorPalette.textDisabled
        EngineStatus.Missing, EngineStatus.Incompatible -> colorPalette.red
    }

    Column(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
    ) {}
}
