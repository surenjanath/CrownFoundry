package com.surenjanath.crownfoundry.matches

import com.surenjanath.crownfoundry.api.HistoryEntryDto
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.AnalysedMove
import com.surenjanath.crownfoundry.engine.BLACK
import com.surenjanath.crownfoundry.engine.GameAnalysis
import com.surenjanath.crownfoundry.engine.MoveQuality
import com.surenjanath.crownfoundry.engine.Pdn
import com.surenjanath.crownfoundry.engine.QualityThresholds
import com.surenjanath.crownfoundry.engine.ScoredMove
import com.surenjanath.crownfoundry.engine.WHITE
import com.surenjanath.crownfoundry.game.FakeCheckersApi
import com.surenjanath.crownfoundry.game.Fixtures
import com.surenjanath.crownfoundry.ui.screens.matches.MatchAnalyser
import com.surenjanath.crownfoundry.ui.screens.matches.PdnExport
import com.surenjanath.crownfoundry.ui.screens.matches.ReviewAnalysis
import com.surenjanath.crownfoundry.ui.screens.matches.ReviewStateHolder
import com.surenjanath.crownfoundry.ui.screens.matches.costOf
import com.surenjanath.crownfoundry.ui.screens.matches.replayOf
import com.surenjanath.crownfoundry.ui.screens.matches.summaryOf
import com.surenjanath.crownfoundry.ui.screens.matches.verdictOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Post-game analysis as the review screen sees it: replaying a stored match, exporting it, and
 * the states the screen draws while the engine works.
 *
 * The engine's own scoring is proven in `:engine`. What is proven here is the wiring - that a
 * match off the wire replays under its own rules, that a verdict lands against the move it was
 * computed for, and that a phone with no engine installed is told so rather than left waiting.
 */
class ReviewAnalysisTest {

    private val playedMoves = listOf("11-15", "23-19", "8-11", "22-17")

    private fun match(
        moves: List<String> = playedMoves,
        winner: String? = Side.HUMAN,
        status: String = "finished",
        rules: MatchRulesDto? = null
    ) = MatchDto(
        matchId = "match-1",
        initialBoard = Fixtures.INITIAL_FEN,
        turnNumber = moves.size,
        status = status,
        winner = winner,
        difficulty = "adaptive",
        rules = rules,
        history = moves.mapIndexed { index, notation ->
            HistoryEntryDto(
                turn = index / 2 + 1,
                side = if (index % 2 == 0) Side.HUMAN else Side.AI,
                move = notation,
                fen = ""
            )
        }
    )

    private fun scored(
        ply: Int,
        side: Int,
        notation: String,
        best: String,
        loss: Float
    ) = AnalysedMove(
        ply = ply,
        side = side,
        notation = notation,
        fen = Fixtures.INITIAL_FEN,
        best = best,
        loss = loss,
        quality = QualityThresholds.of(loss),
        evaluation = 0f,
        alternatives = listOf(ScoredMove(best, 1f), ScoredMove(notation, 1f - loss))
    )

    private fun analysisOf(vararg moves: AnalysedMove) =
        GameAnalysis(moves.toList(), openingEvaluation = 0f, depth = 4)

    // --- replay ---------------------------------------------------------------------------

    @Test
    fun `replays a stored match into plies`() {
        val plies = replayOf(match())

        assertEquals(playedMoves.size, plies.size)
        assertEquals(playedMoves, plies.map { it.move.notation() })
        // Black moves first, and Black is the human.
        assertEquals(BLACK, plies.first().side)
        assertEquals(WHITE, plies[1].side)
    }

    @Test
    fun `replay stops at the first move the rules reject`() {
        val plies = replayOf(match(moves = listOf("11-15", "9-14", "8-11")))

        // 9-14 is not White's to play, so nothing after it is replayable either.
        assertEquals(1, plies.size)
    }

    @Test
    fun `replay honours the match's own rules`() {
        val english = MatchRulesDto(
            flyingKings = false,
            menCaptureBackwards = false,
            mandatoryCapture = true
        )

        val plies = replayOf(match(rules = english))

        assertTrue(plies.isNotEmpty())
        assertEquals(false, plies.first().board.rules.flyingKings)
    }

    @Test
    fun `a match with no moves replays as nothing`() {
        assertTrue(replayOf(match(moves = emptyList())).isEmpty())
    }

    // --- PDN export -----------------------------------------------------------------------

    @Test
    fun `exports the played moves as PDN`() {
        val pdn = PdnExport.of(match())

        assertEquals(playedMoves, Pdn.movesOf(pdn))
    }

    @Test
    fun `a human win is written from White's side`() {
        // The human is Black, so a game you won is 0-1 in PDN's spelling.
        assertEquals(Pdn.RESULT_BLACK, Pdn.tagsOf(PdnExport.of(match()))["Result"])
        assertEquals(
            Pdn.RESULT_WHITE,
            Pdn.tagsOf(PdnExport.of(match(winner = Side.AI)))["Result"]
        )
        assertEquals(
            Pdn.RESULT_DRAW,
            Pdn.tagsOf(PdnExport.of(match(winner = Side.DRAW)))["Result"]
        )
    }

