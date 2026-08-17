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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.offline.EngineStatus
import com.surenjanath.crownfoundry.offline.EngineSync
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.offline.autoUpdateEngineKey
import com.surenjanath.crownfoundry.offline.learnOnDeviceKey
import com.surenjanath.crownfoundry.offline.preferOfflineKey
import com.surenjanath.crownfoundry.ui.components.themed.ConfirmationDialog
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.screens.home.rememberPlayerId
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.rememberPreference
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Everything about the opponent that lives on this phone.
 *
 * The screen is organised around the two things a player actually decides here - *keep it current*
 * and *use it even when the referee is up* - with the version detail underneath for anyone
 * checking why their offline opponent feels different from their online one.
 */
@Composable
fun OfflineSettings() {
    val (colorPalette) = LocalAppearance.current
    val scope = rememberCoroutineScope()
    val playerId = rememberPlayerId()

    var autoUpdate by rememberPreference(autoUpdateEngineKey, true)
    var preferOffline by rememberPreference(preferOfflineKey, false)
    var learnOnDevice by rememberPreference(learnOnDeviceKey, true)

    var action by remember { mutableStateOf<String?>(null) }
    var isConfirmingReset by remember { mutableStateOf(false) }

    val engine = Offline.engine.state

    if (isConfirmingReset) {
        ConfirmationDialog(
            text = "Delete the offline match history and everything the on-device engine learned " +
                    "from it? Games already sent to the referee stay there.",
            onDismiss = { isConfirmingReset = false },
            onConfirm = {
                scope.launch {
                    Offline.matches?.clear()
                    Offline.engine.setPendingUploads(0)
                    action = "Offline history cleared."
                }
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
        Header(title = "Offline")

        SettingsEntryGroupText(title = "AI ENGINE")

        SettingsEntry(
            title = "Installed engine",
            text = engine.headline,
            onClick = {
                action = "Checking…"
                scope.launch {
                    action = describe(Offline.refresh(force = true))
                }
            }
        )

        SettingsDescription(text = engine.detail)

        if (engine.status == EngineStatus.Incompatible) {
            ImportantSettingsDescription(
                text = engine.message
                    ?: "This engine was built for a newer version of CrownFoundry. Update the " +
                    "app, or the phone cannot play offline."
            )
        }

        SettingsEntry(
            title = "Update now",
            text = action ?: "Fetch the referee's current policy and send it any offline games.",
            isEnabled = !engine.busy,
            onClick = {
                action = "Syncing…"
                scope.launch {
                    val result = Offline.synchronise(playerId, force = true)
                    action = result?.let { (upload, refresh) ->
                        listOfNotNull(
                            describeUpload(upload),
                            describe(refresh)
                        ).joinToString(" ")
                    } ?: "Offline mode is still starting up."
                }
            }
        )

        SwitchSettingEntry(
            title = "Keep the engine current",
            text = "Download a new policy whenever the referee trains one. It is about 110 KB.",
            isChecked = autoUpdate,
            onCheckedChange = { autoUpdate = it }
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "HOW IT PLAYS")

        SwitchSettingEntry(
            title = "Always play offline",
            text = "Use the on-device engine even when the referee is reachable.",
            isChecked = preferOffline,
            isEnabled = engine.canPlayOffline,
            onCheckedChange = { preferOffline = it }
        )

        SettingsDescription(
            text = "Off by default, and the difference is worth knowing. The referee has the " +
                    "language model that writes the opponent's reasoning in full sentences, and " +
                    "the whole training corpus behind its policy. Offline you get the same search " +
                    "and the same weights, with stock phrasing instead of prose - and no waiting " +
                    "on a round trip. The app falls back to offline on its own whenever the " +
                    "referee cannot be reached, whatever this is set to."
        )

        SwitchSettingEntry(
            title = "Learn from offline games",
            text = "Fine-tune the on-device weights after each match played here.",
            isChecked = learnOnDevice,
            onCheckedChange = { learnOnDevice = it }
        )

        SettingsDescription(
            text = "The same credit assignment the referee runs: the game is replayed, each of " +
                    "the opponent's decisions is scored against how the game actually went, and " +
                    "the policy is nudged accordingly. It takes a moment at the end of a match " +
                    "and it means the next game faces an opponent that saw the last one."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "SYNC")

        SettingsEntry(
            title = "Games waiting to be sent",
            text = if (engine.pendingUploads == 0) "None - everything is with the referee."
            else "${engine.pendingUploads} finished offline " +
                    (if (engine.pendingUploads == 1) "game" else "games"),
            onClick = {
                action = "Sending…"
                scope.launch {
                    action = Offline.uploadOutbox(playerId)?.let(::describeUpload)
                        ?: "Offline mode is still starting up."
                }
            }
        )

        SettingsDescription(
            text = "Offline games are refereed by this phone, so the server has never seen them. " +
                    "Sending them up is what lets the shared policy learn from them: every move " +
                    "is replayed through the real engine on arrival, and anything that does not " +
                    "replay is refused rather than half-imported."
        )

        // Read-only, so they are stated rather than offered as rows that ripple and do nothing.
        SettingsDescription(
            text = "Last checked ${timestamp(engine.lastCheckedAt)} · " +
                    "last downloaded ${timestamp(engine.lastDownloadedAt)} · " +
                    "last trained here ${timestamp(engine.lastTrainedAt)}."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "STORAGE")

        SettingsEntry(
            title = "Clear offline history",
            text = "Match history and learned weights kept on this phone",
            onClick = { isConfirmingReset = true }
        )

        SettingsDescription(
            text = "The engine itself is not deleted - it stays playable, and it keeps whatever " +
                    "the referee trained into it. What goes is this phone's own record: the " +
                    "offline games, the positions it learned to avoid, and the fine-tuning on top."
        )
    }
}

private fun timestamp(millis: Long): String {
    if (millis <= 0) return "never"
    return FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

private fun describe(result: EngineSync.Result?): String = when (result) {
    null -> "Offline mode is still starting up."
    is EngineSync.Result.UpToDate -> "Already on the referee's v${result.version}."
    is EngineSync.Result.Updated ->
        if (result.from == null) "Downloaded engine v${result.to}."
        else "Updated from v${result.from} to v${result.to}."

    is EngineSync.Result.UpdateAvailable ->
        "The referee is on v${result.server}; this phone has " +
                (result.local?.let { "v$it" } ?: "none") + "."

    is EngineSync.Result.Rejected -> result.detail
    is EngineSync.Result.Failed -> when (val reason = result.reason) {
        is ApiError.Unreachable -> "Could not reach the referee at ${reason.url}."
        is ApiError.Timeout -> "The referee did not answer within ${reason.seconds}s."
        else -> reason.message
    }
}

private fun describeUpload(result: EngineSync.UploadResult): String {
    result.failure?.let {
        return "Could not send offline games: ${it.message}."
    }
    if (result.imported == 0 && result.duplicates == 0 && result.discarded == 0) {
        return "Nothing was waiting to be sent."
    }
    return buildString {
        if (result.imported > 0) {
            append("Sent ${result.imported} ")
            append(if (result.imported == 1) "game" else "games")
            append(" to the referee.")
        }
        if (result.discarded > 0) {
            if (isNotEmpty()) append(" ")
            append("${result.discarded} could not be replayed and were dropped.")
        }
        if (isEmpty()) append("Everything was already there.")
    }
}
