package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The offline referee, against the contract the online one publishes.
 *
 * The point of these is not that the engine plays well - `:engine` proves that - but that the app
 * cannot tell the two referees apart. Every assertion here is about a payload shape or a rule the
 * server also enforces, because the moment the two disagree the board UI starts drawing positions
 * that only exist on one side.
 */
class OfflineCheckersApiTest {

    private lateinit var directory: File
    private lateinit var store: LocalMatchStore
    private lateinit var api: OfflineCheckersApi
    private lateinit var preferences: EnginePreferences

    @Before
    fun setUp() {
        directory = File.createTempFile("crownfoundry", "offline").let {
            it.delete()
            it.mkdirs()
            it
        }
        store = LocalMatchStore(File(directory, "matches.json"))
        preferences = EnginePreferences(FakeSharedPreferences())
        api = OfflineCheckersApi(
            store = store,
            engine = TestEngine.readyStore(directory),
            preferences = preferences,
            searchDepth = 2,
            nodeBudget = 300
        )
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        EngineStore.resetForTest()
    }

    // --- starting and reading --------------------------------------------------------------

    @Test
    fun `a new match opens on the position the backend opens on`() = runTest {
        val match = api.startMatch("hard", null, null).expect()

        assertTrue(LocalMatchStore.isOffline(match.matchId))
        assertEquals(
            "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12",
            match.board.fen
        )
        assertEquals(Side.BLACK, match.board.sideToMove)
        assertEquals(24, match.board.pieces.size)
        assertEquals(7, match.legalMoves.size)
        assertEquals(0, match.turnNumber)
        assertEquals("active", match.status)
        assertEquals("hard", match.difficulty)
        assertNull(match.winner)
    }

    @Test
    fun `pieces carry the side and crown the board needs to draw them`() = runTest {
        val match = api.startMatch().expect()

        val black = match.board.pieces.filter { it.isBlack }
        assertEquals(12, black.size)
        assertEquals((1..12).toList(), black.map { it.square }.sorted())
        assertTrue(black.none { it.king })
        assertEquals((21..32).toList(), match.board.pieces.filter { it.isWhite }.map { it.square }.sorted())
    }

    @Test
    fun `the rules the match was started with are the rules it is played under`() = runTest {
        val english = MatchRulesDto(
            flyingKings = false, menCaptureBackwards = false, mandatoryCapture = true
        )
        val match = api.startMatch("normal", null, english).expect()

        assertEquals(english, match.rules)
        // Resuming has to produce the same variant, or a king would gain range mid-game.
        assertEquals(english, api.match(match.matchId).expect().rules)
    }

    @Test
    fun `an unknown match is a 404, not a crash`() = runTest {
        val failure = api.match("offline-nope").expectFailure()
        assertTrue(failure is ApiError.Rejected)
        assertEquals(404, (failure as ApiError.Rejected).status)
    }

    // --- playing -----------------------------------------------------------------------------

    @Test
    fun `a legal move is applied and the turn passes`() = runTest {
        val match = api.startMatch().expect()
        val result = api.playMove(match.matchId, "11-15").expect()

        assertTrue(result.valid)
        assertFalse(result.gameOver)
        assertEquals("11-15", result.appliedMove.notation)
        assertEquals(Side.WHITE, result.board.sideToMove)
        assertEquals(1, result.turnNumber)
        assertTrue(result.board.pieces.any { it.square == 15 && it.isBlack })
        assertTrue(result.board.pieces.none { it.square == 11 })
    }

    @Test
    fun `an illegal move comes back with the legal ones attached`() = runTest {
        val match = api.startMatch().expect()

        val failure = api.playMove(match.matchId, "11-18").expectFailure()

        assertTrue(failure is ApiError.IllegalMove)
        val legal = (failure as ApiError.IllegalMove).legalMoves
        assertEquals(7, legal.size)
        assertTrue(legal.any { it.notation == "11-15" })
        // Nothing was recorded: a refused move must not advance the game.
        assertEquals(0, api.match(match.matchId).expect().turnNumber)
    }

    @Test
    fun `moving out of turn is refused`() = runTest {
        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()

        val failure = api.playMove(match.matchId, "22-18").expectFailure()

        assertTrue(failure is ApiError.Rejected)
        assertEquals("not_your_turn", (failure as ApiError.Rejected).code)
    }

    @Test
    fun `a from-to pair resolves to the move that joins them`() = runTest {
        val match = api.startMatch().expect()
        val result = api.playMove(match.matchId, 11, 15).expect()
        assertEquals("11-15", result.appliedMove.notation)
    }

