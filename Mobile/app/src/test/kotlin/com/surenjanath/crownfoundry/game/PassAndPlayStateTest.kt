package com.surenjanath.crownfoundry.game

import com.surenjanath.crownfoundry.api.AppliedMoveDto
import com.surenjanath.crownfoundry.api.BoardDto
import com.surenjanath.crownfoundry.api.MoveResultDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.screens.game.GameMode
import com.surenjanath.crownfoundry.ui.screens.game.GamePhase
import com.surenjanath.crownfoundry.ui.screens.game.GameState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn machine with nobody to wait for.
 *
 * One difference, and this file is it: no opponent turn is ever requested, so the board simply
 * stays live for whichever side the rules say is to move. The board is drawn from Black's side in
 * both modes and never turns - a board on a table does not spin round between turns.
 */
class PassAndPlayStateTest {

    private fun stateWith(api: FakeCheckersApi) = GameState(
        api = api,
        difficulty = "pass",
        playerId = null,
        mode = GameMode.PassAndPlay
    )

    /** The referee's answer to a White move played by hand: back to Black, still going. */
    private fun afterWhiteMove(notation: String = "23-19") = MoveResultDto(
        gameOver = false,
        boardState = "B:W21,...:B...",
        board = BoardDto(
            fen = "B:W21,...:B...",
            sideToMove = Side.BLACK,
            pieces = Fixtures.initialPieces
        ),
        legalMoves = Fixtures.openingMoves,
        appliedMove = AppliedMoveDto(notation = notation),
        turnNumber = 2
    )

    @Test
    fun `the opponent is never asked to move`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)

        state.begin(null)
        state.play("11-15")

        assertEquals(0, api.aiCalls)
        // The board is White's to play now, and it is still waiting for a finger.
        assertEquals(GamePhase.HumanTurn, state.phase)
        assertEquals(Side.WHITE, state.sideToMove)
    }

    @Test
    fun `the board answers to White as readily as to Black`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)

        state.begin(null)
        assertTrue(state.acceptsTaps)

        state.play("11-15")

        // Against the engine this is exactly when taps stop being accepted.
        assertEquals(Side.WHITE, state.sideToMove)
        assertTrue(state.acceptsTaps)
    }

    @Test
    fun `White's move is attributed to White`() = runTest {
        val api = FakeCheckersApi()
        api.moveOutcomes += Outcome.Success(Fixtures.afterHumanOpening())
        api.moveOutcomes += Outcome.Success(afterWhiteMove())

        val state = stateWith(api)
        state.begin(null)

        state.play("11-15")
        state.clearAnimation()
        state.play("23-19")

        assertEquals(Side.AI, state.animation?.side)
        assertEquals(listOf("11-15", "23-19"), api.movesSent)
    }

    @Test
    fun `a resumed game on White's move is White's to play, not the engine's`() = runTest {
        val api = FakeCheckersApi().apply {
            matchOutcome = Outcome.Success(
                Fixtures.initialMatch.copy(
                    board = Fixtures.initialBoard.copy(sideToMove = Side.WHITE)
                )
            )
        }

        val state = stateWith(api)
        state.begin(Fixtures.MATCH_ID)

        assertEquals(0, api.aiCalls)
        assertTrue(state.acceptsTaps)
    }

    @Test
    fun `against the engine the opponent still answers`() = runTest {
        val api = FakeCheckersApi()
        val state = GameState(api = api, difficulty = "adaptive", playerId = null)

        state.begin(null)
        state.play("11-15")

        assertFalse(state.mode.isPassAndPlay)
        assertTrue(api.aiCalls > 0)
    }
}