    @Test
    fun `an unfinished game exports as unfinished whatever the winner field says`() {
        val tags = Pdn.tagsOf(PdnExport.of(match(status = "active", winner = null)))

        assertEquals(Pdn.RESULT_UNFINISHED, tags["Result"])
    }

    @Test
    fun `there is nothing to export before a move is played`() {
        assertTrue(PdnExport.isExportable(match()))
        assertFalse(PdnExport.isExportable(match(moves = emptyList())))
        assertFalse(PdnExport.isExportable(null))
    }

    // --- the states the screen draws --------------------------------------------------------

    private fun holderWith(
        match: MatchDto,
        analyser: MatchAnalyser
    ): ReviewStateHolder {
        val api = FakeCheckersApi().apply { matchOutcome = Outcome.Success(match) }
        return ReviewStateHolder(api, match.matchId, analyser)
    }

    @Test
    fun `a scored game ends Ready and lines up with the scrubber`() = runTest {
        val analysis = analysisOf(
            scored(1, BLACK, "11-15", "11-15", 0f),
            scored(2, WHITE, "23-19", "22-17", 3f)
        )

        val holder = holderWith(match(), { _, onProgress ->
            onProgress(1, 2)
            onProgress(2, 2)
            analysis
        })

        holder.load()
        holder.analyse()

        val state = holder.analysis
        assertTrue(state is ReviewAnalysis.Ready)
        // Ply 0 is the opening, so the first move is at index 1.
        assertNull(state.moveAt(0))
        assertEquals("11-15", state.moveAt(1)?.notation)
        assertEquals("23-19", state.moveAt(2)?.notation)
        assertNull(state.moveAt(3))
    }

    @Test
    fun `no engine on the phone is an explanation, not a failure`() = runTest {
        val holder = holderWith(match(), { _, _ -> null })

        holder.load()
        holder.analyse()

        val state = holder.analysis
        assertTrue(state is ReviewAnalysis.Unavailable)
        assertTrue((state as ReviewAnalysis.Unavailable).reason.contains("Settings"))
    }

    @Test
    fun `an engine that throws is reported rather than swallowed`() = runTest {
        val holder = holderWith(match(), { _, _ -> throw IllegalStateException("weights gone") })

        holder.load()
        holder.analyse()

        assertTrue(holder.analysis is ReviewAnalysis.Failed)
    }

    @Test
    fun `a game with no moves is never sent to the engine`() = runTest {
        var calls = 0
        val holder = holderWith(match(moves = emptyList()), { _, _ ->
            calls += 1
            null
        })

        holder.load()
        holder.analyse()

        assertEquals(0, calls)
        assertTrue(holder.analysis is ReviewAnalysis.Unavailable)
    }

    @Test
    fun `analysing twice does not re-run the search`() = runTest {
        var calls = 0
        val holder = holderWith(match(), { _, _ ->
            calls += 1
            analysisOf(scored(1, BLACK, "11-15", "11-15", 0f))
        })

        holder.load()
        holder.analyse()
        holder.analyse()

        assertEquals(1, calls)
    }

    // --- what the player is told -------------------------------------------------------------

    @Test
    fun `a blunder names the move that was there instead`() {
        val move = scored(3, BLACK, "8-11", "9-14", 3.2f)

        assertEquals(MoveQuality.Blunder, move.quality)
        assertEquals("Blunder · best was 9-14", verdictOf(move))
        assertNotNull(costOf(move))
    }

    @Test
    fun `the best move is not given a cost to read`() {
        val move = scored(1, BLACK, "11-15", "11-15", 0f)

        assertEquals("Best move", verdictOf(move))
        assertNull(costOf(move))
    }

    @Test
    fun `the summary counts only your errors and finds the turning point`() {
        val analysis = analysisOf(
            scored(1, BLACK, "11-15", "11-15", 0f),
            scored(2, WHITE, "23-19", "22-17", 4f),
            scored(3, BLACK, "8-11", "9-14", 3.2f),
            scored(4, WHITE, "22-17", "22-17", 0f)
        )

        val summary = summaryOf(analysis)!!

        assertEquals(1, summary.blunders)
        assertEquals(0, summary.mistakes)
        // One of Black's two moves was best, so 50%.
        assertEquals(50, summary.accuracy)
        assertEquals(50, summary.opponentAccuracy)
        // White's 4.0 is the biggest loss in the game, and it was not the player's.
        assertEquals(2, summary.turningPoint?.ply)
        assertFalse(summary.turningPointWasYours)
        assertTrue(summary.turningPointLine!!.startsWith("Its move 2"))
    }

    @Test
    fun `a clean game says so instead of listing nothing`() {
        val summary = summaryOf(
            analysisOf(
                scored(1, BLACK, "11-15", "11-15", 0f),
                scored(2, WHITE, "23-19", "23-19", 0f)
            )
        )!!

        assertEquals(0, summary.blunders)
        assertNull(summary.turningPoint)
        assertTrue(summary.detail.contains("Well played"))
    }
}
