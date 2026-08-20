package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.Outcome
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A build published without a referee.
 *
 * `crownfoundry.backendUrl=none` ships the bundled engine as the whole product. The rule that
 * makes that work is not "try the server and cope when it fails" - it is *never try*. A build with
 * no address would otherwise take its own placeholder literally and reach for `http://none`, which
 * a release build refuses as cleartext; that refusal is not a connectivity error, so the offline
 * fallback would not even recognise it, and a perfectly playable app would show a failure on every
 * screen.
 */
class OfflineOnlyBuildTest {

    private lateinit var directory: File
    private lateinit var preferences: EnginePreferences
    private lateinit var local: OfflineCheckersApi
    private lateinit var remote: CountingRemote

    @Before
    fun setUp() {
        directory = File.createTempFile("crownfoundry", "nobackend").let {
            it.delete(); it.mkdirs(); it
        }
        preferences = EnginePreferences(FakeSharedPreferences())
        remote = CountingRemote()
        local = OfflineCheckersApi(
            store = LocalMatchStore(File(directory, "matches.json")),
            engine = TestEngine.readyStore(directory),
            preferences = preferences,
            searchDepth = 2,
            nodeBudget = 300
        )
    }

    private fun hybrid(backendAvailable: Boolean) = HybridCheckersApi(
        remote = remote,
        local = local,
        preferences = preferences,
        backendAvailable = { backendAvailable }
    )

    @Test
    fun `with no backend a match starts without touching the network`() = runBlocking {
        val api = hybrid(backendAvailable = false)

        val match = api.startMatch("adaptive", playerId = null, rules = null)

        assertNotNull(match.valueOrNull)
        assertEquals("the network must not be reached at all", 0, remote.calls)
    }

    @Test
    fun `with no backend every read is answered locally`() = runBlocking {
        val api = hybrid(backendAvailable = false)

        api.health()
        api.matches(null, 10)
        api.summary()
        api.performance()

        assertEquals(0, remote.calls)
    }

    @Test
    fun `with no backend moves and ai turns stay local`() = runBlocking {
        val api = hybrid(backendAvailable = false)
        val id = api.startMatch("adaptive", null, null).valueOrNull!!.matchId

        assertNotNull(api.playMove(id, "11-15").valueOrNull)
        assertNotNull(api.generateAiTurn(id).valueOrNull)
        assertEquals(0, remote.calls)
    }

    @Test
    fun `with no backend the app reports itself as offline`() = runBlocking {
        val api = hybrid(backendAvailable = false)
        api.health()
        assertTrue(api.isOffline)
    }

    @Test
    fun `naming a backend later starts using it`() = runBlocking {
        // The flag is read per call, so typing an address into Settings takes effect immediately.
        var available = false
        val api = HybridCheckersApi(
            remote = remote, local = local, preferences = preferences,
            backendAvailable = { available }
        )

        api.health()
        assertEquals(0, remote.calls)

        available = true
        api.health()
        assertEquals(1, remote.calls)
    }

    @Test
    fun `with a backend configured the remote is tried first`() = runBlocking {
        // This fake always refuses, so the call still ends up served locally - what is being
        // asserted is that the referee was asked at all, which is the difference from the cases
        // above.
        val api = hybrid(backendAvailable = true)
        api.health()
        assertEquals(1, remote.calls)
    }
}

/** A remote that records whether it was reached at all, and always fails if it was. */
private class CountingRemote : CheckersApi {
    var calls = 0
        private set

    private fun <T> reached(): Outcome<T> {
        calls++
        return Outcome.Failure(ApiError.Unreachable("http://none"))
    }

    override suspend fun health() = reached<com.surenjanath.crownfoundry.api.HealthDto>()
    override suspend fun startMatch(difficulty: String, playerId: String?, rules: MatchRulesDto?) =
        reached<com.surenjanath.crownfoundry.api.MatchDto>()
    override suspend fun match(matchId: String) = reached<com.surenjanath.crownfoundry.api.MatchDto>()
    override suspend fun matches(playerId: String?, limit: Int) =
        reached<com.surenjanath.crownfoundry.api.MatchListDto>()
    override suspend fun playMove(matchId: String, move: String) =
        reached<com.surenjanath.crownfoundry.api.MoveResultDto>()
    override suspend fun playMove(matchId: String, from: Int, to: Int) =
        reached<com.surenjanath.crownfoundry.api.MoveResultDto>()
    override suspend fun generateAiTurn(matchId: String) =
        reached<com.surenjanath.crownfoundry.api.AiTurnDto>()
    override suspend fun resign(matchId: String) =
        reached<com.surenjanath.crownfoundry.api.ResignDto>()
    override suspend fun performance() =
        reached<com.surenjanath.crownfoundry.api.PerformanceDto>()
    override suspend fun summary() =
        reached<com.surenjanath.crownfoundry.api.AnalyticsSummaryDto>()
}
