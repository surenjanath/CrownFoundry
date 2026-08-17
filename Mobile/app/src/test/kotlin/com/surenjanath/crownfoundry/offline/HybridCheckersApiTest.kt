package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.MatchListDto
import com.surenjanath.crownfoundry.api.MatchSummaryDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.game.FakeCheckersApi
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The routing rule, which is where offline mode can go quietly wrong.
 *
 * The failure this file exists to prevent is a match being refereed by both sides at once. Every
 * test below is some version of "did the right referee get this call?", because a wrong answer
 * there does not look like a bug - it looks like the board losing a move.
 */
class HybridCheckersApiTest {

    private lateinit var directory: File
    private lateinit var remote: FakeCheckersApi
    private lateinit var preferences: EnginePreferences
    private lateinit var hybrid: HybridCheckersApi
    private lateinit var offline: OfflineCheckersApi

    @Before
    fun setUp() {
        directory = File.createTempFile("crownfoundry", "hybrid").let {
            it.delete(); it.mkdirs(); it
        }
        remote = FakeCheckersApi()
        preferences = EnginePreferences(FakeSharedPreferences())
        offline = OfflineCheckersApi(
            store = LocalMatchStore(File(directory, "matches.json")),
            engine = TestEngine.readyStore(directory),
            preferences = preferences,
            searchDepth = 1,
            nodeBudget = 200
        )
        hybrid = HybridCheckersApi(remote, offline, preferences)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        EngineStore.resetForTest()
    }

    @Test
    fun `a reachable referee gets the match`() = runTest {
        val match = hybrid.startMatch().expect()

        assertEquals(1, remote.startCalls)
        assertFalse(hybrid.isOffline)
        assertFalse(LocalMatchStore.isOffline(match.matchId))
    }

    @Test
    fun `an unreachable referee hands the game to the device`() = runTest {
        remote.startOutcome = Outcome.Failure(ApiError.Unreachable("http://nope:8000"))

        val match = hybrid.startMatch().expect()

        assertTrue(hybrid.isOffline)
        assertTrue(LocalMatchStore.isOffline(match.matchId))
        assertEquals(7, match.legalMoves.size)
        assertTrue(hybrid.lastFallbackReason is ApiError.Unreachable)
    }

    @Test
    fun `a timeout also falls back`() = runTest {
        remote.startOutcome = Outcome.Failure(ApiError.Timeout(10))
        assertTrue(LocalMatchStore.isOffline(hybrid.startMatch().expect().matchId))
    }

    @Test
    fun `a referee that answered and said no is not second-guessed`() = runTest {
        // A real refusal is information. Replacing it with a locally invented game would hide a
        // genuine disagreement between this app and the server.
        remote.startOutcome = Outcome.Failure(
            ApiError.Rejected(400, "invalid_difficulty", "no such difficulty")
        )

        val failure = hybrid.startMatch("nonsense").expectFailure()

        assertEquals("invalid_difficulty", (failure as ApiError.Rejected).code)
        assertFalse(hybrid.isOffline)
    }

    @Test
    fun `with no engine installed there is nothing to fall back to`() = runTest {
        val bare = HybridCheckersApi(
            remote = remote,
            local = OfflineCheckersApi(
                store = LocalMatchStore(File(directory, "bare.json")),
                engine = TestEngine.missingStore(),
                preferences = preferences
            ),
            preferences = preferences
        )
        remote.startOutcome = Outcome.Failure(ApiError.Unreachable("http://nope:8000"))

        // The referee's own error is the more useful one when both are true.
        val failure = bare.startMatch().expectFailure()
        assertTrue(failure is ApiError.Unreachable)
    }

    @Test
    fun `a match belongs to whoever started it`() = runTest {
        remote.startOutcome = Outcome.Failure(ApiError.Unreachable("http://nope:8000"))
        val local = hybrid.startMatch().expect()

        // The referee is back, but this game is not its business.
        remote.startOutcome = Outcome.Success(MatchDto(matchId = "server-1"))
        hybrid.playMove(local.matchId, "11-15").expect()

        assertTrue("the referee was sent an offline match", remote.movesSent.isEmpty())
        assertEquals(1, offline.match(local.matchId).expect().turnNumber)
    }

    @Test
    fun `a server match keeps going to the server`() = runTest {
        hybrid.playMove("2c9b8a1e-0000-0000-0000-000000000000", "11-15")
        assertEquals(listOf("11-15"), remote.movesSent)
        assertFalse(hybrid.isOffline)
    }

    @Test
    fun `always-play-offline routes new matches to the device`() = runTest {
        preferences.preferOffline = true

        val match = hybrid.startMatch().expect()

        assertEquals(0, remote.startCalls)
        assertTrue(LocalMatchStore.isOffline(match.matchId))
        assertTrue(hybrid.isOffline)
    }

    @Test
    fun `always-play-offline is ignored when there is no engine`() = runTest {
        preferences.preferOffline = true
        val bare = HybridCheckersApi(
            remote = remote,
            local = OfflineCheckersApi(
                store = LocalMatchStore(File(directory, "bare2.json")),
                engine = TestEngine.missingStore(),
                preferences = preferences
            ),
            preferences = preferences
        )

        bare.startMatch().expect()
        assertEquals(1, remote.startCalls)
    }

    @Test
    fun `history from both referees appears in one list, newest first`() = runTest {
        preferences.preferOffline = true
        val local = hybrid.startMatch().expect()
        offline.resign(local.matchId).expect()
        preferences.preferOffline = false

        remote.matchesOutcome = Outcome.Success(
            MatchListDto(
                matches = listOf(
                    MatchSummaryDto(matchId = "server-1", startTime = "1999-01-01T00:00:00Z"),
                    MatchSummaryDto(matchId = "server-2", startTime = "2099-01-01T00:00:00Z")
                )
            )
        )

        val listed = hybrid.matches(null, 50).expect().matches

        assertEquals(3, listed.size)
        assertEquals("server-2", listed.first().matchId)
        assertTrue(listed.any { LocalMatchStore.isOffline(it.matchId) })
    }

    @Test
    fun `an unreachable referee still shows the games played here`() = runTest {
        preferences.preferOffline = true
        hybrid.startMatch().expect()
        preferences.preferOffline = false

        remote.matchesOutcome = Outcome.Failure(ApiError.Unreachable("http://nope:8000"))

        val listed = hybrid.matches(null, 50).expect().matches
        assertEquals(1, listed.size)
        assertTrue(hybrid.isOffline)
    }

    @Test
    fun `health falls back so the Play screen can still say something true`() = runTest {
        remote.healthOutcome = Outcome.Failure(ApiError.Unreachable("http://nope:8000"))

        val health = hybrid.health().expect()

        assertEquals("offline", health.version)
        assertTrue(hybrid.isOffline)
    }

    @Test
    fun `resigning an offline match never reaches the referee`() = runTest {
        preferences.preferOffline = true
        val match = hybrid.startMatch().expect()

        val result = hybrid.resign(match.matchId).expect()

        assertEquals(Side.AI, result.winner)
        assertEquals(0, remote.resignCalls)
    }

    private fun <T> Outcome<T>.expect(): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> throw AssertionError("expected success, got ${reason.message}")
    }

    private fun <T> Outcome<T>.expectFailure(): ApiError = when (this) {
        is Outcome.Failure -> reason
        is Outcome.Success -> throw AssertionError("expected a failure, got $value")
    }
}
