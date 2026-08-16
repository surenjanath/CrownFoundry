package com.surenjanath.crownfoundry.api

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The AI turn waits on a local LLM; everything else waits on Django. Two budgets, set per request
 * so the long one never leaks onto a move the player is watching.
 */
class TimeoutProfileTest : MockBackendTest() {

    private val HttpRequestData.timeouts
        get() = getCapabilityOrNull(HttpTimeout).also { assertNotNull("no timeout on the request", it) }!!

    @Test
    fun `generate ai turn gets ninety seconds`() = runTest {
        serving(Fixtures.AI_TURN)

        CrownFoundryClient.generateAiTurn("m-1").succeeded()

        assertEquals(90_000L, lastRequest.timeouts.requestTimeoutMillis)
        assertEquals(90_000L, lastRequest.timeouts.socketTimeoutMillis)
        // Reaching the host is still expected to be quick.
        assertEquals(10_000L, lastRequest.timeouts.connectTimeoutMillis)
    }

    @Test
    fun `every other call gets ten seconds`() = runTest {
        serving(Fixtures.HEALTH)
        CrownFoundryClient.health()
        serving(Fixtures.MATCH_START)
        CrownFoundryClient.startMatch()
        serving(Fixtures.MATCH_DETAIL)
        CrownFoundryClient.match("m-1")
        serving(Fixtures.MATCH_LIST)
        CrownFoundryClient.matches()
        serving(Fixtures.MOVE_RESULT)
        CrownFoundryClient.playMove("m-1", "11-15")
        serving(Fixtures.MOVE_RESULT)
        CrownFoundryClient.playMove("m-1", 11, 15)
        serving(Fixtures.RESIGN)
        CrownFoundryClient.resign("m-1")
        serving(Fixtures.PERFORMANCE)
        CrownFoundryClient.performance()
        serving(Fixtures.SUMMARY)
        CrownFoundryClient.summary()

        assertEquals(9, requests.size)
        requests.forEach {
            assertEquals("${it.path} should use the short budget", 10_000L, it.timeouts.requestTimeoutMillis)
        }
    }

    @Test
    fun `the long budget does not leak into the next call`() = runTest {
        serving(Fixtures.AI_TURN)
        CrownFoundryClient.generateAiTurn("m-1").succeeded()
        assertEquals(90_000L, lastRequest.timeouts.requestTimeoutMillis)

        serving(Fixtures.MOVE_RESULT)
        CrownFoundryClient.playMove("m-1", "11-15").succeeded()
        assertEquals(10_000L, lastRequest.timeouts.requestTimeoutMillis)
    }

    @Test
    fun `the budgets are what the constants say`() {
        assertEquals(10, CrownFoundryClient.NORMAL_TIMEOUT_SECONDS)
        assertEquals(90, CrownFoundryClient.AI_TURN_TIMEOUT_SECONDS)
    }
}
