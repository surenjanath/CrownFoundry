package com.surenjanath.crownfoundry.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scoring a finished game.
 *
 * The load-bearing claim of this file is a negative one: the value head's absolute output means
 * nothing, so nothing here may depend on it. Every number the analysis reports has to come from a
 * difference taken inside one position. The swing curve is checked against hand-computed sums for
 * exactly that reason - it is the one graphic in the app built on this code, and the previous
 * version of it plotted absolute values and drew a sawtooth.
 */
class AnalysisTest {

    private fun move(
        ply: Int,
        side: Int,
        loss: Float,
        notation: String = "11-15",
        best: String = "11-15",
        alternatives: List<ScoredMove> = listOf(ScoredMove(best, 1f), ScoredMove(notation, 1f - loss))
    ) = AnalysedMove(
        ply = ply,
        side = side,
        notation = notation,
        fen = Board.initial().toFen(),
        best = best,
        loss = loss,
        quality = QualityThresholds.of(loss),
        alternatives = alternatives
    )

    // --- thresholds ---------------------------------------------------------------------------

    @Test
    fun `a move is labelled by what it gave up`() {
        assertEquals(MoveQuality.Best, QualityThresholds.of(0f))
        assertEquals(MoveQuality.Best, QualityThresholds.of(QualityThresholds.BEST))
        assertEquals(MoveQuality.Good, QualityThresholds.of(0.2f))
        assertEquals(MoveQuality.Inaccuracy, QualityThresholds.of(0.5f))
        assertEquals(MoveQuality.Mistake, QualityThresholds.of(1.6f))
        assertEquals(MoveQuality.Blunder, QualityThresholds.of(3f))
    }

    @Test
    fun `only mistakes and blunders are worth interrupting someone about`() {
        assertFalse(MoveQuality.Best.isError)
        assertFalse(MoveQuality.Good.isError)
        assertFalse(MoveQuality.Inaccuracy.isError)
        assertTrue(MoveQuality.Mistake.isError)
        assertTrue(MoveQuality.Blunder.isError)
    }

    // --- the swing curve ----------------------------------------------------------------------

