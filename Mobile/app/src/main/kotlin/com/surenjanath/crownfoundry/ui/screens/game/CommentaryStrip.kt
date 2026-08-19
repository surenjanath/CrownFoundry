package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.surenjanath.crownfoundry.ui.components.ShimmerHost
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary

const val AiThinkingTag = "aiThinking"

/** How much of the opponent's sentence gets to be read without it moving anything. */
private const val COMMENTARY_LINES = 2

/**
 * What the opponent is thinking.
 *
 * Two lines, from the first frame, whatever is in them - a shimmer while the search runs, the
 * sentence it wrote afterwards, or nothing at all. Two rather than one because the opponent
 * explaining itself is the point of the whole app and most of what it writes did not fit in one;
 * fixed rather than growing because a slot that expanded when it found something to say would push
 * the board down the screen at the exact moment the player is looking at it.
 *
 * It is the only place the opponent's reasoning is shown, and the only place the screen says the
 * opponent is thinking; the action bar deliberately stays quiet about both.
 */
@Composable
fun CommentaryStrip(
    commentary: Commentary,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    when (commentary) {
        is Commentary.Thinking -> ShimmerHost(
            modifier = modifier
                .testTag(AiThinkingTag)
                .fillMaxWidth()
        ) {
            BasicText(
                text = "Analyzing next move…",
                style = typography.xxs.medium.copy(color = colorPalette.accent),
                minLines = COMMENTARY_LINES,
                maxLines = COMMENTARY_LINES
            )
        }

        is Commentary.Said -> BasicText(
            text = commentary.text,
            style = typography.xxs.medium.secondary,
            minLines = COMMENTARY_LINES,
            maxLines = COMMENTARY_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.fillMaxWidth()
        )

        // The empty case still draws its two lines of nothing, which is the whole point.
        is Commentary.Silent -> BasicText(
            text = " ",
            style = typography.xxs.medium.secondary,
            minLines = COMMENTARY_LINES,
            maxLines = COMMENTARY_LINES,
            modifier = modifier.fillMaxWidth()
        )
    }
}
