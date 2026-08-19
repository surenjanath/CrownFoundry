package com.surenjanath.crownfoundry.game

import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.ResignDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.screens.game.GameState
import com.surenjanath.crownfoundry.ui.screens.game.Hinter
import com.surenjanath.crownfoundry.ui.screens.game.present
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asking the engine what you should play.
 *
 * The rules that matter are about when a hint is allowed to exist. Advice belongs to exactly one
 * position: offered only while the board is yours to touch, and thrown away the instant anything
 * moves, because an arrow pointing at a piece that has already gone is worse than no arrow.
 */
class HintTest {

    private fun stateWith(api: FakeCheckersApi, hinter: Hinter) =
        GameState(api = api, difficulty = "adaptive", playerId = null, hinter = hinter)

    @Test
    fun `the engine's move becomes the hint`() = runTest {
        val state = stateWith(FakeCheckersApi()) { _, _ -> "11-15" }
        state.begin(null)

        state.requestHint()

        assertEquals("11-15", state.hint)
        assertFalse(state.hinting)
        assertFalse(state.hintUnavailable)
    }

    @Test
    fun `a device with no engine says so rather than doing nothing`() = runTest {
        val state = stateWith(FakeCheckersApi()) { _, _ -> null }
        state.begin(null)

        state.requestHint()

        assertNull(state.hint)
        assertTrue(state.hintUnavailable)
    }

    @Test
    fun `an engine that throws leaves the board alone`() = runTest {
        val state = stateWith(FakeCheckersApi()) { _, _ -> error("weights gone") }
        state.begin(null)

        state.requestHint()

        assertNull(state.hint)
        assertTrue(state.hintUnavailable)
        assertFalse(state.hinting)
    }

    @Test
    fun `the hint is asked about the position actually on the board`() = runTest {
        var askedAbout: String? = null
        val state = stateWith(FakeCheckersApi()) { fen, _ ->
            askedAbout = fen
            "11-15"
        }
        state.begin(null)

        state.requestHint()

        assertEquals(state.fen, askedAbout)
    }

    @Test
    fun `moving throws the hint away`() = runTest {
        val state = stateWith(FakeCheckersApi()) { _, _ -> "11-15" }
        state.begin(null)
        state.requestHint()
        assertEquals("11-15", state.hint)

        state.play("11-15")

        // The position it was advice about no longer exists.
        assertNull(state.hint)
    }

    @Test
    fun `no hint while it is not your board to touch`() = runTest {
        var asked = 0
        val api = FakeCheckersApi().apply {
            resignOutcome = Outcome.Success(ResignDto(gameOver = true, winner = Side.AI))
        }
        val state = stateWith(api) { _, _ ->
            asked += 1
            "11-15"
        }
        state.begin(null)
        state.resign()

        state.requestHint()

        assertEquals(0, asked)
        assertNull(state.hint)
    }

    @Test
    fun `a device with no engine is told so on the bar, not left guessing`() = runTest {
        val state = stateWith(FakeCheckersApi()) { _, _ -> null }
        state.begin(null)

        assertNull(state.present().event)

        state.requestHint()

        assertEquals("No engine on this device to ask", state.present().event?.text)
    }

    @Test
    fun `putting the hint away leaves no trace of it`() = runTest {
        val state = stateWith(FakeCheckersApi()) { _, _ -> null }
        state.begin(null)
        state.requestHint()
        assertTrue(state.hintUnavailable)

        state.clearHint()

        assertNull(state.hint)
        assertFalse(state.hintUnavailable)
    }
}
