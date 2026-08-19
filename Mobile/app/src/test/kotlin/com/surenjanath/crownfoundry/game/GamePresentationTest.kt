package com.surenjanath.crownfoundry.game

import com.surenjanath.crownfoundry.api.AiStatusDto
import com.surenjanath.crownfoundry.api.EvaluationDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.ResignDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.screens.game.Commentary
import com.surenjanath.crownfoundry.ui.screens.game.GameMode
import com.surenjanath.crownfoundry.ui.screens.game.GameState
import com.surenjanath.crownfoundry.ui.screens.game.SeatAvatar
import com.surenjanath.crownfoundry.ui.screens.game.capturedOf
import com.surenjanath.crownfoundry.ui.screens.game.clockOf
import com.surenjanath.crownfoundry.ui.screens.game.present
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the screen says about the game.
 *
 * These exist because the facts they cover used to be derived independently in four different
 * composables - whose turn it is, what each side is called, how many pieces have come off - and
 * four copies of a rule are four chances to disagree. Each assertion here is a claim that some
 * fact is computed once and reads the same wherever it is drawn.
 */
class GamePresentationTest {

    private fun stateWith(
        api: FakeCheckersApi,
        mode: GameMode = GameMode.VersusEngine
    ) = GameState(api = api, difficulty = "adaptive", playerId = null, mode = mode)

    // --- naming, computed once -------------------------------------------------------------

    @Test
    fun `against the engine the seats are you and the opponent`() = runTest {
        val state = stateWith(FakeCheckersApi())
        state.begin(null)

        val screen = state.present()

        assertEquals("You", screen.you.name)
        assertEquals("Opponent", screen.opponent.name)
        assertEquals(SeatAvatar.Person, screen.you.avatar)
        assertEquals(SeatAvatar.Engine, screen.opponent.avatar)
    }

    @Test
    fun `in pass-and-play both chairs are people, named by colour`() = runTest {
        val state = stateWith(FakeCheckersApi(), GameMode.PassAndPlay)
        state.begin(null)

        val screen = state.present()

        assertEquals("Black", screen.you.name)
        assertEquals("White", screen.opponent.name)
        assertEquals(SeatAvatar.Person, screen.opponent.avatar)
        // Nothing about an engine belongs on a game two people played.
        assertTrue(screen.opponent.tags.isEmpty())
        assertNull(screen.opponent.evaluation)
        assertEquals(Commentary.Silent, screen.commentary)
    }

    // --- whose turn, said once ---------------------------------------------------------------

    @Test
    fun `exactly one seat has the move`() = runTest {
        val state = stateWith(FakeCheckersApi())
        state.begin(null)

        val opening = state.present()
        assertTrue(opening.you.isToMove)
        assertFalse(opening.opponent.isToMove)

        state.play("11-15")

        val afterReply = state.present()
        // The engine answered, so it is Black's again - still exactly one seat.
        assertTrue(afterReply.you.isToMove != afterReply.opponent.isToMove)
    }

    @Test
    fun `a finished game leaves nobody to move`() = runTest {
        val api = FakeCheckersApi().apply {
            resignOutcome = Outcome.Success(ResignDto(gameOver = true, winner = Side.AI))
        }
        val state = stateWith(api)
        state.begin(null)
        state.resign()

        val screen = state.present()

        assertFalse(screen.you.isToMove)
        assertFalse(screen.opponent.isToMove)
    }

    // --- the event line ------------------------------------------------------------------------

    @Test
    fun `nothing to announce while an ordinary turn is waiting`() = runTest {
        val state = stateWith(FakeCheckersApi())
        state.begin(null)

        // Whose turn it is belongs to the seat; the bar only speaks up when something is unusual.
        assertNull(state.present().event)
    }

    @Test
    fun `the result is announced when the game ends`() = runTest {
        val api = FakeCheckersApi().apply {
            resignOutcome = Outcome.Success(ResignDto(gameOver = true, winner = Side.AI))
        }

        val engine = stateWith(api)
        engine.begin(null)
        engine.resign()
        assertEquals("It won", engine.present().event?.text)

        val hand = stateWith(api, GameMode.PassAndPlay)
        hand.begin(null)
        hand.resign()
        assertEquals("White won", hand.present().event?.text)
    }

