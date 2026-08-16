package com.surenjanath.crownfoundry.api

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One engine for the process, and a screen that leaves composition cancelling cleanly.
 * These use [runBlocking] rather than `runTest`: the point is real overlap, not virtual time.
 */
class ConcurrencyTest : MockBackendTest() {

    @Test
    fun `a cancelled call throws rather than becoming a failure`(): Unit = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        backend {
            arrived.complete(Unit)
            never.await()
            respond(Fixtures.AI_TURN, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val outcome = AtomicReference<Outcome<AiTurnDto>?>(null)
        val thrown = AtomicReference<Throwable?>(null)

        val job = launch(Dispatchers.Default) {
            try {
                outcome.set(CrownFoundryClient.generateAiTurn("m-1"))
            } catch (failure: Throwable) {
                thrown.set(failure)
                throw failure
            }
        }

        arrived.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull("cancellation must not be swallowed into an Outcome", outcome.get())
        assertTrue(
            "expected a CancellationException, got ${thrown.get()}",
            thrown.get() is CancellationException
        )
        never.complete(Unit)
    }

    @Test
    fun `cancelling one call leaves the client usable`(): Unit = runBlocking {
        val arrived = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        backend { request ->
            if (request.path == "/api/ai/generate-turn/") {
                arrived.complete(Unit)
                never.await()
            }
            respond(Fixtures.HEALTH, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val client = CrownFoundryClient.httpClient
        val job = launch(Dispatchers.Default) { CrownFoundryClient.generateAiTurn("m-1") }
        arrived.await()
        job.cancelAndJoin()

        assertEquals("1.0.0", CrownFoundryClient.health().succeeded().version)
        assertSame(client, CrownFoundryClient.httpClient)
        never.complete(Unit)
    }

    @Test
    fun `concurrent calls share one client and do not cross wires`(): Unit = runBlocking {
        val inFlight = AtomicInteger()
        val allArrived = CompletableDeferred<Unit>()
        val calls = 12

        // Every request parks until all of them are in flight: proof they really do overlap.
        backend { request ->
            if (inFlight.incrementAndGet() == calls) allArrived.complete(Unit)
            allArrived.await()
            val body = when (request.path) {
                "/api/health/" -> Fixtures.HEALTH
                "/api/ai/generate-turn/" -> Fixtures.AI_TURN
                "/api/analytics/summary/" -> Fixtures.SUMMARY
                "/api/matches/" -> Fixtures.MATCH_LIST
                else -> error("unexpected path ${request.path}")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val client = CrownFoundryClient.httpClient
        val results = (0 until calls).map { index ->
            async(Dispatchers.Default) {
                when (index % 4) {
                    0 -> CrownFoundryClient.health().succeeded().version
                    1 -> CrownFoundryClient.generateAiTurn("m-$index").succeeded().aiMove
                    2 -> CrownFoundryClient.summary().succeeded().elo.toString()
                    else -> CrownFoundryClient.matches().succeeded().matches.single().totalTurns.toString()
                }
            }
        }.awaitAll()

        assertEquals(
            List(calls) { listOf("1.0.0", "24-19", "1180", "12")[it % 4] },
            results
        )
        assertEquals(calls, requests.size)
        assertSame("all calls must go through the one client", client, CrownFoundryClient.httpClient)
    }
}
