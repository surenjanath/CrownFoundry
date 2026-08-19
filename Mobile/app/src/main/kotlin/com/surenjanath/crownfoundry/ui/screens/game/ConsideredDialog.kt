package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.api.ScoredMoveDto
import com.surenjanath.crownfoundry.ui.components.themed.DefaultDialog
import com.surenjanath.crownfoundry.ui.components.themed.DialogTextButton
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.center
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

const val ConsideredDialogTag = "consideredDialog"

/**
 * The moves the opponent weighed, and what it thought each was worth.
 *
 * The app has always fetched this - every AI turn comes back with the shortlist attached - and has
 * always thrown it away, showing only the single number for the move it settled on. That is the
 * wrong half to keep. "It played 24-19" is a fact; "it played 24-19 over 23-18 by four hundredths"
 * is the thing that tells you whether the position was close, and this app exists to answer that
 * kind of question.
 *
 * The scores are the search's own units and are only comparable with each other, within this one
 * position - which is why they are drawn as a ranking with the gap shown, rather than as numbers
 * that look like they mean something on their own.
 */
@Composable
fun ConsideredDialog(
    considered: List<ScoredMoveDto>,
    played: String?,
    onDismiss: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    val ranked = considered.sortedByDescending { it.q }.take(5)
    val best = ranked.firstOrNull()?.q

    DefaultDialog(
        onDismiss = onDismiss,
        modifier = Modifier.testTag(ConsideredDialogTag)
    ) {
        BasicText(
            text = "What it considered",
            style = typography.s.semiBold.center,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        BasicText(
            text = "Its shortlist on the last turn, best first. The numbers only mean anything " +
                "against each other, in this one position.",
            style = typography.xxs.medium.center.secondary,
            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp, end = 8.dp)
        )

        ranked.forEach { move ->
            val chosen = move.notation == played
            val behind = best?.let { it - move.q } ?: 0.0

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (chosen) colorPalette.accent.copy(alpha = 0.14f)
                        else colorPalette.background0
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                BasicText(
                    text = move.notation,
                    style = if (chosen) {
                        typography.xs.semiBold.copy(color = colorPalette.accent)
                    } else {
                        typography.xs.medium
                    }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // The gap from the best move is the readable part; the raw score is not.
                    BasicText(
                        text = if (behind <= 0.0005) "its pick" else "-${"%.2f".format(behind)}",
                        style = typography.xxs.medium.secondary
                    )

                    Spacer(modifier = Modifier.width(2.dp))

                    BasicText(
                        text = "%.2f".format(move.q),
                        style = typography.xxs.semiBold.secondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        DialogTextButton(
            text = "Done",
            onClick = onDismiss,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
