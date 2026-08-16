package com.surenjanath.crownfoundry.game

import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.AppliedMoveDto
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.screens.game.GamePhase
import com.surenjanath.crownfoundry.ui.screens.game.GameState
import com.surenjanath.crownfoundry.ui.screens.game.RetryAction
import com.surenjanath.crownfoundry.ui.screens.game.TapResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The turn machine, driven entirely off [FakeCheckersApi]. */
class GameStateTest {

    private fun stateWith(
        api: FakeCheckersApi,
        onMatchIdChanged: (String?) -> Unit = {}
    ) = GameState(
        api = api,
        difficulty = "adaptive",
        playerId = "player-1",
        onMatchIdChanged = onMatchIdChanged
    )

    @Test
    fun `a new match starts the human on move with twenty-four pieces`() = runTest {
        val api = FakeCheckersApi()
        val ids = mutableListOf<String?>()
        val state = stateWith(api) { ids += it }

        state.begin(null)

        assertEquals(1, api.startCalls)
        assertEquals(0, api.matchCalls)
        assertEquals(Fixtures.MATCH_ID, state.matchId)
        assertEquals(listOf<String?>(Fixtures.MATCH_ID), ids)
        assertEquals(24, state.pieces.size)
        assertEquals(12, state.counts.black)
        assertEquals(12, state.counts.white)
        assertEquals(Side.HUMAN, state.sideToMove)
        assertEquals(GamePhase.HumanTurn, state.phase)
        assertTrue(state.acceptsTaps)
        assertEquals(7, state.legalMoves.size)
        assertNull(state.failure)
    }