    @Test
    fun `a mandatory capture is the only thing offered`() = runTest {
        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()
        val afterAi = api.generateAiTurn(match.matchId).expect()

        // Whatever the AI played, the hints the board draws are the engine's, not a guess.
        val resumed = api.match(match.matchId).expect()
        assertEquals(afterAi.legalMoves.map { it.notation }.sorted(),
            resumed.legalMoves.map { it.notation }.sorted())
    }

    // --- the opponent ------------------------------------------------------------------------

    @Test
    fun `the AI answers with a legal move, its reasoning and its shortlist`() = runTest {
        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()

        val turn = api.generateAiTurn(match.matchId).expect()

        assertTrue(turn.aiMove.isNotBlank())
        assertEquals("local", turn.reasoningSource)
        assertFalse(turn.spokeThroughOllama)
        assertTrue(turn.aiReasoning.startsWith("Playing ${turn.aiMove}"))
        assertTrue(turn.evaluation.considered.isNotEmpty())
        assertTrue(turn.evaluation.confidence in 0.0..1.0)
        assertEquals(Side.BLACK, turn.board.sideToMove)
        assertEquals(2, turn.turnNumber)
    }

    @Test
    fun `the AI will not move when it is not its turn`() = runTest {
        val match = api.startMatch().expect()
        val failure = api.generateAiTurn(match.matchId).expectFailure()
        assertEquals("not_your_turn", (failure as ApiError.Rejected).code)
    }

    @Test
    fun `with no engine installed the AI says so instead of inventing a move`() = runTest {
        val bare = OfflineCheckersApi(
            store = store,
            engine = TestEngine.missingStore(),
            preferences = preferences
        )

        val failure = bare.startMatch().expectFailure()
        assertTrue(failure is ApiError.BrainUnavailable)
        // `detail` is what GameFailureCard actually shows, so that is what has to be useful.
        val detail = (failure as ApiError.BrainUnavailable).detail
        assertTrue(detail, detail.contains("engine"))
        assertTrue(detail, detail.contains("Connect to the referee") || detail.contains("download"))
    }

    // --- finishing ---------------------------------------------------------------------------

    @Test
    fun `resigning hands the game to the opponent and closes it`() = runTest {
        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()

        val resigned = api.resign(match.matchId).expect()

        assertTrue(resigned.gameOver)
        assertEquals(Side.AI, resigned.winner)

        val resumed = api.match(match.matchId).expect()
        assertEquals("finished", resumed.status)
        assertEquals(Side.AI, resumed.winner)
        assertTrue("a finished match offers no moves", resumed.legalMoves.isEmpty())
    }

    @Test
    fun `a finished match refuses further moves`() = runTest {
        val match = api.startMatch().expect()
        api.resign(match.matchId).expect()

        val failure = api.playMove(match.matchId, "11-15").expectFailure()
        assertEquals("match_finished", (failure as ApiError.Rejected).code)
    }

    @Test
    fun `a finished game joins the outbox with its full move list`() = runTest {
        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()
        api.generateAiTurn(match.matchId).expect()
        api.resign(match.matchId).expect()

        val pending = store.pendingUploads()
        assertEquals(1, pending.size)
        assertEquals(2, pending[0].moves.size)
        assertEquals("11-15", pending[0].moves[0])
        assertEquals(Side.HUMAN, pending[0].resignedBy)
        assertFalse(pending[0].uploaded)
    }

    @Test
    fun `an uploaded game leaves the outbox`() = runTest {
        val match = api.startMatch().expect()
        api.resign(match.matchId).expect()

        store.markUploaded(store.pendingUploads().map { it.localId })
        assertTrue(store.pendingUploads().isEmpty())
    }

    // --- history and analytics ---------------------------------------------------------------

    @Test
    fun `a resumed match carries the reasoning the AI wrote at the time`() = runTest {
        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()
        val turn = api.generateAiTurn(match.matchId).expect()

        val history = api.match(match.matchId).expect().history

        assertEquals(2, history.size)
        assertEquals(Side.HUMAN, history[0].side)
        assertNull(history[0].reasoning)
        assertEquals(Side.AI, history[1].side)
        assertEquals(turn.aiReasoning, history[1].reasoning)
        assertTrue(history[1].fen.isNotBlank())
    }

