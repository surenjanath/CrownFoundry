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

/**
 * What the opponent is thinking, in one line.
 *
 * The line exists from the first frame and never changes height - only what fills it changes, from
 * nothing, to a shimmer while the search runs, to the sentence it wrote afterwards. A slot that
 * appeared when the opponent found something to say would push the board down the screen at the
 * exact moment the player is looking at it.
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
                maxLines = 1
            )
        }

        is Commentary.Said -> BasicText(
            text = commentary.text,
            style = typography.xxs.medium.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.fillMaxWidth()
        )

        // The empty case still draws a line's worth of nothing, which is the whole point.
        is Commentary.Silent -> BasicText(
            text = " ",
            style = typography.xxs.medium.secondary,
            maxLines = 1,
            modifier = modifier.fillMaxWidth()
        )
    }
}
