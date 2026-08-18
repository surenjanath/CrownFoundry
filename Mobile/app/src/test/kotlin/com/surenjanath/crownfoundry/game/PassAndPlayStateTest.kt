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
 * Two differences, and this file is both of them: the opponent is never asked for a move, and the
 * board turns to face whoever is to play - but only once the move that changed hands has finished
 * animating, because rotating mid-slide reads as a glitch rather than as handing the phone over.
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
    fun `the board turns over only once the move has finished animating`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)

        state.begin(null)
        assertEquals(Side.BLACK, state.viewpoint)

        state.play("11-15")
        // The piece is still sliding across the board the player is looking at.
        assertEquals(Side.BLACK, state.viewpoint)

        state.clearAnimation()
        assertEquals(Side.WHITE, state.viewpoint)
    }

    @Test
    fun `a resumed game opens facing the player to move`() = runTest {
        val api = FakeCheckersApi().apply {
            matchOutcome = Outcome.Success(
                Fixtures.initialMatch.copy(
                    board = Fixtures.initialBoard.copy(sideToMove = Side.WHITE)
                )
            )
        }

        val state = stateWith(api)
        state.begin(Fixtures.MATCH_ID)

        assertEquals(Side.WHITE, state.viewpoint)
        assertEquals(0, api.aiCalls)
        assertTrue(state.acceptsTaps)
    }

    @Test
    fun `against the engine nothing about the board moves`() = runTest {
        val api = FakeCheckersApi()
        val state = GameState(api = api, difficulty = "adaptive", playerId = null)

        state.begin(null)
        state.play("11-15")
        state.clearAnimation()

        // The engine game is played entirely from Black's side of the table, as it always was.
        assertEquals(Side.BLACK, state.viewpoint)
        assertFalse(state.mode.isPassAndPlay)
        assertTrue(api.aiCalls > 0)
    }
}
