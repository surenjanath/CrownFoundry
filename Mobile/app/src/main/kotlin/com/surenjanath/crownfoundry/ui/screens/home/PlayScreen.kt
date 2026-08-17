package com.surenjanath.crownfoundry.ui.screens.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.AnalyticsSummaryDto
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.api.HealthDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.enums.Difficulty
import com.surenjanath.crownfoundry.ui.components.EngineCard
import com.surenjanath.crownfoundry.ui.components.ShimmerHost
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.components.themed.PrimaryButton
import com.surenjanath.crownfoundry.ui.components.themed.SecondaryButton
import com.surenjanath.crownfoundry.ui.components.themed.SecondaryTextButton
import com.surenjanath.crownfoundry.ui.components.themed.TextPlaceholder
import com.surenjanath.crownfoundry.ui.screens.insights.aiArcReading
import com.surenjanath.crownfoundry.ui.screens.insights.percent
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.activeMatchIdKey
import com.surenjanath.crownfoundry.utils.backendUrlKey
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.defaultBackendUrl
import com.surenjanath.crownfoundry.utils.difficultyKey
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.playerIdKey
import com.surenjanath.crownfoundry.utils.preferences
import com.surenjanath.crownfoundry.utils.rememberPreference
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The front door: start a game, or pick up the one you walked away from, and see who you are
 * about to play - because the opponent is different every time you come back to it.
 */
@Composable
fun PlayScreen(
    onPlay: (String?) -> Unit,
    onSeeInsights: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    var difficulty by rememberPreference(difficultyKey, Difficulty.Adaptive)
    val activeMatchId by rememberPreference(activeMatchIdKey, "")
    val backendUrl by rememberPreference(backendUrlKey, defaultBackendUrl)

    var attempt by remember { mutableIntStateOf(0) }
    val holder = remember { PlayStateHolder(Offline.api) }
    val scope = rememberCoroutineScope()
    val playerId = rememberPlayerId()

    LaunchedEffect(backendUrl, attempt) {
        CrownFoundryClient.baseUrl = backendUrl
        // A base URL that has just changed is a referee that has just become reachable, or a
        // different one entirely. Either way the engine's staleness is worth re-checking.
        Offline.synchroniseInBackground(playerId)

        // A poll rather than a one-shot: the opponent keeps training while this screen is open,
        // and self-play moves these numbers without the reader touching anything.
        while (true) {
            holder.refresh()
            delay(POLL_INTERVAL_MILLIS)
        }
    }

    val state = holder.state

    Column(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                LocalWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues()
            )
    ) {
        Header(title = "Play") {
            state.summary?.let {
                BasicText(
                    text = "Elo ${it.elo}",
                    style = typography.s.secondary
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPlay(null) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            PrimaryButton(
                onClick = { onPlay(null) },
                iconId = R.drawable.arrow_forward
            )

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    text = "New match",
                    style = typography.m.semiBold
                )

                BasicText(
                    text = "You are Black and you move first.",
                    style = typography.xs.secondary
                )
            }
        }

        if (activeMatchId.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(activeMatchId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SecondaryButton(
                    onClick = { onPlay(activeMatchId) },
                    iconId = R.drawable.sync
                )

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText(
                        text = "Resume",
                        style = typography.xs.semiBold
                    )

                    BasicText(
                        text = "The match you left is still on the board.",
                        style = typography.xxs.secondary
                    )
                }
            }
        }

        SectionLabel(text = "DIFFICULTY")

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Difficulty.entries.forEach { entry ->
                DifficultyChip(
                    difficulty = entry,
                    isSelected = entry == difficulty,
                    onClick = { difficulty = entry }
                )
            }
        }

        BasicText(
            text = difficulty.description,
            style = typography.xxs.secondary,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
        )

        SectionLabel(text = "YOUR OPPONENT")

        OpponentCard(
            state = state,
            onClick = onSeeInsights
        )

        SectionLabel(text = "OFFLINE PLAY")

        EngineCard(
            state = Offline.engine.state,
            onUpdate = {
                scope.launch { Offline.synchronise(playerId, force = true) }
            }
        )

        SectionLabel(text = "BACKEND")

        BackendHealthLine(
            state = state,
            url = backendUrl,
            onRetry = { attempt += 1 }
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private const val POLL_INTERVAL_MILLIS = 20_000L

@Composable
private fun SectionLabel(text: String) {
    val (colorPalette, typography) = LocalAppearance.current

    BasicText(
        text = text,
        style = typography.xxs.semiBold.color(colorPalette.accent),
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp)
    )
}

