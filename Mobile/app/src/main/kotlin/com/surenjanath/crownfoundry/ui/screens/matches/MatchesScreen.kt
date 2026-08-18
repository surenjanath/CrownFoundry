package com.surenjanath.crownfoundry.ui.screens.matches

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.api.MatchSummaryDto
import com.surenjanath.crownfoundry.ui.components.LocalMenuState
import com.surenjanath.crownfoundry.ui.components.ShimmerHost
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.components.themed.Menu
import com.surenjanath.crownfoundry.ui.components.themed.MenuEntry
import com.surenjanath.crownfoundry.ui.components.themed.SecondaryTextButton
import com.surenjanath.crownfoundry.ui.components.themed.TextPlaceholder
import com.surenjanath.crownfoundry.ui.items.ItemContainer
import com.surenjanath.crownfoundry.ui.items.ItemInfoContainer
import com.surenjanath.crownfoundry.ui.screens.home.rememberPlayerId
import com.surenjanath.crownfoundry.ui.styling.Dimensions
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.ui.styling.shimmer
import com.surenjanath.crownfoundry.utils.backendUrlKey
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.copyToClipboard
import com.surenjanath.crownfoundry.utils.defaultBackendUrl
import com.surenjanath.crownfoundry.utils.formatAsRelativeTime
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.rememberPreference
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

/**
 * Everything you have played, newest first, written from your side of the board.
 */
@Composable
fun MatchesScreen(
    onReviewMatch: (String) -> Unit,
    /** The id, and whether it is a game between two people - the board behaves differently. */
    onResumeMatch: (String, Boolean) -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    val playerId = rememberPlayerId()
    val backendUrl by rememberPreference(backendUrlKey, defaultBackendUrl)

    var attempt by remember { mutableIntStateOf(0) }
    val holder = remember(playerId) { MatchesStateHolder(Offline.api, playerId) }

    LaunchedEffect(playerId, backendUrl, attempt) {
        CrownFoundryClient.baseUrl = backendUrl
        holder.load()
    }

    val state = holder.state
    val lazyListState = rememberLazyListState()

    Box(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
    ) {
        LazyColumn(
            state = lazyListState,
            contentPadding = LocalWindowInsets.current
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                .asPaddingValues(),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header") {
                Header(title = "Matches") {
                    if (state.matches.isNotEmpty()) {
                        BasicText(
                            text = "${state.matches.size} played",
                            style = typography.s.secondary
                        )
                    }
                }
            }

            when {
                state.isLoading && state.matches.isEmpty() -> item(key = "loading") {
                    ShimmerHost {
                        repeat(6) { MatchRowPlaceholder() }
                    }
                }

                state.error != null -> item(key = "error") {
                    MatchesError(
                        error = state.error,
                        url = backendUrl,
                        onRetry = { attempt += 1 }
                    )
                }

                state.isEmpty -> item(key = "empty") {
                    BasicText(
                        text = "Nothing here yet. Play a game and it lands in this list the " +
                                "moment it ends - with the position at every ply and what the " +
                                "opponent was thinking when it moved.",
                        style = typography.xs.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                else -> items(
                    items = state.matches,
                    key = { it.matchId }
                ) { match ->
                    MatchRow(
                        match = match,
                        onReview = { onReviewMatch(match.matchId) },
                        onResume = {
                            onResumeMatch(match.matchId, isPassAndPlay(match.difficulty))
                        }
                    )
                }
            }

            item(key = "footer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MatchRow(
    match: MatchSummaryDto,
    onReview: () -> Unit,
    onResume: () -> Unit
) {
    val (_, typography) = LocalAppearance.current
    val menuState = LocalMenuState.current
    val context = LocalContext.current

    val outcome = outcomeOf(match)
    val inProgress = outcome == MatchOutcome.InProgress

    ItemContainer(
        alternative = false,
        thumbnailSizeDp = Dimensions.thumbnails.story,
        modifier = Modifier
            .combinedClickable(
                onLongClick = {
                    menuState.display {
                        Menu {
                            MenuEntry(
                                icon = R.drawable.time,
                                text = "Review ply by ply",
                                onClick = {
                                    menuState.hide()
                                    onReview()
                                }
                            )

                            if (inProgress) {
                                MenuEntry(
                                    icon = R.drawable.sync,
                                    text = "Resume this match",
                                    secondaryText = "Pick the board up where you left it",
                                    onClick = {
                                        menuState.hide()
                                        onResume()
                                    }
                                )
                            }

                            MenuEntry(
                                icon = R.drawable.link,
                                text = "Copy match id",
                                secondaryText = match.matchId,
                                onClick = {
                                    menuState.hide()
                                    context.copyToClipboard(match.matchId)
                                }
                            )
                        }
                    }
                },
                onClick = { if (inProgress) onResume() else onReview() }
            )
    ) { centeredModifier ->
        OutcomeBadge(
            outcome = outcome,
            modifier = centeredModifier
        )

        ItemInfoContainer {
            BasicText(
                text = matchTitle(match),
                style = typography.xs.semiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            BasicText(
                text = matchSubtitle(match),
                style = typography.xxs.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = formatAsRelativeTime(matchTimestampSeconds(match))
                        .ifEmpty { "no date recorded" },
                    style = typography.xxs.medium.secondary,
                    maxLines = 1
                )

                if (inProgress) {
                    SecondaryTextButton(
                        text = "Resume",
                        onClick = onResume
                    )
                }
            }
        }
    }
}

@Composable
private fun OutcomeBadge(
    outcome: MatchOutcome,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    val color = when (outcome) {
        MatchOutcome.Won -> colorPalette.accent
        MatchOutcome.Lost -> colorPalette.red
        MatchOutcome.Drawn -> colorPalette.textSecondary
        MatchOutcome.InProgress -> colorPalette.blue
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(LocalAppearance.current.thumbnailShape)
            .background(colorPalette.background1)
            .size(Dimensions.thumbnails.story)
    ) {
        BasicText(
            text = outcomeBadge(outcome),
            style = typography.xxs.semiBold.color(color),
            maxLines = 1
        )
    }
}

@Composable
private fun MatchRowPlaceholder() {
    ItemContainer(
        alternative = false,
        thumbnailSizeDp = Dimensions.thumbnails.story
    ) {
        Spacer(
            modifier = Modifier
                .background(
                    color = LocalAppearance.current.colorPalette.shimmer,
                    shape = LocalAppearance.current.thumbnailShape
                )
                .size(Dimensions.thumbnails.story)
        )

        ItemInfoContainer {
            TextPlaceholder()
            TextPlaceholder()
        }
    }
}

@Composable
private fun MatchesError(
    error: ApiError,
    url: String,
    onRetry: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
    ) {
        BasicText(
            text = when (error) {
                is ApiError.Unreachable -> "Nothing answered at ${error.url}, so there is no " +
                        "list to show."

                is ApiError.Timeout -> "The backend at $url took longer than ${error.seconds} " +
                        "seconds to send the list."

                else -> error.message
            },
            style = typography.xs.medium.color(colorPalette.red)
        )

        BasicText(
            text = "Your matches live on the backend. Start it, or check the address in " +
                    "Settings → Backend.",
            style = typography.xxs.secondary
        )

        SecondaryTextButton(
            text = "Try again",
            onClick = onRetry
        )
    }
}
