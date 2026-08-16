package com.surenjanath.crownfoundry.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field-by-field, against the bodies in ARCHITECTURE.md §5. */
class ResponseParsingTest : MockBackendTest() {

    @Test
    fun health() = runTest {
        serving(Fixtures.HEALTH)

        val health = CrownFoundryClient.health().succeeded()

        assertTrue(health.ok)
        assertEquals("1.0.0", health.version)
        assertTrue(health.ollama.available)
        assertEquals("qwen3.5:9b", health.ollama.model)
        assertEquals(12, health.policyVersion)
    }

    @Test
    fun `match start`() = runTest {
        serving(Fixtures.MATCH_START)

        val match = CrownFoundryClient.startMatch().succeeded()

        assertEquals("3f2b1c0a-0000-4000-8000-000000000001", match.matchId)
        assertEquals(Fixtures.OPENING_FEN, match.initialBoard)
        assertEquals(Fixtures.OPENING_FEN, match.board.fen)
        assertEquals(Side.BLACK, match.board.sideToMove)
        assertEquals(0, match.turnNumber)
        assertFalse(match.isFinished)

        assertEquals(2, match.board.pieces.size)
        assertEquals(1, match.board.pieces[0].square)
        assertTrue(match.board.pieces[0].isBlack)
        assertFalse(match.board.pieces[0].king)
        assertEquals(32, match.board.pieces[1].square)
        assertTrue(match.board.pieces[1].isWhite)
        assertTrue(match.board.pieces[1].king)

        assertEquals(2, match.legalMoves.size)
        assertEquals("11-15", match.legalMoves[0].notation)
        assertEquals(11, match.legalMoves[0].from)
        assertEquals(15, match.legalMoves[0].to)
        assertFalse(match.legalMoves[0].isJump)
        assertEquals("11x18x25", match.legalMoves[1].notation)
        assertEquals(listOf(15, 22), match.legalMoves[1].captures)
        assertTrue(match.legalMoves[1].isJump)

        assertEquals(12, match.ai.policyVersion)
        assertEquals(340, match.ai.gamesTrained)
        assertEquals(0.46, match.ai.winRate, 1e-9)
        assertEquals(1180, match.ai.elo)
    }

    @Test
    fun `match detail carries history status and winner`() = runTest {
        serving(Fixtures.MATCH_DETAIL)

        val match = CrownFoundryClient.match("m-1").succeeded()

        assertEquals("finished", match.status)
        assertTrue(match.isFinished)
        assertEquals(Side.BLACK, match.winner)
        assertEquals(3, match.turnNumber)
        assertEquals(Side.WHITE, match.board.sideToMove)

        assertEquals(2, match.history.size)
        assertEquals(1, match.history[0].turn)
        assertEquals(Side.BLACK, match.history[0].side)
        assertEquals("11-15", match.history[0].move)
        assertNull(match.history[0].reasoning)
        assertEquals("Trading into the centre.", match.history[1].reasoning)
    }

    @Test
    fun `move result`() = runTest {
        serving(Fixtures.MOVE_RESULT)

        val result = CrownFoundryClient.playMove("m-1", "11-15").succeeded()

        assertTrue(result.ok)
        assertTrue(result.valid)
        assertFalse(result.gameOver)
        assertNull(result.winner)
        assertEquals("W:W21,22:B1,2", result.boardState)
        assertEquals("W:W21,22:B1,2", result.board.fen)
        assertEquals(1, result.legalMoves.size)
        assertEquals("23-18", result.legalMoves[0].notation)
        assertEquals("11-15", result.appliedMove.notation)
        assertEquals(emptyList<Int>(), result.appliedMove.captures)
        assertFalse(result.appliedMove.crowned)
        assertEquals(1, result.turnNumber)
    }

    @Test
    fun `ai turn`() = runTest {
        serving(Fixtures.AI_TURN)

        val turn = CrownFoundryClient.generateAiTurn("m-1").succeeded()

        assertEquals("24-19", turn.aiMove)
        assertEquals(
            "Holding the centre so your right flank has nothing to trade into.",
            turn.aiReasoning
        )
        assertEquals("ollama", turn.reasoningSource)
        assertTrue(turn.spokeThroughOllama)
        assertEquals("B:W21,19:B1,2", turn.newBoard)
        assertEquals(Side.BLACK, turn.board.sideToMove)
        assertEquals(0.41, turn.evaluation.qValue, 1e-9)
        assertEquals(0.78, turn.evaluation.confidence, 1e-9)
        assertEquals(2, turn.evaluation.considered.size)
        assertEquals("24-19", turn.evaluation.considered[0].notation)
        assertEquals(0.41, turn.evaluation.considered[0].q, 1e-9)
        assertEquals("23-18", turn.evaluation.considered[1].notation)
        assertEquals(0.36, turn.evaluation.considered[1].q, 1e-9)
        assertFalse(turn.gameOver)
        assertNull(turn.winner)
        assertEquals(2, turn.turnNumber)
        assertEquals(emptyList<Int>(), turn.captures)
        assertFalse(turn.crowned)
    }

    @Test
    fun `match list`() = runTest {
        serving(Fixtures.MATCH_LIST)

        val list = CrownFoundryClient.matches().succeeded()

        assertEquals(1, list.matches.size)
        val summary = list.matches.single()
        assertEquals("3f2b1c0a-0000-4000-8000-000000000001", summary.matchId)
        assertEquals("2026-08-16T11:00:00Z", summary.startTime)
        assertNull(summary.endTime)
        assertEquals("active", summary.status)
        assertNull(summary.winner)
        assertEquals(12, summary.totalTurns)
        assertEquals("adaptive", summary.difficulty)
        assertEquals(2, summary.aiCaptures)
        assertEquals(3, summary.humanCaptures)
    }

