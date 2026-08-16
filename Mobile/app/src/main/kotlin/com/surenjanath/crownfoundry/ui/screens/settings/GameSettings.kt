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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.enums.Difficulty
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.difficultyKey
import com.surenjanath.crownfoundry.utils.hapticFeedbackKey
import com.surenjanath.crownfoundry.utils.rememberPreference
import com.surenjanath.crownfoundry.utils.showEvaluationKey
import com.surenjanath.crownfoundry.utils.showLegalMovesKey
import com.surenjanath.crownfoundry.utils.showReasoningKey

@Composable
fun GameSettings() {
    val (colorPalette) = LocalAppearance.current

    var difficulty by rememberPreference(difficultyKey, Difficulty.Adaptive)
    var showLegalMoves by rememberPreference(showLegalMovesKey, true)
    var showReasoning by rememberPreference(showReasoningKey, true)
    var showEvaluation by rememberPreference(showEvaluationKey, false)
    var hapticFeedback by rememberPreference(hapticFeedbackKey, true)

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
        Header(title = "Game")

        SettingsEntryGroupText(title = "OPPONENT")

        EnumValueSelectorSettingsEntry(
            title = "Difficulty",
            selectedValue = difficulty,
            onValueSelected = { difficulty = it },
            valueText = { it.label }
        )

        SettingsDescription(text = difficulty.description)

        SettingsDescription(
            text = "This is the same setting the Play tab shows, and it applies to the next " +
                    "match you start - a game already on the board keeps the difficulty it " +
                    "began with."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "THE BOARD")

        SwitchSettingEntry(
            title = "Show legal moves",
            text = "Mark the squares a selected piece can go to",
            isChecked = showLegalMoves,
            onCheckedChange = { showLegalMoves = it }
        )

        SwitchSettingEntry(
            title = "Show its reasoning",
            text = "Print the sentence the opponent writes about each of its moves",
            isChecked = showReasoning,
            onCheckedChange = { showReasoning = it }
        )

        SwitchSettingEntry(
            title = "Show its evaluation",
            text = "Show the score it gave the move it chose, and the ones it passed over",
            isChecked = showEvaluation,
            onCheckedChange = { showEvaluation = it }
        )

        SettingsDescription(
            text = "The evaluation is the Q-network's own number for a position, between -1 and " +
                    "1 from its side of the board. It is honest about what the opponent is " +
                    "thinking, and it will tell you when you are winning."
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "FEEDBACK")

        SwitchSettingEntry(
            title = "Haptic feedback",
            text = "A short buzz when a piece lands and when a capture is taken",
            isChecked = hapticFeedback,
            onCheckedChange = { hapticFeedback = it }
        )

        SettingsGroupSpacer()

        SettingsEntryGroupText(title = "RULE VARIATIONS")

        var flyingKings by rememberPreference(com.surenjanath.crownfoundry.utils.flyingKingsKey, true)
        var menCaptureBackwards by rememberPreference(com.surenjanath.crownfoundry.utils.menCaptureBackwardsKey, true)
        var mandatoryCapture by rememberPreference(com.surenjanath.crownfoundry.utils.mandatoryCaptureKey, true)

        SwitchSettingEntry(
            title = "Flying Kings",
            text = "Kings slide across open diagonals and jump over pieces from a distance",
            isChecked = flyingKings,
            onCheckedChange = { flyingKings = it }
        )

        SwitchSettingEntry(
            title = "Backward Man Captures",
            text = "Men / pawns can jump backwards to capture enemy pieces",
            isChecked = menCaptureBackwards,
            onCheckedChange = { menCaptureBackwards = it }
        )

        SwitchSettingEntry(
            title = "Mandatory Captures",
            text = "Captures are strictly mandatory when any jump is available",
            isChecked = mandatoryCapture,
            onCheckedChange = { mandatoryCapture = it }
        )

        SettingsDescription(
            text = "Rule variations apply when starting new matches and are referee-enforced by the engine."
        )
    }
}
