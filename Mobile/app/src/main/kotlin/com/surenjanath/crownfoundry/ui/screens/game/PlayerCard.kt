package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold
import kotlinx.coroutines.delay

@Composable
fun PlayerCard(
    state: GameState,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current
    val counts = state.counts
    val passAndPlay = state.mode.isPassAndPlay
    val isMyTurn = !state.isOver && state.sideToMove == Side.HUMAN
    val capturedWhite = (12 - counts.white).coerceAtLeast(0)

    val cardBackground = if (isMyTurn) colorPalette.background1 else colorPalette.background0
    val cardBorder = if (isMyTurn) colorPalette.accent.copy(alpha = 0.35f) else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBackground)
            .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Left: Avatar + Player Label + Status + Captured Tray
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colorPalette.accent.copy(alpha = 0.15f))
                    .border(1.5.dp, if (isMyTurn) colorPalette.accent else Color.Transparent, CircleShape)
            ) {
                Image(
                    painter = painterResource(R.drawable.person),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.accent),
                    modifier = Modifier.size(16.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    BasicText(
                        text = if (passAndPlay) "Black" else "You",
                        style = typography.xs.semiBold
                    )

                    if (isMyTurn) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colorPalette.accent)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            BasicText(
                                text = when {
                                    state.mustCapture -> "Must Jump!"
                                    passAndPlay -> "Black to move"
                                    else -> "Your Move"
                                },
                                style = typography.xxs.semiBold.copy(color = Color.White)
                            )
                        }
                    }
                }

                if (capturedWhite > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        BasicText(text = "Taken:", style = typography.xxs.medium.secondary)
                        Spacer(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colorPalette.text)
                        )
                        BasicText(text = "$capturedWhite", style = typography.xxs.semiBold)
                    }
                }
            }
        }

        // Right: Turn Timer + Remaining Pieces Pill
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PlayerTurnTimer(isActive = isMyTurn)

            // Piece count pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorPalette.background2)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Spacer(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colorPalette.accent)
                )

                BasicText(
                    text = "${counts.black}",
                    style = typography.xs.semiBold
                )

                if (counts.blackKings > 0) {
                    Image(
                        painter = painterResource(R.drawable.crown),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.accent),
                        modifier = Modifier.size(10.dp)
                    )
                    BasicText(
                        text = "${counts.blackKings}",
                        style = typography.xxs.semiBold.copy(color = colorPalette.accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerTurnTimer(isActive: Boolean) {
    val (colorPalette, typography) = LocalAppearance.current
    var seconds by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            delay(1000)
            seconds++
        }
    }

    val minutes = seconds / 60
    val remSeconds = seconds % 60
    val timeText = "%02d:%02d".format(minutes, remSeconds)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colorPalette.background2)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.time),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.accent),
            modifier = Modifier.size(11.dp)
        )
        BasicText(
            text = timeText,
            style = typography.xxs.semiBold
        )
    }
}