    @Test
    fun `a compulsory capture is urgent`() = runTest {
        val api = FakeCheckersApi().apply {
            startOutcome = Outcome.Success(
                Fixtures.initialMatch.copy(legalMoves = Fixtures.captureMoves)
            )
        }
        val state = stateWith(api)
        state.begin(null)

        val event = state.present().event

        assertEquals("Capture is mandatory", event?.text)
        assertTrue(event!!.urgent)
    }

    // --- what each side has left ----------------------------------------------------------------

    @Test
    fun `each seat reports its own material and the other side's losses`() = runTest {
        val state = stateWith(FakeCheckersApi())
        state.begin(null)

        val screen = state.present()

        assertEquals(12, screen.you.pieces)
        assertEquals(12, screen.opponent.pieces)
        assertEquals(0, screen.you.kings)
        // Nothing has been taken from a board nobody has moved on.
        assertEquals(0, screen.you.captured)
        assertEquals(0, screen.opponent.captured)
    }

    @Test
    fun `twelve a side, so what is missing is what the other player took`() {
        assertEquals(0, capturedOf(12))
        assertEquals(5, capturedOf(7))
        assertEquals(12, capturedOf(0))
        // A count that should be impossible reads as nothing taken rather than as a negative.
        assertEquals(0, capturedOf(13))
    }

    // --- the engine's own details ---------------------------------------------------------------

    @Test
    fun `the opponent carries its rating, its setting and where it is running`() = runTest {
        val api = FakeCheckersApi().apply {
            startOutcome = Outcome.Success(
                Fixtures.initialMatch.copy(ai = AiStatusDto(elo = 1420))
            )
        }
        val state = stateWith(api)
        state.begin(null)

        val tags = state.present(engineLabel = "On-device v27").opponent.tags.map { it.text }

        assertEquals(listOf("1420 Elo", "Adaptive", "On-device v27"), tags)
    }

    @Test
    fun `an unrated opponent does not claim a rating`() = runTest {
        val api = FakeCheckersApi().apply {
            startOutcome = Outcome.Success(Fixtures.initialMatch.copy(ai = AiStatusDto(elo = 0)))
        }
        val state = stateWith(api)
        state.begin(null)

        assertTrue(state.present().opponent.tags.none { it.text.endsWith("Elo") })
    }

    @Test
    fun `the evaluation is shown only when it was asked for`() = runTest {
        val api = FakeCheckersApi().apply {
            aiOutcomes += Outcome.Success(
                Fixtures.aiReply().copy(evaluation = EvaluationDto(qValue = 0.41))
            )
        }
        val state = stateWith(api)
        state.begin(null)
        state.play("11-15")

        assertEquals("Q +0.41", state.present(showEvaluation = true).opponent.evaluation)
        assertNull(state.present(showEvaluation = false).opponent.evaluation)
    }

    @Test
    fun `the opponent's sentence is shown only when commentary is on`() = runTest {
        val state = stateWith(FakeCheckersApi())
        state.begin(null)
        state.play("11-15")

        val said = state.present(showReasoning = true).commentary
        assertTrue(said is Commentary.Said)
        assertEquals(Commentary.Silent, state.present(showReasoning = false).commentary)
    }

    // --- how far in you are ---------------------------------------------------------------------

    @Test
    fun `the rail counts the move about to be played, then how long it took`() = runTest {
        val state = stateWith(FakeCheckersApi())
        state.begin(null)

        // Nobody has moved, so the game is on its first move rather than its zeroth.
        assertEquals("Move 1", state.present().moveLabel)

        state.play("11-15")
        assertEquals("Move 3", state.present().moveLabel)

        val ended = stateWith(FakeCheckersApi().apply {
            resignOutcome = Outcome.Success(ResignDto(gameOver = true, winner = Side.AI))
        })
        ended.begin(null)
        ended.resign()
        assertEquals("0 moves", ended.present().moveLabel)
    }

    // --- the clock ------------------------------------------------------------------------------

    @Test
    fun `the clock reads as minutes and seconds`() {
        assertEquals("00:00", clockOf(0))
        assertEquals("00:09", clockOf(9))
        assertEquals("01:05", clockOf(65))
        assertEquals("09:59", clockOf(599))
        assertEquals("61:01", clockOf(3661))
    }
}