    @Test
    fun `a match id resumes instead of starting a new one`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)

        state.begin(Fixtures.MATCH_ID)

        assertEquals(0, api.startCalls)
        assertEquals(1, api.matchCalls)
        assertEquals(GamePhase.HumanTurn, state.phase)
    }

    @Test
    fun `an unreachable referee leaves a retryable failure and no match`() = runTest {
        val api = FakeCheckersApi().apply {
            startOutcome = Outcome.Failure(ApiError.Unreachable("http://10.0.2.2:8000"))
        }
        val state = stateWith(api)

        state.begin(null)

        assertEquals(GamePhase.Idle, state.phase)
        assertNull(state.matchId)
        assertEquals(RetryAction.Begin, state.failure?.retry)
        assertTrue(state.failure?.error is ApiError.Unreachable)
    }

    @Test
    fun `a human move runs straight into the opponent's turn`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)

        state.begin(null)
        state.play("11-15")

        assertEquals(listOf("11-15"), api.movesSent)
        assertEquals(1, api.aiCalls)
        assertEquals(1, state.aiTurns)
        assertEquals(GamePhase.HumanTurn, state.phase)
        assertEquals(Side.HUMAN, state.sideToMove)
        assertEquals("23-18", state.lastAiMove)
        assertTrue(state.spokeThroughOllama)
        assertNotNull(state.reasoning)
        assertEquals(2, state.evaluation?.considered?.size)
        assertNull(state.failure)
    }

    @Test
    fun `the opponent's move is handed to the board as an animation and a trace`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)

        state.begin(null)
        state.play("11-15")

        assertEquals(23, state.animation?.origin)
        assertEquals(listOf(18), state.animation?.path)
        assertEquals(Side.AI, state.animation?.side)
        assertEquals(23, state.lastMove?.from)
        assertEquals(18, state.lastMove?.to)

        state.clearAnimation()
        assertNull(state.animation)
    }

    @Test
    fun `a captured piece is carried into the animation with the crown it was wearing`() = runTest {
        val api = FakeCheckersApi().apply {
            moveOutcomes += Outcome.Success(
                Fixtures.afterHumanOpening().copy(
                    gameOver = true,
                    winner = Side.HUMAN,
                    appliedMove = AppliedMoveDto(
                        notation = "11x18x25",
                        captures = listOf(15, 22),
                        crowned = false
                    )
                )
            )
        }
        val state = stateWith(api)

        state.begin(null)
        state.play("11x18x25")

        // 15 is empty in the opening position, 22 holds a White man - only real pieces are drawn.
        // gameOver=true prevents the AI follow-up from overwriting the human's animation.
        assertEquals(listOf(22), state.animation?.captured?.map { it.square })
    }

    @Test
    fun `an illegal move puts the position back and takes the referee's hints`() = runTest {
        val api = FakeCheckersApi().apply {
            moveOutcomes += Outcome.Failure(ApiError.IllegalMove(Fixtures.captureMoves))
        }
        val state = stateWith(api)

        state.begin(null)
        val before = state.pieces

        state.play("11-16")

        assertEquals("the board must not move", before, state.pieces)
        assertEquals(Fixtures.INITIAL_FEN, state.fen)
        assertEquals(Side.HUMAN, state.sideToMove)
        assertEquals("the server's legal_moves replace the local hints", Fixtures.captureMoves, state.legalMoves)
        assertTrue(state.mustCapture)
        assertEquals(GamePhase.HumanTurn, state.phase)
        assertEquals(RetryAction.None, state.failure?.retry)
        assertTrue(state.failure?.error is ApiError.IllegalMove)
        assertEquals("the opponent was never asked", 0, api.aiCalls)
    }

    @Test
    fun `a timeout on the opponent's turn leaves the position and offers a retry`() = runTest {
        val api = FakeCheckersApi().apply {
            aiOutcomes += Outcome.Failure(ApiError.Timeout(90))
            aiOutcomes += Outcome.Success(Fixtures.aiReply())
        }
        val state = stateWith(api)

        state.begin(null)
        state.play("11-15")

        assertEquals(GamePhase.Stalled, state.phase)
        assertEquals(RetryAction.AiTurn, state.failure?.retry)
        assertTrue(state.failure!!.canRetry)
        assertTrue(state.failure?.error is ApiError.Timeout)
        assertNull("the match is not over", state.winner)
        assertEquals("the position the move produced is still on the board", 24, state.pieces.size)
        assertEquals(0, state.aiTurns)

        state.retry()

        assertEquals(2, api.aiCalls)
        assertEquals(1, state.aiTurns)
        assertEquals(GamePhase.HumanTurn, state.phase)
        assertNull(state.failure)
    }

    @Test
    fun `the brain being down is its own retryable state`() = runTest {
        val api = FakeCheckersApi().apply {
            aiOutcomes += Outcome.Failure(ApiError.BrainUnavailable("ollama refused the connection"))
        }
        val state = stateWith(api)

        state.begin(null)
        state.play("11-15")

        assertEquals(GamePhase.Stalled, state.phase)
        assertTrue(state.failure?.error is ApiError.BrainUnavailable)
        assertEquals(RetryAction.AiTurn, state.failure?.retry)
    }

    @Test
    fun `game over stops the loop and never fires another opponent turn`() = runTest {
        val api = FakeCheckersApi().apply {
            moveOutcomes += Outcome.Success(
                Fixtures.afterHumanOpening().copy(gameOver = true, winner = Side.HUMAN)
            )
        }
        val ids = mutableListOf<String?>()
        val state = stateWith(api) { ids += it }

        state.begin(null)
        state.play("11-15")

        assertEquals(GamePhase.Over, state.phase)
        assertTrue(state.isOver)
        assertTrue(state.humanWon)
        assertEquals(0, api.aiCalls)
        assertTrue("the hints must go with the game", state.legalMoves.isEmpty())
        assertFalse(state.acceptsTaps)
        assertEquals("the active match id is cleared", listOf(Fixtures.MATCH_ID, null), ids)

        // Anything the screen still has in flight is refused rather than replayed.
        state.play("10-14")
        state.aiTurn()

        assertEquals(1, api.movesSent.size)
        assertEquals(0, api.aiCalls)
    }

    @Test
    fun `the opponent winning ends the match too`() = runTest {
        val api = FakeCheckersApi().apply {
            aiOutcomes += Outcome.Success(Fixtures.aiReply(gameOver = true, winner = Side.AI))
        }
        val state = stateWith(api)

        state.begin(null)
        state.play("11-15")

        assertEquals(GamePhase.Over, state.phase)
        assertTrue(state.aiWon)
        assertEquals(1, api.aiCalls)
    }

    @Test
    fun `resigning hands the match to the machine`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)

        state.begin(null)
        state.resign()

        assertEquals(1, api.resignCalls)
        assertEquals(GamePhase.Over, state.phase)
        assertTrue(state.aiWon)
    }

    @Test
    fun `a rematch starts a second match from scratch`() = runTest {
        val api = FakeCheckersApi().apply {
            aiOutcomes += Outcome.Success(Fixtures.aiReply(gameOver = true, winner = Side.AI))
        }
        val state = stateWith(api)

        state.begin(null)
        state.play("11-15")
        assertTrue(state.isOver)

        state.rematch()

        assertEquals(2, api.startCalls)
        assertEquals(GamePhase.HumanTurn, state.phase)
        assertNull(state.winner)
        assertNull(state.reasoning)
        assertEquals(24, state.pieces.size)
    }

    @Test
    fun `a resumed match that is already finished opens on the game over state`() = runTest {
        val api = FakeCheckersApi().apply {
            matchOutcome = Outcome.Success(
                Fixtures.initialMatch.copy(status = "finished", winner = Side.AI)
            )
        }
        val state = stateWith(api)

        state.begin(Fixtures.MATCH_ID)

        assertEquals(GamePhase.Over, state.phase)
        assertTrue(state.aiWon)
        assertEquals(0, api.aiCalls)
    }

    @Test
    fun `a resumed match sitting on the opponent's move asks it to think`() = runTest {
        val api = FakeCheckersApi().apply {
            matchOutcome = Outcome.Success(
                MatchDto(
                    matchId = Fixtures.MATCH_ID,
                    board = Fixtures.initialBoard.copy(sideToMove = Side.AI),
                    legalMoves = emptyList(),
                    turnNumber = 3
                )
            )
        }
        val state = stateWith(api)

        state.begin(Fixtures.MATCH_ID)

        assertEquals(1, api.aiCalls)
        assertEquals(GamePhase.HumanTurn, state.phase)
        assertEquals(Side.HUMAN, state.sideToMove)
    }

    // --- taps --------------------------------------------------------------------------------

    @Test
    fun `tapping a piece then a hint produces one notation string`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)
        state.begin(null)

        val selected = state.tap(11)
        assertTrue(selected is TapResult.Selected)
        assertEquals(setOf(15, 16), state.selection?.destinations)

        val ready = state.tap(15)
        assertEquals(TapResult.Ready("11-15"), ready)
        assertNull(state.selection)
    }

    @Test
    fun `tapping a piece with no move is ignored and tapping the same piece deselects`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)
        state.begin(null)

        assertEquals(TapResult.Ignored, state.tap(1))
        assertNull(state.selection)

        state.tap(11)
        assertEquals(TapResult.Cleared, state.tap(11))
        assertNull(state.selection)
    }

    @Test
    fun `tapping another movable piece switches the selection`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)
        state.begin(null)

        state.tap(11)
        val switched = state.tap(9)

        assertTrue(switched is TapResult.Selected)
        assertEquals(9, state.selection?.origin)
    }

    @Test
    fun `a triple jump is three taps and one move`() = runTest {
        val api = FakeCheckersApi().apply {
            matchOutcome = Outcome.Success(
                Fixtures.initialMatch.copy(legalMoves = Fixtures.captureMoves)
            )
        }
        val state = stateWith(api)
        state.begin(Fixtures.MATCH_ID)

        assertTrue(state.mustCapture)
        assertEquals(TapResult.Ignored, state.tap(12))

        assertTrue(state.tap(11) is TapResult.Selected)
        assertTrue(state.tap(18) is TapResult.Advanced)
        assertEquals(setOf(25, 27), state.selection?.destinations)

        // Mid-jump, a square that is not a continuation does nothing at all.
        assertEquals(TapResult.Ignored, state.tap(9))
        assertEquals(18, state.selection?.square)

        assertEquals(TapResult.Ready("11x18x25"), state.tap(25))

        state.play("11x18x25")
        assertEquals(listOf("11x18x25"), api.movesSent)
    }

    @Test
    fun `mid jump the square you are standing on backs the whole thing out`() = runTest {
        val api = FakeCheckersApi().apply {
            matchOutcome = Outcome.Success(
                Fixtures.initialMatch.copy(legalMoves = Fixtures.captureMoves)
            )
        }
        val state = stateWith(api)
        state.begin(Fixtures.MATCH_ID)

        state.tap(11)
        state.tap(18)
        assertEquals(TapResult.Cleared, state.tap(18))
        assertNull(state.selection)
    }

    @Test
    fun `the board ignores taps once the match is over`() = runTest {
        val api = FakeCheckersApi()
        val state = stateWith(api)
        state.begin(null)
        state.play("11-15")
        state.resign()

        assertEquals(TapResult.Ignored, state.tap(11))
        assertNull(state.selection)
    }
}
