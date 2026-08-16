package com.surenjanath.crownfoundry.ui.screens.settings

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.surenjanath.crownfoundry.BuildConfig
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.secondary

@ExperimentalAnimationApi
@Composable
fun About() {
    val (colorPalette, typography) = LocalAppearance.current
    val uriHandler = LocalUriHandler.current

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
        Header(title = "About") {
            BasicText(
                text = "v${BuildConfig.VERSION_NAME}",
                style = typography.s.secondary
            )
        }

        SettingsDescription(
            text = "CrownFoundry is a game of draughts against an opponent that is still " +
                    "learning. Every match you play is training data: the moves that lost it " +
                    "the game are penalised, the moves that won are reinforced, and the next " +
                    "game starts from a slightly better version of the same mind."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "HOW IT LEARNS")

        SettingsDescription(
            text = "A Q-network scores every position it can reach, a shallow search looks a " +
                    "few plies past that, and a local language model turns the chosen move into " +
                    "a sentence. Rewards follow the game: +10 for a win, +3 for a crown, +2 for " +
                    "a capture, -2 for a man lost, -10 for a loss. Learning happens three times " +
                    "over - once per move, once per finished match, and continuously in " +
                    "self-play on the backend."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "THE RULES IT PLAYS BY")

        SettingsDescription(
            text = "English draughts. Men move one square diagonally forward, kings move one " +
                    "square in any direction and do not fly. Captures are compulsory, and a " +
                    "multiple jump must be played out - except that a man crowned in the middle " +
                    "of a jump stops there. A game is drawn after forty plies with no capture " +
                    "and no crowning, or when the same position appears three times."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "CREDITS")

        SettingsEntry(
            title = "ViMusic",
            text = "The design and Compose foundations this app is built on",
            onClick = { uriHandler.openUri("https://github.com/vfsfitvnm/ViMusic") }
        )

        SettingsEntry(
            title = "Ollama",
            text = "The local model that gives the opponent its voice",
            onClick = { uriHandler.openUri("https://ollama.com") }
        )

        SettingsEntry(
            title = "Ionicons",
            text = "The icon set",
            onClick = { uriHandler.openUri("https://ionic.io/ionicons") }
        )

        SettingsEntry(
            title = "Poppins",
            text = "The typeface",
            onClick = { uriHandler.openUri("https://fonts.google.com/specimen/Poppins") }
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "PRIVACY")

        SettingsDescription(
            text = "No account and no analytics. Matches are stored by the backend you point " +
                    "this app at - by default one running on your own machine - and nothing " +
                    "leaves it."
        )
    }
}
