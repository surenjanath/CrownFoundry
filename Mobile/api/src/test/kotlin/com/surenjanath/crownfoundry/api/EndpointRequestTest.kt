package com.surenjanath.crownfoundry.api

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Method, path (Django will not rescue a POST to a slash-less route), body and query, per endpoint.
 */
class EndpointRequestTest : MockBackendTest() {

    @Test
    fun `health is a GET on a trailing slash`() = runTest {
        serving(Fixtures.HEALTH)

        CrownFoundryClient.health().succeeded()

        assertEquals(HttpMethod.Get, lastRequest.method)
        assertEquals("/api/health/", lastRequest.path)
        assertEquals("10.0.2.2", lastRequest.url.host)
        assertEquals(8000, lastRequest.url.port)
    }

    @Test
    fun `start match posts difficulty and player id`() = runTest {
        serving(Fixtures.MATCH_START)

        CrownFoundryClient.startMatch("hard", "player-7").succeeded()

        assertEquals(HttpMethod.Post, lastRequest.method)
        assertEquals("/api/match/start/", lastRequest.path)
        assertEquals("hard", lastRequest.bodyJson["difficulty"]?.jsonPrimitive?.content)
        assertEquals("player-7", lastRequest.bodyJson["player_id"]?.jsonPrimitive?.content)
        assertEquals("application/json", lastRequest.body.contentType?.withoutParameters().toString())
    }

    @Test
    fun `start match omits a null player id`() = runTest {
        serving(Fixtures.MATCH_START)

        CrownFoundryClient.startMatch().succeeded()

        assertEquals("adaptive", lastRequest.bodyJson["difficulty"]?.jsonPrimitive?.content)
        assertNull(lastRequest.bodyJson["player_id"])
    }

    @Test
    fun `match detail addresses the match by id`() = runTest {
        serving(Fixtures.MATCH_DETAIL)

        CrownFoundryClient.match("abc-123").succeeded()

        assertEquals(HttpMethod.Get, lastRequest.method)
        assertEquals("/api/match/abc-123/", lastRequest.path)
    }

    @Test
    fun `matches carries player id and limit`() = runTest {
        serving(Fixtures.MATCH_LIST)

        CrownFoundryClient.matches("player-7", limit = 25).succeeded()

        assertEquals(HttpMethod.Get, lastRequest.method)
        assertEquals("/api/matches/", lastRequest.path)
        assertEquals("player-7", lastRequest.query("player_id"))
        assertEquals("25", lastRequest.query("limit"))
    }

    @Test
    fun `matches defaults to a limit of fifty and no player filter`() = runTest {
        serving(Fixtures.MATCH_LIST)

        CrownFoundryClient.matches().succeeded()

        assertNull(lastRequest.query("player_id"))
        assertEquals("50", lastRequest.query("limit"))
    }

    @Test
    fun `notation move posts player_move`() = runTest {
        serving(Fixtures.MOVE_RESULT)

        CrownFoundryClient.playMove("m-1", "11x18x25").succeeded()

        assertEquals(HttpMethod.Post, lastRequest.method)
        assertEquals("/api/match/move/", lastRequest.path)
        assertEquals("m-1", lastRequest.bodyJson["match_id"]?.jsonPrimitive?.content)
        assertEquals("11x18x25", lastRequest.bodyJson["player_move"]?.jsonPrimitive?.content)
    }

    @Test
    fun `square to square move posts from and to`() = runTest {
        serving(Fixtures.MOVE_RESULT)

        CrownFoundryClient.playMove("m-1", from = 11, to = 15).succeeded()

        assertEquals("/api/match/move/", lastRequest.path)
        assertEquals("m-1", lastRequest.bodyJson["match_id"]?.jsonPrimitive?.content)
        assertEquals(11, lastRequest.bodyJson["from"]?.jsonPrimitive?.int)
        assertEquals(15, lastRequest.bodyJson["to"]?.jsonPrimitive?.int)
        assertNull(lastRequest.bodyJson["player_move"])
    }

    @Test
    fun `ai turn posts the match id`() = runTest {
        serving(Fixtures.AI_TURN)

        CrownFoundryClient.generateAiTurn("m-1").succeeded()

        assertEquals(HttpMethod.Post, lastRequest.method)
        assertEquals("/api/ai/generate-turn/", lastRequest.path)
        assertEquals("m-1", lastRequest.bodyJson["match_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `resign posts to the match's resign route`() = runTest {
        serving(Fixtures.RESIGN)

        CrownFoundryClient.resign("m-1").succeeded()

        assertEquals(HttpMethod.Post, lastRequest.method)
        assertEquals("/api/match/m-1/resign/", lastRequest.path)
    }

    @Test
    fun `analytics endpoints`() = runTest {
        serving(Fixtures.PERFORMANCE)
        CrownFoundryClient.performance().succeeded()
        assertEquals(HttpMethod.Get, lastRequest.method)
        assertEquals("/api/analytics/ai-performance/", lastRequest.path)

        serving(Fixtures.SUMMARY)
        CrownFoundryClient.summary().succeeded()
        assertEquals(HttpMethod.Get, lastRequest.method)
        assertEquals("/api/analytics/summary/", lastRequest.path)
    }

    @Test
    fun `every path the app can call ends in a slash`() = runTest {
        serving(Fixtures.HEALTH)
        runCatching { CrownFoundryClient.health() }
        serving(Fixtures.MATCH_START)
        runCatching { CrownFoundryClient.startMatch() }
        serving(Fixtures.MATCH_DETAIL)
        runCatching { CrownFoundryClient.match("m-1") }
        serving(Fixtures.MATCH_LIST)
        runCatching { CrownFoundryClient.matches() }
        serving(Fixtures.MOVE_RESULT)
        runCatching { CrownFoundryClient.playMove("m-1", "11-15") }
        serving(Fixtures.AI_TURN)
        runCatching { CrownFoundryClient.generateAiTurn("m-1") }
        serving(Fixtures.RESIGN)
        runCatching { CrownFoundryClient.resign("m-1") }
        serving(Fixtures.PERFORMANCE)
        runCatching { CrownFoundryClient.performance() }
        serving(Fixtures.SUMMARY)
        runCatching { CrownFoundryClient.summary() }

        assertEquals(9, requests.size)
        requests.forEach { assertEquals("${it.path} must end in /", '/', it.path.last()) }
    }
}
