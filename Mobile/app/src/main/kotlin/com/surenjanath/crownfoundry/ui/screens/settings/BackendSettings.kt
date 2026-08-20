package com.surenjanath.crownfoundry.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.api.HealthDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.normaliseBaseUrl
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.components.themed.TextFieldDialog
import com.surenjanath.crownfoundry.ui.screens.home.rememberPlayerId
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.backendUrlKey
import com.surenjanath.crownfoundry.utils.copyToClipboard
import com.surenjanath.crownfoundry.utils.backendConfigured
import com.surenjanath.crownfoundry.utils.defaultBackendUrl
import com.surenjanath.crownfoundry.utils.effectiveBackendUrl
import com.surenjanath.crownfoundry.utils.rememberPreference
import kotlinx.coroutines.launch

/**
 * Where the referee lives, whether it is answering, and who this phone says it is.
 */
@Composable
fun BackendSettings() {
    val (colorPalette) = LocalAppearance.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Empty rather than the build default, so a build published without a server shows "not set"
    // instead of presenting the `none` sentinel as though it were an address someone could reach.
    var backendUrl by rememberPreference(backendUrlKey, if (backendConfigured) defaultBackendUrl else "")
    val playerId = rememberPlayerId()

    var isEditingUrl by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<Probe>(Probe.Untested) }

    // The client is a process-wide object; keeping it in step with the stored preference here
    // means a change takes effect on the very next call, without restarting anything.
    LaunchedEffect(backendUrl) {
        effectiveBackendUrl(backendUrl)?.let { CrownFoundryClient.baseUrl = it }
    }

    if (isEditingUrl) {
        TextFieldDialog(
            hintText = if (backendConfigured) defaultBackendUrl else "https://your-server.example.com",
            initialTextInput = backendUrl,
            onDismiss = { isEditingUrl = false },
            onDone = { entered ->
                backendUrl = if (entered.isBlank()) "" else normaliseBaseUrl(entered)
                effectiveBackendUrl(backendUrl)?.let { CrownFoundryClient.baseUrl = it }
                probe = Probe.Untested
            }
        )
    }

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
        Header(title = "Backend")

        SettingsEntryGroupText(title = "ADDRESS")

        SettingsEntry(
            title = "Backend URL",
            text = backendUrl.ifBlank { "Not set - playing offline" },
            onClick = { isEditingUrl = true }
        )

        SettingsDescription(
            text = if (backendConfigured) {
                "$defaultBackendUrl is how the Android emulator reaches a server running on " +
                        "your own machine. On a real phone, use the machine's address on your " +
                        "network - something like http://192.168.1.20:8000 - and make sure Django " +
                        "is listening on it, not only on localhost."
            } else {
                "This build ships without a referee, and does not need one: the opponent runs on " +
                        "this phone, and so do match history, review and puzzles. Name a server " +
                        "here to sync your games and pick up newer opponents as they are trained."
            }
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "CONNECTION")

        SettingsEntry(
            title = "Test connection",
            text = probeText(probe),
            isEnabled = probe !is Probe.Testing,
            onClick = {
                probe = Probe.Testing
                coroutineScope.launch {
                    probe = when (val outcome = CrownFoundryClient.testConnection(backendUrl)) {
                        is Outcome.Success -> Probe.Answered(outcome.value)
                        is Outcome.Failure -> Probe.Failed(outcome.reason)
                    }
                }
            }
        )

        SettingsDescription(
            text = "The test asks for /api/health/ and reports what came back: the backend's " +
                    "version, whether Ollama is up and on which model, and which policy version " +
                    "the opponent is currently playing from."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "IDENTITY")

        SettingsEntry(
            title = "Player id",
            text = playerId,
            onClick = { context.copyToClipboard(playerId) }
        )

        SettingsDescription(
            text = "Generated once on this phone and sent with every match. It is how an " +
                    "opponent that adapts to one player knows which player it is adapting to. " +
                    "Tap to copy it."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "WHAT THE BACKEND KEEPS")

        SettingsDescription(
            text = "Every match: the position after each ply, the move that produced it, and the " +
                    "sentence the opponent wrote about its own moves. Alongside that it keeps a " +
                    "profile of your play - how often you trade, how quickly you go for kings - " +
                    "and the policy weights it trains from all of it."
        )

        SettingsDescription(
            text = "There is no account and nothing is sent anywhere else. Everything above " +
                    "lives on the machine at the address at the top of this screen, which by " +
                    "default is yours."
        )
    }
}

private sealed interface Probe {
    data object Untested : Probe
    data object Testing : Probe
    data class Answered(val health: HealthDto) : Probe
    data class Failed(val error: ApiError) : Probe
}

private fun probeText(probe: Probe): String = when (probe) {
    Probe.Untested -> "Not tried yet"
    Probe.Testing -> "Asking…"

    is Probe.Answered -> buildString {
        append("Answered")
        probe.health.version.takeIf { it.isNotEmpty() }?.let { append(", version $it") }
        append(
            if (probe.health.ollama.available) {
                ", Ollama up on ${probe.health.ollama.model.ifEmpty { "its default model" }}"
            } else {
                ", Ollama not answering"
            }
        )
        append(", policy v${probe.health.policyVersion}")
    }

    is Probe.Failed -> when (val error = probe.error) {
        is ApiError.Unreachable -> "Nothing answered at ${error.url}"
        is ApiError.Timeout -> "No answer within ${error.seconds} seconds"
        else -> error.message
    }
}
