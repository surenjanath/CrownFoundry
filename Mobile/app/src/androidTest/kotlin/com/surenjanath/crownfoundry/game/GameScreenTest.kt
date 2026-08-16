package com.surenjanath.crownfoundry.game

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.components.board.BoardHintSquares
import com.surenjanath.crownfoundry.ui.components.board.BoardPieceCount
import com.surenjanath.crownfoundry.ui.components.board.CheckersBoardTag
import com.surenjanath.crownfoundry.ui.components.board.squareCenter
import com.surenjanath.crownfoundry.ui.screens.game.AiPanelTag
import com.surenjanath.crownfoundry.ui.screens.game.AiThinkingTag
import com.surenjanath.crownfoundry.ui.screens.game.GameFailureTag
import com.surenjanath.crownfoundry.ui.screens.game.GameOverDialogTag
import com.surenjanath.crownfoundry.ui.screens.game.GameScreen
import com.surenjanath.crownfoundry.ui.screens.game.RematchButtonTag
import com.surenjanath.crownfoundry.ui.screens.game.RetryButtonTag
import com.surenjanath.crownfoundry.utils.preferences
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
class GameScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val api = ScriptedApi()

    @Before
    fun clearPreferences() {
        InstrumentationRegistry.getInstrumentation().targetContext.preferences
            .edit().clear().commit()
    }

    private fun screen() = rule.setContent {
        Themed {
            GameScreen(matchId = null, api = api)
        }
    }

    private fun tapSquare(square: Int) {
        rule.onNodeWithTag(CheckersBoardTag).performTouchInput {
            click(squareCenter(square, width.toFloat()))
        }
        rule.waitForIdle()
    }

    @Test
    fun a_new_match_opens_on_the_initial_position() {
        screen()

        rule.onNodeWithTag(CheckersBoardTag)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(BoardPieceCount, 24))

        rule.onNodeWithTag(AiPanelTag).assertIsDisplayed()
        assertEquals(1, api.startCalls)
    }

    @Test
    fun tapping_a_piece_shows_its_hints() {
        screen()

        tapSquare(11)

        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardHintSquares, listOf(15, 16)))
    }

    @Test
    fun tapping_a_hint_sends_the_expected_notation() {
        screen()

        tapSquare(11)
        tapSquare(15)

        rule.waitUntil(5_000) { api.movesSent.isNotEmpty() }
        assertEquals(listOf("11-15"), api.movesSent)
        rule.waitUntil(5_000) { api.aiCalls == 1 }
    }

    @Test
    fun the_thinking_state_appears_while_the_opponent_decides() {
        val gate = CompletableDeferred<Unit>()
        api.holdAiTurn = gate

        screen()

        tapSquare(11)
        tapSquare(15)

        rule.waitUntil(5_000) { api.aiCalls == 1 }
        rule.onNodeWithTag(AiThinkingTag).assertIsDisplayed()

        gate.complete(Unit)
        rule.waitUntil(5_000) {
            rule.nodesWithTag(AiThinkingTag) == 0
        }
    }

    @Test
    fun the_game_over_dialog_appears_and_a_rematch_starts_a_new_match() {
        api.aiOutcome = Outcome.Success(UiFixtures.aiReply(gameOver = true, winner = Side.AI))

        screen()

        tapSquare(11)
        tapSquare(15)

        rule.waitUntil(5_000) { rule.nodesWithTag(GameOverDialogTag) == 1 }
        rule.onNodeWithTag(GameOverDialogTag).assertIsDisplayed()

        rule.onNodeWithTag(RematchButtonTag).performClick()

        rule.waitUntil(5_000) { api.startCalls == 2 }
        assertEquals(2, api.startCalls)
    }

    @Test
    fun an_unreachable_referee_is_drawn_with_a_retry() {
        api.startOutcome = Outcome.Failure(ApiError.Unreachable("http://10.0.2.2:8000"))

        screen()

        rule.waitUntil(5_000) { rule.nodesWithTag(GameFailureTag) == 1 }
        rule.onNodeWithTag(GameFailureTag).assertIsDisplayed()
        rule.onNodeWithTag(RetryButtonTag).assertIsDisplayed()

        api.startOutcome = Outcome.Success(UiFixtures.initialMatch)
        rule.onNodeWithTag(RetryButtonTag).performClick()

        rule.waitUntil(5_000) { api.startCalls == 2 }
        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardPieceCount, 24))
    }

    @Test
    fun an_illegal_move_redraws_the_hints_the_referee_supplied() {
        api.moveOutcome = Outcome.Failure(
            ApiError.IllegalMove(listOf(UiFixtures.move("9-13")))
        )

        screen()

        tapSquare(11)
        tapSquare(15)

        rule.waitUntil(5_000) { rule.nodesWithTag(GameFailureTag) == 1 }

        tapSquare(11)
        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardHintSquares, emptyList<Int>()))

        tapSquare(9)
        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardHintSquares, listOf(13)))
    }
}
