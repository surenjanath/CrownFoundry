package com.surenjanath.crownfoundry.ui.screens.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.offline.EngineStatus
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.screens.settings.SettingsDescription
import com.surenjanath.crownfoundry.ui.screens.settings.SettingsEntry
import com.surenjanath.crownfoundry.ui.screens.settings.SettingsEntryGroupText
import com.surenjanath.crownfoundry.ui.screens.settings.SettingsGroupSpacer
import com.surenjanath.crownfoundry.ui.screens.settings.ValueSelectorSettingsEntry
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.secondary
import kotlinx.coroutines.launch

/**
 * Where the opponent practises against itself.
 *
 * A visible screen rather than a background job, and that is a product decision as much as a
 * technical one. Training is minutes of full-tilt CPU: run silently it is a battery complaint the
 * player cannot explain, and run behind a scheduler it is a promise the app cannot keep on a phone
 * that never charges overnight. Here the work is something the player starts, watches and stops,
 * and the cost is obvious while it is being paid.
 *
 * Every session is a challenge, not an update. The weights only change if the policy that comes
 * out beats the one that went in - see `SelfPlay.gate` - so leaving this running can improve the
 * opponent and cannot damage it.
 */
@Composable
fun TrainingScreen() {
    val (colorPalette, typography) = LocalAppearance.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(TrainingState()) }
    val engine = Offline.engine.state

    // Leaving mid-session stops it. The store holds its lock for the length of a session, so a
    // session nobody is watching would block the next game the player started.
    var stopped by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { stopped = true } }

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
        Header(title = "Train")

        SettingsEntryGroupText(title = "PRACTICE")

        SettingsDescription(
            text = "The opponent plays a run of games against itself, starting against random " +
                    "play, working up through a material grabber, and finishing against its own " +
                    "policy. What it learns is then made to prove itself: the new weights play a " +
                    "match against the ones they would replace, and are thrown away unless they " +
                    "win it. Training here can only make the opponent better."
        )

        SettingsGroupSpacer()

        ValueSelectorSettingsEntry(
            title = "Session length",
            selectedValue = state.games,
            values = listOf(25, 50, 100, 200),
            onValueSelected = { state = state.copy(games = it) },
            isEnabled = !state.running,
            valueText = { "$it games" }
        )

        SettingsDescription(
            text = "Longer sessions gather more experience before the policy is fitted to it, " +
                    "which makes an improvement more likely to be real rather than lucky. A " +
                    "hundred games is a minute or two of solid work."
        )

        SettingsGroupSpacer()

        if (state.running) {
            ProgressBar(fraction = state.fraction)

            SettingsEntry(
                title = "Game ${state.played} of ${state.games}",
                text = "Currently ${state.stage} · ${state.tally}",
                onClick = { stopped = true }
            )

            SettingsDescription(text = "Tap to stop. The games played so far are still kept.")
        } else {
            SettingsEntry(
                title = "Start training",
                text = when {
                    !engine.canPlayOffline ->
                        "There is no engine installed to train."

                    engine.status == EngineStatus.Incompatible ->
                        "The installed engine cannot be read by this build."

                    else -> "Play ${state.games} games and keep the result only if it is better"
                },
                isEnabled = engine.canPlayOffline && engine.status != EngineStatus.Incompatible,
                onClick = {
                    val settings = Offline.settings
                    if (settings == null) {
                        state = state.copy(message = "Offline mode is still starting up.")
                        return@SettingsEntry
                    }
                    stopped = false
                    state = TrainingState(running = true, games = state.games)
                    scope.launch {
                        val report = Offline.engine.runSelfPlay(
                            games = state.games,
                            preferences = settings,
                            onProgress = { progress ->
                                state = state.with(
                                    progress,
                                    TrainingState.learnerSideOn(progress.gameIndex)
                                )
                            },
                            shouldContinue = { !stopped }
                        )
                        state = state.copy(
                            running = false,
                            report = report,
                            message = report?.let(::describeOutcome)
                                ?: "There is no engine installed to train."
                        )
                    }
                }
            )
        }

        state.message?.let { message ->
            SettingsGroupSpacer()
            SettingsEntryGroupText(title = "LAST SESSION")
            SettingsDescription(text = message)

            state.report?.let { report ->
                SettingsDescription(
                    text = "${report.verdict.summary} · ${report.transitions} positions learned " +
                            "from · ${report.replaySize} in the buffer · " +
                            "${report.durationMs / 1000}s."
                )
            }
        }

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "THIS ENGINE")

        SettingsDescription(
            text = buildString {
                append("Version ${engine.label}")
                val header = engine.header
                if (header != null && header.selfPlayGames > 0) {
                    append(" · ${header.selfPlayGames} games of practice across ")
                    append("${header.selfPlaySessions} accepted ")
                    append(if (header.selfPlaySessions == 1) "session" else "sessions")
                }
                append(".")
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        BasicText(
            text = "Most sessions are discarded, and that is the guard working rather than " +
                    "failing. Self-play produces a genuine improvement some of the time and " +
                    "noise the rest of it, and the only way to keep the first without the second " +
                    "is to make every candidate win a match before it is installed.",
            style = typography.xxs.secondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ProgressBar(fraction: Float) {
    val (colorPalette) = LocalAppearance.current

    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colorPalette.background2)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colorPalette.accent)
        )
    }
}