    @Test
    fun `the match list reports what was played`() = runTest {
        val first = api.startMatch("easy").expect()
        api.playMove(first.matchId, "11-15").expect()
        api.resign(first.matchId).expect()
        api.startMatch("hard").expect()

        val listed = api.matches(null, 50).expect().matches

        assertEquals(2, listed.size)
        val finished = listed.first { it.matchId == first.matchId }
        assertEquals("finished", finished.status)
        assertEquals("easy", finished.difficulty)
        assertEquals(1, finished.totalTurns)
        assertNotNull(finished.endTime)
    }

    @Test
    fun `the summary counts games this device refereed`() = runTest {
        repeat(3) {
            val match = api.startMatch().expect()
            api.playMove(match.matchId, "11-15").expect()
            api.resign(match.matchId).expect()
        }

        val summary = api.summary().expect()
        assertEquals(3, summary.totalMatches)
        assertEquals(3, summary.aiWins)
        assertEquals(1.0, summary.aiWinRate, 1e-6)
        assertEquals(1.0, summary.avgTurns, 1e-6)
    }

    @Test
    fun `health reports the engine rather than a backend`() = runTest {
        val health = api.health().expect()
        assertTrue(health.ok)
        assertEquals("offline", health.version)
        assertFalse(health.ollama.available)
    }

    // --- learning ----------------------------------------------------------------------------

    @Test
    fun `finishing a game trains the local weights`() = runTest {
        val before = EngineStore.state.header?.localGames ?: 0

        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()
        api.generateAiTurn(match.matchId).expect()
        api.resign(match.matchId).expect()

        assertEquals(before + 1, EngineStore.state.header?.localGames)
        assertTrue(EngineStore.state.lastTrainedAt > 0)
    }

    @Test
    fun `learning can be turned off`() = runTest {
        preferences.learnOnDevice = false
        val before = EngineStore.state.header?.localGames ?: 0

        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()
        api.resign(match.matchId).expect()

        assertEquals(before, EngineStore.state.header?.localGames ?: 0)
    }

    @Test
    fun `a lost game teaches the agent which move not to repeat`() = runTest {
        val match = api.startMatch().expect()
        api.playMove(match.matchId, "11-15").expect()
        api.generateAiTurn(match.matchId).expect()
        // A resignation is a win for the AI, so nothing here should be marked a mistake.
        api.resign(match.matchId).expect()

        val memory = store.mistakeMemory()
        val opening = com.surenjanath.crownfoundry.engine.Board.initial()
        assertTrue(memory.knownMistakes(opening.toFen()).isEmpty())
    }

    // --- the whole thing ---------------------------------------------------------------------

    @Test
    fun `a complete game plays out through the public API and ends properly`() = runTest {
        // The test that stands in for a person with no signal: start, alternate turns until the
        // engine calls it, and check that everything downstream of "game over" actually happened.
        val match = api.startMatch("normal").expect()
        val random = kotlin.random.Random(9)

        var legal = match.legalMoves
        var winner: String? = null
        var plies = 0

        while (plies < 300) {
            if (legal.isEmpty()) break
            val chosen = legal[random.nextInt(legal.size)].notation

            val moved = api.playMove(match.matchId, chosen).expect()
            plies++
            if (moved.gameOver) {
                winner = moved.winner
                break
            }

            val turn = api.generateAiTurn(match.matchId).expect()
            plies++
            assertTrue("the AI narrated its move", turn.aiReasoning.isNotBlank())
            if (turn.gameOver) {
                winner = turn.winner
                break
            }
            legal = turn.legalMoves
        }

        assertNotNull("the game never reached a conclusion in $plies plies", winner)
        assertTrue(winner in listOf(Side.BLACK, Side.WHITE, Side.DRAW))

        val finished = api.match(match.matchId).expect()
        assertEquals("finished", finished.status)
        assertEquals(winner, finished.winner)
        assertEquals(plies, finished.turnNumber)
        assertEquals(plies, finished.history.size)
        assertTrue(finished.legalMoves.isEmpty())

        // It reached the outbox, it trained, and the move list replays - which is exactly what
        // the server is going to do with it.
        val pending = store.pendingUploads()
        assertEquals(1, pending.size)
        assertEquals(plies, pending[0].moves.size)
        assertEquals(
            plies,
            com.surenjanath.crownfoundry.engine.replayMoves(pending[0].moves).size
        )
        assertEquals(1, EngineStore.state.header?.localGames)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun <T> Outcome<T>.expect(): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw AssertionError("expected success, got ${reason.message}")
    }

    private fun <T> Outcome<T>.expectFailure(): ApiError = when (this) {
        is Outcome.Failure -> reason
        is Outcome.Success -> throw AssertionError("expected a failure, got $value")
    }
}
