package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.components.themed.DefaultDialog
import com.surenjanath.crownfoundry.ui.components.themed.DialogTextButton
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.center
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

const val GameOverDialogTag = "gameOverDialog"
const val RematchButtonTag = "rematchButton"

@Composable
fun GameOverDialog(
    winner: String?,
    counts: PieceCounts,
    onRematch: () -> Unit,
    onDismiss: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    val title = when (winner) {
        Side.HUMAN -> "You win"
        Side.AI -> "The machine wins"
        Side.DRAW -> "A draw"
        else -> "Game over"
    }

    DefaultDialog(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(GameOverDialogTag)
    ) {
        Image(
            painter = painterResource(
                if (winner == Side.HUMAN) R.drawable.crown else R.drawable.brain
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.accent),
            modifier = Modifier
                .padding(top = 8.dp)
                .size(32.dp)
        )

        BasicText(
            text = title,
            style = typography.l.semiBold.center,
            modifier = Modifier.padding(top = 12.dp)
        )

        BasicText(
            text = when (winner) {
                Side.HUMAN -> "It ran out of pieces to move."
                Side.AI -> "It closed the position and you ran out of moves."
                Side.DRAW -> "Neither side could force a win."
                else -> "The match is finished."
            },
            style = typography.xxs.medium.center.secondary,
            modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(top = 20.dp)
        ) {
            FinalTally("You", counts.black, counts.blackKings, colorPalette.accent)
            FinalTally("Opponent", counts.white, counts.whiteKings, colorPalette.text)
        }

        if (winner == Side.AI) {
            // The RL loop is the product. Say so, plainly, at the moment it matters.
            BasicText(
                text = "It has already replayed this game and adjusted its policy. The next one " +
                        "will be harder.",
                style = typography.xxs.medium.center.secondary,
                modifier = Modifier.padding(top = 20.dp, start = 8.dp, end = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
        ) {
            DialogTextButton(text = "Done", onClick = onDismiss)

            DialogTextButton(
                text = "Rematch",
                primary = true,
                onClick = onRematch,
                modifier = Modifier.testTag(RematchButtonTag)
            )
        }
    }
}

@Composable
private fun FinalTally(label: String, total: Int, kings: Int, color: Color) {
    val (_, typography) = LocalAppearance.current

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color)
        )

        BasicText(
            text = "$total",
            style = typography.l.semiBold,
            modifier = Modifier.padding(top = 6.dp)
        )

        BasicText(
            text = if (kings == 0) label else "$label · $kings king${if (kings == 1) "" else "s"}",
            style = typography.xxs.medium.secondary
        )
    }
}