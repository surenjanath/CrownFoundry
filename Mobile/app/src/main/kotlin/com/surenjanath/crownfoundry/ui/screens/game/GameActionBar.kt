package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameActionBar(
    state: GameState,
    showReasoning: Boolean,
    onToggleReasoning: () -> Unit,
    onResign: () -> Unit,
    onMenu: () -> Unit,
    onRematch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorPalette.background1)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Left: Resign or Rematch
        if (state.isOver) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onRematch)
                    .background(colorPalette.accent)
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.undo),
                    contentDescription = "Rematch",
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(14.dp)
                )
                BasicText(
                    text = "Rematch",
                    style = typography.xs.semiBold.copy(color = Color.White)
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = onResign,
                        onLongClick = onMenu
                    )
                    .background(colorPalette.background2)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag(ResignButtonTag)
            ) {
                Image(
                    painter = painterResource(R.drawable.flag),
                    contentDescription = "Resign",
                    colorFilter = ColorFilter.tint(colorPalette.text),
                    modifier = Modifier.size(14.dp)
                )
                BasicText(
                    text = "Resign",
                    style = typography.xs.medium
                )
            }
        }

        // Center: Match status chip
        val statusText = when {
            state.phase == GamePhase.Loading -> "Setting up..."
            state.isOver -> when (state.winner) {
                com.surenjanath.crownfoundry.api.Side.HUMAN -> "Victory! 🎉"
                com.surenjanath.crownfoundry.api.Side.AI -> "AI Won"
                com.surenjanath.crownfoundry.api.Side.DRAW -> "Draw Game"
                else -> "Game Finished"
            }
            state.mustCapture -> "Capture is mandatory!"
            state.phase == GamePhase.Thinking -> "AI is analyzing..."
            state.sideToMove == com.surenjanath.crownfoundry.api.Side.HUMAN -> "Your turn"
            else -> "Opponent's turn"
        }

        BasicText(
            text = statusText,
            style = if (state.mustCapture) typography.xs.semiBold.copy(color = colorPalette.accent)
            else typography.xs.medium.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Right: Commentary Toggle & Menu
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Commentary speech toggle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleReasoning)
                    .background(
                        if (showReasoning) colorPalette.accent.copy(alpha = 0.2f)
                        else colorPalette.background2
                    )
            ) {
                Image(
                    painter = painterResource(R.drawable.chatbubble),
                    contentDescription = "Toggle commentary",
                    colorFilter = ColorFilter.tint(
                        if (showReasoning) colorPalette.accent else colorPalette.textSecondary
                    ),
                    modifier = Modifier.size(14.dp)
                )
            }

            // Options menu
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onMenu)
                    .background(colorPalette.background2)
            ) {
                Image(
                    painter = painterResource(R.drawable.ellipsis_horizontal),
                    contentDescription = "Menu",
                    colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
