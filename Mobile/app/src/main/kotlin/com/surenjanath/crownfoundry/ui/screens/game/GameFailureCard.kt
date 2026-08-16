package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.ui.components.themed.SecondaryTextButton
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

const val GameFailureTag = "gameFailure"
const val RetryButtonTag = "retryButton"

/**
 * Every way the referee can let the screen down, drawn as a state on the page. A toast would put
 * the reason somewhere the player cannot go back and read.
 */
@Composable
fun GameFailureCard(
    failure: GameFailure,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    val error = failure.error

    val title = when (error) {
        is ApiError.Unreachable -> "The referee is not answering"
        is ApiError.IllegalMove -> "That move is not legal"
        is ApiError.BrainUnavailable -> "The opponent cannot think"
        is ApiError.Timeout -> "The opponent is taking too long"
        is ApiError.Malformed -> "The referee's answer made no sense"
        is ApiError.Rejected -> "The referee said no"
    }

    val detail = when (error) {
        is ApiError.Unreachable ->
            "Nothing is listening at ${error.url}. Start the Django server, or change the " +
                    "backend URL in Settings."

        is ApiError.IllegalMove ->
            "The board has been put back and now shows the moves the referee will accept."

        is ApiError.BrainUnavailable ->
            error.detail.ifEmpty { "Ollama or the policy network is down. The position is safe." }

        is ApiError.Timeout ->
            "It did not answer within ${error.seconds}s. The position is unchanged - ask it again."

        is ApiError.Malformed -> error.detail.ifEmpty { "The payload did not parse." }
        is ApiError.Rejected -> "${error.detail.ifEmpty { error.code }} (HTTP ${error.status})"
    }

    Row(
        modifier = modifier
            .testTag(GameFailureTag)
            .clip(RoundedCornerShape(12.dp))
            .background(colorPalette.background1)
            .height(IntrinsicSize.Min)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colorPalette.red)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            BasicText(text = title, style = typography.xs.semiBold)
            BasicText(text = detail, style = typography.xxs.medium.secondary)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failure.canRetry) {
                    SecondaryTextButton(
                        text = "Try again",
                        onClick = onRetry,
                        modifier = Modifier.testTag(RetryButtonTag)
                    )
                }

                SecondaryTextButton(
                    text = "Dismiss",
                    onClick = onDismiss,
                    alternative = true
                )
            }
        }
    }
}