    @Test
    fun `the swing curve is the running total of ground given away`() {
        val analysis = GameAnalysis(
            listOf(
                move(1, BLACK, 0.834f),
                move(2, WHITE, 0f),
                move(3, BLACK, 0f),
                move(4, WHITE, 0.244f),
                move(5, BLACK, 1.615f)
            ),
            depth = 4
        )

        // Starts level, falls on Black's losses, rises on White's - hand-computed.
        val expected = listOf(0f, -0.834f, -0.834f, -0.834f, -0.590f, -2.205f)
        val actual = analysis.swingSeries

        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, value ->
            assertEquals("point $index", value, actual[index], 1e-4f)
        }
    }

    @Test
    fun `a game nobody gave anything away in stays level`() {
        val analysis = GameAnalysis(
            listOf(move(1, BLACK, 0f), move(2, WHITE, 0f)),
            depth = 4
        )

        assertEquals(listOf(0f, 0f, 0f), analysis.swingSeries)
    }

    @Test
    fun `the curve has one point per ply plus the opening`() {
        val analysis = GameAnalysis((1..7).map { move(it, if (it % 2 == 1) BLACK else WHITE, 0.1f) }, 4)

        assertEquals(8, analysis.swingSeries.size)
        assertEquals(0f, analysis.swingSeries.first(), 1e-6f)
    }

    @Test
    fun `an empty game has a curve with nothing on it`() {
        val analysis = GameAnalysis(emptyList(), depth = 4)

        assertTrue(analysis.isEmpty)
        assertEquals(listOf(0f), analysis.swingSeries)
    }

    // --- the summary numbers --------------------------------------------------------------------

    @Test
    fun `accuracy counts best and good moves, per side`() {
        val analysis = GameAnalysis(
            listOf(
                move(1, BLACK, 0f),      // Best
                move(2, WHITE, 0.2f),    // Good
                move(3, BLACK, 3f),      // Blunder
                move(4, WHITE, 0f)       // Best
            ),
            depth = 4
        )

        assertEquals(0.5f, analysis.accuracy(BLACK), 1e-6f)
        assertEquals(1f, analysis.accuracy(WHITE), 1e-6f)
        assertEquals(1, analysis.count(BLACK, MoveQuality.Blunder))
        assertEquals(0, analysis.count(WHITE, MoveQuality.Blunder))
        assertEquals(1, analysis.errors(BLACK).size)
        assertTrue(analysis.errors(WHITE).isEmpty())
    }

    @Test
    fun `a side that never moved has no accuracy rather than a divide by zero`() {
        val analysis = GameAnalysis(listOf(move(1, BLACK, 0f)), depth = 4)

        assertEquals(0f, analysis.accuracy(WHITE), 1e-6f)
        assertEquals(0f, analysis.averageLoss(WHITE), 1e-6f)
    }

    @Test
    fun `the turning point is the costliest error, and only an error`() {
        val analysis = GameAnalysis(
            listOf(move(1, BLACK, 0.9f), move(2, WHITE, 4f), move(3, BLACK, 3f)),
            depth = 4
        )

        assertEquals(2, analysis.turningPoint()?.ply)
        assertEquals(3, analysis.turningPoint(BLACK)?.ply)

        // 0.9 is an inaccuracy, not an error, so there is no turning point to point at.
        assertNull(GameAnalysis(listOf(move(1, BLACK, 0.9f)), 4).turningPoint())
    }

    // --- puzzles ----------------------------------------------------------------------------------

    @Test
    fun `only your errors with a clearly best answer become puzzles`() {
        val analysis = GameAnalysis(
            listOf(
                // An error, and the best move is clearly best: qualifies.
                move(1, BLACK, 3f, notation = "9-13", best = "11-15"),
                // White's error - not the player's to practise.
                move(2, WHITE, 4f, notation = "22-17", best = "23-19"),
                // An error, but the second choice is just as good: a right answer would be
                // marked wrong, so it is left out.
                move(
                    3, BLACK, 3f, notation = "8-11", best = "10-14",
                    alternatives = listOf(
                        ScoredMove("10-14", 1f),
                        ScoredMove("12-16", 0.9f),
                        ScoredMove("8-11", -2f)
                    )
                ),
                // Only an inaccuracy.
                move(4, BLACK, 0.5f, notation = "6-10", best = "7-11")
            ),
            depth = 4
        )

        val seeds = puzzleSeedsFrom(analysis, BLACK)

        assertEquals(1, seeds.size)
        assertEquals("11-15", seeds.first().best)
        assertEquals("9-13", seeds.first().played)
        assertEquals(1, seeds.first().ply)
    }

    @Test
    fun `a forced move is not a puzzle`() {
        val analysis = GameAnalysis(
            listOf(move(1, BLACK, 3f, alternatives = listOf(ScoredMove("11-15", 1f)))),
            depth = 4
        )

        assertTrue(puzzleSeedsFrom(analysis, BLACK).isEmpty())
    }

    @Test
    fun `the worst mistakes come first, and the list is capped`() {
        val analysis = GameAnalysis(
            listOf(
                move(1, BLACK, 2.6f, notation = "a", best = "A"),
                move(3, BLACK, 5f, notation = "b", best = "B"),
                move(5, BLACK, 3f, notation = "c", best = "C"),
                move(7, BLACK, 4f, notation = "d", best = "D")
            ),
            depth = 4
        )

        val seeds = puzzleSeedsFrom(analysis, BLACK, limit = 2)

        assertEquals(listOf("B", "D"), seeds.map { it.best })
    }

    // --- end to end -------------------------------------------------------------------------------

    @Test
    fun `a replayed game is scored ply by ply`() = runBlocking {
        val net = QNetwork(intArrayOf(FEATURE_SIZE, 24, 1)).apply { randomise(seed = 11) }
        val moves = listOf("11-15", "22-18", "15x22", "26x17", "9-14")
        val plies = replayMoves(moves)

        var lastDone = 0
        val analysis = GameAnalyser(net, depth = 2, nodeBudget = 400)
            .analyse(plies) { done, _ -> lastDone = done }

        assertEquals(moves.size, analysis.moves.size)
        assertEquals(moves.size, lastDone)
        assertEquals(moves, analysis.moves.map { it.notation })
        // Black moves first, and the sides alternate.
        assertEquals(listOf(BLACK, WHITE, BLACK, WHITE, BLACK), analysis.moves.map { it.side })
        assertEquals((1..moves.size).toList(), analysis.moves.map { it.ply })

        // Loss is a difference against the best move, so it is never negative and is zero exactly
        // when the move played was the one the engine would have chosen.
        analysis.moves.forEach { move ->
            assertTrue("loss ${move.loss}", move.loss >= 0f)
            if (move.notation == move.best) assertEquals(0f, move.loss, 1e-5f)
            assertTrue(move.alternatives.isNotEmpty())
        }

        assertEquals(moves.size + 1, analysis.swingSeries.size)
    }

    @Test
    fun `a game with no moves scores as nothing`() = runBlocking {
        val net = QNetwork(intArrayOf(FEATURE_SIZE, 24, 1)).apply { randomise(seed = 3) }

        val analysis = GameAnalyser(net).analyse(emptyList())

        assertTrue(analysis.isEmpty)
        assertNull(analysis.turningPoint())
    }
}
