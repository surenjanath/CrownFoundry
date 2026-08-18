package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Two people, one phone.
 *
 * The interesting assertions are the ones about what pass-and-play *is not*: it is not an engine
 * game, so nothing it produces may reach the trainer, the outbox, the opponent model or the win
 * rate. A game between two humans that quietly teaches the policy to imitate whoever borrowed the
 * phone is the failure this file exists to prevent.
 */
class PassAndPlayTest {

    private lateinit var directory: File
    private lateinit var store: LocalMatchStore
    private lateinit var api: OfflineCheckersApi
    private lateinit var preferences: EnginePreferences

    @Before
    fun setUp() {
        directory = File.createTempFile("crownfoundry", "pass").let {
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

    // --- the referee ------------------------------------------------------------------------

    @Test
    fun `both sides may move, in turn`() = runTest {
        val match = api.startPassAndPlay(null).expect()

        val black = api.playMove(match.matchId, "11-15").expect()
        assertEquals(Side.WHITE, black.board.sideToMove)

        // The move that would be refused in an engine game: White, played by hand.
        val white = api.playMove(match.matchId, "23-19").expect()
        assertEquals(Side.BLACK, white.board.sideToMove)
        assertEquals(2, white.turnNumber)
    }

    @Test
    fun `the rules are still the only authority`() = runTest {
        val match = api.startPassAndPlay(null).expect()

        // 23-19 is White's move and it is Black to play, so it is illegal - not "not your turn".
        val failure = api.playMove(match.matchId, "23-19").expectFailure()

        assertTrue(failure is ApiError.IllegalMove)
        assertEquals(7, (failure as ApiError.IllegalMove).legalMoves.size)
    }

    @Test
    fun `there is no opponent to ask for a move`() = runTest {
        val match = api.startPassAndPlay(null).expect()
        api.playMove(match.matchId, "11-15").expect()

        val failure = api.generateAiTurn(match.matchId).expectFailure()

        assertTrue(failure is ApiError.Rejected)
        assertEquals("no_opponent", (failure as ApiError.Rejected).code)
    }

    @Test
    fun `resigning hands the game to the other chair`() = runTest {
        val match = api.startPassAndPlay(null).expect()
        api.playMove(match.matchId, "11-15").expect()

        // White is to move, so White is the one giving up.
        val resigned = api.resign(match.matchId).expect()

        assertTrue(resigned.gameOver)
        assertEquals(Side.HUMAN, resigned.winner)
        assertEquals(Side.AI, store.find(match.matchId)?.resignedBy)
    }

    @Test
    fun `captures are recorded against the colour that made them`() = runTest {
        val match = api.startPassAndPlay(null).expect()
        api.playMove(match.matchId, "11-15").expect()
        api.playMove(match.matchId, "23-19").expect()

        val stored = store.find(match.matchId)!!
        assertEquals(listOf("11-15", "23-19"), stored.moves)
        assertTrue(stored.isPassAndPlay)
    }

    // --- what it must never contaminate --------------------------------------------------------

    @Test
    fun `a pass-and-play game is never offered to the server`() = runTest {
        val engineGame = api.startMatch().expect()
        api.playMove(engineGame.matchId, "11-15").expect()
        api.resign(engineGame.matchId).expect()

        val handGame = api.startPassAndPlay(null).expect()
        api.playMove(handGame.matchId, "11-15").expect()
        api.resign(handGame.matchId).expect()

        val pending = store.pendingUploads().map { it.matchId }

        assertTrue(engineGame.matchId in pending)
        assertFalse(handGame.matchId in pending)
    }

    @Test
    fun `a pass-and-play game does not become the opponent model`() = runTest {
        val match = api.startPassAndPlay(null).expect()
        api.playMove(match.matchId, "11-15").expect()
        api.resign(match.matchId).expect()

        // Only games against the engine describe the person the engine is playing.
        assertEquals(0, store.opponentProfile().totalGames)
    }

    @Test
    fun `a pass-and-play game is not counted in the engine's record`() = runTest {
        val match = api.startPassAndPlay(null).expect()
        api.playMove(match.matchId, "11-15").expect()
        api.resign(match.matchId).expect()

        assertEquals(0, api.summary().expect().totalMatches)
        assertTrue(api.performance().expect().winRateSeries.isEmpty())
    }

    @Test
    fun `a pass-and-play game teaches the policy nothing`() = runTest {
        preferences.learnOnDevice = true
        val before = EngineStore.state.header?.gamesTrained ?: 0

        val match = api.startPassAndPlay(null).expect()
        api.playMove(match.matchId, "11-15").expect()
        api.resign(match.matchId).expect()

        assertEquals(before, EngineStore.state.header?.gamesTrained ?: 0)
    }

    @Test
    fun `it does not need an engine at all`() = runTest {
        val bare = OfflineCheckersApi(
            store = store,
            engine = TestEngine.missingStore(),
            preferences = preferences
        )

        // startMatch would refuse here; a game between two people has nothing to refuse over.
        assertTrue(bare.startMatch() is Outcome.Failure)

        val match = bare.startPassAndPlay(null).expect()
        assertTrue(bare.playMove(match.matchId, "11-15") is Outcome.Success)
    }

    // --- the wrapper the screens use -----------------------------------------------------------

    @Test
    fun `the pass-and-play api starts pass-and-play matches and nothing else changes`() = runTest {
        val wrapper = PassAndPlayApi(api)

        val match = wrapper.startMatch("hard", "player-1", null).expect()

        assertTrue(store.find(match.matchId)!!.isPassAndPlay)
        // Delegation, not reimplementation: reading the match back goes to the same referee.
        assertEquals(match.matchId, wrapper.match(match.matchId).expect().matchId)
    }

    private fun <T> Outcome<T>.expect(): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw AssertionError("expected success, got $reason")
    }

    private fun <T> Outcome<T>.expectFailure(): ApiError = when (this) {
        is Outcome.Success -> throw AssertionError("expected failure, got $value")
        is Outcome.Failure -> reason
    }
}