@Composable
private fun DifficultyChip(
    difficulty: Difficulty,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    BasicText(
        text = difficulty.label,
        style = typography.xxs.medium.color(
            if (isSelected) colorPalette.onAccent else colorPalette.text
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(if (isSelected) colorPalette.accent else colorPalette.background1)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

/**
 * Elo, policy version, games trained and the win rate against you - and then, in a sentence, what
 * those four numbers add up to. Tapping it opens the curve they came from.
 */
@Composable
private fun OpponentCard(
    state: PlayState,
    onClick: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(colorPalette.background1)
            .padding(16.dp)
            .fillMaxWidth()
    ) {
        val summary = state.summary

        when {
            summary != null -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Figure(label = "Elo", value = summary.elo.toString(), accented = true)
                    Figure(label = "Policy", value = "v${summary.policyVersion}")
                    Figure(label = "Trained on", value = "${summary.totalMatches}")
                    Figure(label = "Wins vs you", value = percent(summary.aiWinRate))
                }

                BasicText(
                    text = aiArcReading(summary),
                    style = typography.xs.medium
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.trending),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.accent),
                        modifier = Modifier.size(12.dp)
                    )

                    BasicText(
                        text = "See the whole learning curve",
                        style = typography.xxs.medium.color(colorPalette.accent)
                    )
                }
            }

            state.isLoadingSummary -> ShimmerHost {
                TextPlaceholder()
                TextPlaceholder()
                TextPlaceholder()
            }

            else -> BasicText(
                text = "The opponent's record is on the backend, and the backend is not " +
                        "answering. What is below says why.",
                style = typography.xs.secondary
            )
        }
    }
}

@Composable
private fun Figure(
    label: String,
    value: String,
    accented: Boolean = false
) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(
            text = value,
            style = typography.s.semiBold.color(
                if (accented) colorPalette.accent else colorPalette.text
            ),
            maxLines = 1
        )

        BasicText(
            text = label,
            style = typography.xxs.medium.secondary,
            maxLines = 1
        )
    }
}

/**
 * Whether anything is listening, and whether Ollama is up behind it. When it is not, this is the
 * screen that has to explain it: the address that was tried, and where to change it.
 */
@Composable
private fun BackendHealthLine(
    state: PlayState,
    url: String,
    onRetry: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Image(
            painter = painterResource(R.drawable.pulse),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                when {
                    state.health != null -> colorPalette.accent
                    state.healthError != null -> colorPalette.red
                    else -> colorPalette.textDisabled
                }
            ),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(16.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            val health = state.health
            val error = state.healthError

            when {
                health != null -> {
                    BasicText(
                        text = "Reachable at $url, running version ${health.version.ifEmpty { "unknown" }}.",
                        style = typography.xs.medium
                    )

                    BasicText(
                        text = if (health.ollama.available) {
                            "Ollama is up on ${health.ollama.model.ifEmpty { "its default model" }}, " +
                                    "so the opponent will explain its moves in its own words."
                        } else {
                            "Ollama is not answering. The opponent will still play - the policy " +
                                    "picks the move - but it will describe itself in stock " +
                                    "phrases instead of sentences."
                        },
                        style = typography.xxs.secondary
                    )
                }

                error != null -> {
                    BasicText(
                        text = when (error) {
                            is ApiError.Unreachable -> "Nothing answered at ${error.url}."
                            is ApiError.Timeout -> "The backend at $url took longer than " +
                                    "${error.seconds} seconds to answer."

                            else -> "${error.message} ($url)"
                        },
                        style = typography.xs.medium.color(colorPalette.red)
                    )

                    BasicText(
                        text = "Start the Django server on that machine, or change the address " +
                                "in Settings → Backend.",
                        style = typography.xxs.secondary
                    )

                    SecondaryTextButton(
                        text = "Try again",
                        onClick = onRetry
                    )
                }

                else -> ShimmerHost {
                    TextPlaceholder()
                }
            }
        }
    }
}

// --- state --------------------------------------------------------------------------------------

data class PlayState(
    val isLoadingSummary: Boolean = true,
    val summary: AnalyticsSummaryDto? = null,
    val summaryError: ApiError? = null,
    val isCheckingHealth: Boolean = true,
    val health: HealthDto? = null,
    val healthError: ApiError? = null
)

/**
 * Two independent calls, two independent outcomes. A backend that is up but has no analytics yet
 * is a real state, and so is a stale summary sitting above a health line that has just gone red -
 * which is why the last good summary is kept rather than cleared on a failed poll.
 */
class PlayStateHolder(private val api: CheckersApi) {
    var state by mutableStateOf(PlayState())
        private set

    suspend fun refresh() {
        state = state.copy(isCheckingHealth = true, isLoadingSummary = true)

        state = when (val outcome = api.health()) {
            is Outcome.Success -> state.copy(
                isCheckingHealth = false,
                health = outcome.value,
                healthError = null
            )

            is Outcome.Failure -> state.copy(
                isCheckingHealth = false,
                health = null,
                healthError = outcome.reason
            )
        }

        state = when (val outcome = api.summary()) {
            is Outcome.Success -> state.copy(
                isLoadingSummary = false,
                summary = outcome.value,
                summaryError = null
            )

            is Outcome.Failure -> state.copy(
                isLoadingSummary = false,
                summaryError = outcome.reason
            )
        }
    }
}

/**
 * The identity the backend models this player by. Generated once and kept, because an opponent
 * that adapts to you needs to know which "you" it is playing.
 *
 * This belongs in `utils/Preferences.kt` beside the key it reads; it lives here for now because
 * that file is owned elsewhere.
 */
@Composable
fun rememberPlayerId(): String {
    val context = LocalContext.current

    return remember {
        val preferences = context.preferences
        val existing = preferences.getString(playerIdKey, null)

        if (!existing.isNullOrEmpty()) {
            existing
        } else {
            UUID.randomUUID().toString().also {
                preferences.edit().putString(playerIdKey, it).apply()
            }
        }
    }
}
