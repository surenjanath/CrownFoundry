package com.surenjanath.crownfoundry.daily

import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.BLACK
import com.surenjanath.crownfoundry.engine.Board
import com.surenjanath.crownfoundry.offline.DailyChallenge
import com.surenjanath.crownfoundry.offline.LocalMatch
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyChallengeTest {

    private fun attempt(date: String, winner: String?) = LocalMatch(
        localId = date,
        matchId = date,
        difficulty = DailyChallenge.DIFFICULTY,
        startedAt = 1,
        finishedAt = if (winner == null) null else 2,
        winner = winner,
        daily = date
    )

    // --- the position -----------------------------------------------------------------------

    /** The whole design rests on this: no server, and two phones agree. */
    @Test
    fun `the same date always gives the same position`() {
        val a = DailyChallenge.positionFor("2026-09-01").toFen()
        val b = DailyChallenge.positionFor("2026-09-01").toFen()
        assertEquals(a, b)
    }

    @Test
    fun `different dates give different positions`() {
        val fens = (1..14).map {
            DailyChallenge.positionFor(LocalDate.of(2026, 9, it).toString()).toFen()
        }
        assertTrue("only ${fens.toSet().size} distinct positions in a fortnight",
            fens.toSet().size >= 12)
    }

    @Test
    fun `every position is live and the player is to move`() {
        for (day in 1..30) {
            val board = DailyChallenge.positionFor(LocalDate.of(2026, 9, day).toString())
            assertNull("day $day is already decided", board.winner())
            assertTrue("day $day has no legal moves", board.legalMoves().isNotEmpty())
            // The human plays Black, and a challenge that opened on the engine's move would hand
            // half the field a different game.
            assertEquals("day $day does not start on the player's move", BLACK, board.sideToMove)
        }
    }

    /** `RULES` is the standard variant, so the opening it falls back to is the ordinary one. */
    @Test
    fun `an unparseable key falls back to the opening rather than throwing`() {
        assertEquals(Board.initial().toFen(), DailyChallenge.positionFor("not-a-date").toFen())
    }

    @Test
    fun `the challenge is always played under standard rules`() {
        assertTrue(DailyChallenge.RULES.flyingKings)
        assertTrue(DailyChallenge.RULES.menCaptureBackwards)
        assertTrue(DailyChallenge.RULES.mandatoryCapture)
    }

    /** Adaptive would be a different opponent per player, so the comparison would mean nothing. */
    @Test
    fun `the challenge is never played at adaptive`() {
        assertEquals("hard", DailyChallenge.DIFFICULTY)
    }

    // --- status -----------------------------------------------------------------------------

    @Test
    fun `an untouched day has no attempt and no streak`() {
        val status = DailyChallenge.statusOf(emptyList(), "2026-09-10")
        assertNull(status.attempt)
        assertFalse(status.isPlayed)
        assertFalse(status.isWon)
        assertEquals(0, status.streak)
    }

    @Test
    fun `an unfinished attempt reads as in progress`() {
        val status = DailyChallenge.statusOf(
            listOf(attempt("2026-09-10", null)), "2026-09-10"
        )
        assertTrue(status.isInProgress)
        assertFalse(status.isPlayed)
    }

    @Test
    fun `a finished attempt is played, win or lose`() {
        val won = DailyChallenge.statusOf(listOf(attempt("2026-09-10", Side.HUMAN)), "2026-09-10")
        assertTrue(won.isPlayed)
        assertTrue(won.isWon)

        val lost = DailyChallenge.statusOf(listOf(attempt("2026-09-10", Side.AI)), "2026-09-10")
        assertTrue(lost.isPlayed)
        assertFalse(lost.isWon)
    }

    @Test
    fun `the streak counts consecutive wins back from today`() {
        val played = listOf(
            attempt("2026-09-08", Side.HUMAN),
            attempt("2026-09-09", Side.HUMAN),
            attempt("2026-09-10", Side.HUMAN)
        )
        assertEquals(3, DailyChallenge.statusOf(played, "2026-09-10").streak)
    }

    @Test
    fun `a gap breaks the streak`() {
        val played = listOf(
            attempt("2026-09-06", Side.HUMAN),
            // 7th missed
            attempt("2026-09-08", Side.HUMAN),
            attempt("2026-09-09", Side.HUMAN),
            attempt("2026-09-10", Side.HUMAN)
        )
        assertEquals(3, DailyChallenge.statusOf(played, "2026-09-10").streak)
    }

    @Test
    fun `a lost day breaks the streak`() {
        val played = listOf(
            attempt("2026-09-08", Side.HUMAN),
            attempt("2026-09-09", Side.AI),
            attempt("2026-09-10", Side.HUMAN)
        )
        assertEquals(1, DailyChallenge.statusOf(played, "2026-09-10").streak)
    }

    /**
     * A streak should not read as broken at one minute past midnight, before the player has had
     * any chance to keep it.
     */
    @Test
    fun `an unplayed today does not break yesterday's streak`() {
        val played = listOf(
            attempt("2026-09-08", Side.HUMAN),
            attempt("2026-09-09", Side.HUMAN)
        )
        val status = DailyChallenge.statusOf(played, "2026-09-10")
        assertEquals(2, status.streak)
        assertNull(status.attempt)
    }

    @Test
    fun `losing today does break the streak`() {
        val played = listOf(
            attempt("2026-09-08", Side.HUMAN),
            attempt("2026-09-09", Side.HUMAN),
            attempt("2026-09-10", Side.AI)
        )
        assertEquals(0, DailyChallenge.statusOf(played, "2026-09-10").streak)
    }

    @Test
    fun `ordinary games are not daily attempts`() {
        val ordinary = LocalMatch(
            localId = "x", matchId = "x", winner = Side.HUMAN, startedAt = 1, finishedAt = 2
        )
        val status = DailyChallenge.statusOf(listOf(ordinary), "2026-09-10")
        assertNull(status.attempt)
        assertEquals(0, status.totalSolved)
        assertFalse(ordinary.isDaily)
    }

    @Test
    fun `total solved counts every won challenge, streak or not`() {
        val played = listOf(
            attempt("2026-09-01", Side.HUMAN),
            attempt("2026-09-05", Side.HUMAN),
            attempt("2026-09-06", Side.AI),
            attempt("2026-09-10", Side.HUMAN)
        )
        assertEquals(3, DailyChallenge.statusOf(played, "2026-09-10").totalSolved)
    }

    @Test
    fun `the headline says what state the day is in`() {
        val keys = listOf(
            DailyChallenge.statusOf(emptyList(), "2026-09-10"),
            DailyChallenge.statusOf(listOf(attempt("2026-09-10", null)), "2026-09-10"),
            DailyChallenge.statusOf(listOf(attempt("2026-09-10", Side.HUMAN)), "2026-09-10"),
            DailyChallenge.statusOf(listOf(attempt("2026-09-10", Side.AI)), "2026-09-10")
        ).map { it.headline }

        assertEquals("each state should read differently", keys.size, keys.toSet().size)
    }

    @Test
    fun `today's key is an ISO date`() {
        assertNotEquals(null, LocalDate.parse(DailyChallenge.today()))
    }
}