    @Test
    fun resign() = runTest {
        serving(Fixtures.RESIGN)

        val resign = CrownFoundryClient.resign("m-1").succeeded()

        assertTrue(resign.gameOver)
        assertEquals(Side.WHITE, resign.winner)
    }

    @Test
    fun `analytics summary parses without an ok flag`() = runTest {
        serving(Fixtures.SUMMARY)

        val summary = CrownFoundryClient.summary().succeeded()

        assertEquals(41, summary.totalMatches)
        assertEquals(19, summary.aiWins)
        assertEquals(20, summary.humanWins)
        assertEquals(2, summary.draws)
        assertEquals(0.463, summary.aiWinRate, 1e-9)
        assertEquals(1180, summary.elo)
        assertEquals(12, summary.policyVersion)
        assertNull(summary.gamesTo50Percent)
        assertEquals(38.2, summary.avgTurns, 1e-9)
        assertEquals(0.07, summary.mistakeRepetitionRate, 1e-9)
        assertEquals(1.12, summary.captureRatio, 1e-9)
    }

    @Test
    fun `ai performance series`() = runTest {
        serving(Fixtures.PERFORMANCE)

        val performance = CrownFoundryClient.performance().succeeded()

        assertEquals(41, performance.summary.totalMatches)

        val winRate = performance.winRateSeries.single()
        assertEquals(1, winRate.matchIndex)
        assertEquals(0.0, winRate.cumulativeWinRate, 1e-9)
        assertEquals(0.0, winRate.rollingWinRate, 1e-9)
        assertEquals("loss", winRate.result)

        assertEquals(44, performance.gameLengthSeries.single().turns)

        val mistake = performance.mistakeSeries.single()
        assertEquals(2, mistake.repeatedMistakes)
        assertEquals(0.09, mistake.rate, 1e-9)

        val capture = performance.captureSeries.single()
        assertEquals(4, capture.aiCaptures)
        assertEquals(7, capture.humanCaptures)

        val training = performance.training.single()
        assertEquals(3, training.policyVersion)
        assertEquals(0.11, training.loss, 1e-9)
        assertEquals(120, training.gamesTrained)
        assertEquals("2026-08-16T11:00:00Z", training.updatedAt)
    }

    // --- forward compatibility -------------------------------------------------------------------

    @Test
    fun `unknown fields from a newer backend are ignored`() = runTest {
        serving(
            """
            {"ok": true, "match_id": "m-9", "board": {"fen": "x", "side_to_move": "black",
              "pieces": [{"square": 3, "side": "black", "king": false, "frozen": true}],
              "hash": "deadbeef"},
             "legal_moves": [{"notation": "11-15", "from": 11, "to": 15, "captures": [],
                              "score": 0.9}],
             "turn_number": 4, "ai": {"policy_version": 2, "temperature": 0.3},
             "opening_name": "Old Faithful", "server_time": "2026-08-16T11:00:00Z"}
            """.trimIndent()
        )

        val match = CrownFoundryClient.startMatch().succeeded()

        assertEquals("m-9", match.matchId)
        assertEquals(4, match.turnNumber)
        assertEquals(3, match.board.pieces.single().square)
        assertEquals("11-15", match.legalMoves.single().notation)
        assertEquals(2, match.ai.policyVersion)
    }

    @Test
    fun `missing optional fields fall back to the DTO defaults`() = runTest {
        serving("""{"ok": true, "match_id": "m-9"}""")

        val match = CrownFoundryClient.startMatch().succeeded()

        assertEquals("m-9", match.matchId)
        assertNull(match.initialBoard)
        assertEquals("", match.board.fen)
        assertEquals(Side.BLACK, match.board.sideToMove)
        assertEquals(emptyList<PieceDto>(), match.board.pieces)
        assertEquals(emptyList<MoveDto>(), match.legalMoves)
        assertEquals(0, match.turnNumber)
        assertEquals("active", match.status)
        assertNull(match.winner)
        assertEquals("adaptive", match.difficulty)
        assertEquals(emptyList<HistoryEntryDto>(), match.history)
        assertEquals(AiStatusDto(), match.ai)
        assertEquals(1200, match.ai.elo)
    }

    @Test
    fun `a sparse ai turn still yields a usable turn`() = runTest {
        serving("""{"ok": true, "ai_move": "24-19", "new_board": "B:W19:B1"}""")

        val turn = CrownFoundryClient.generateAiTurn("m-1").succeeded()

        assertEquals("24-19", turn.aiMove)
        assertEquals("", turn.aiReasoning)
        assertEquals("heuristic", turn.reasoningSource)
        assertFalse(turn.spokeThroughOllama)
        assertEquals(0.0, turn.evaluation.qValue, 1e-9)
        assertEquals(emptyList<ScoredMoveDto>(), turn.evaluation.considered)
        assertFalse(turn.gameOver)
        assertEquals(0, turn.turnNumber)
    }

    @Test
    fun `an empty analytics summary is all defaults`() = runTest {
        serving("{}")

        val summary = CrownFoundryClient.summary().succeeded()

        assertEquals(AnalyticsSummaryDto(), summary)
        assertEquals(1200, summary.elo)
        assertNull(summary.gamesTo50Percent)
    }
}
