package com.surenjanath.crownfoundry.api

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per branch of the map from "what went wrong" to "what the board should say". */
class ErrorMappingTest : MockBackendTest() {

    private fun failing(cause: Throwable) = backend { throw cause }

    @Test
    fun `connection refused is unreachable`() = runTest {
        failing(ConnectException("Connection refused"))

        val error = CrownFoundryClient.health().failedWith<ApiError.Unreachable>()

        assertEquals("http://10.0.2.2:8000/api/health/", error.url)
        assertTrue(error.message.contains("Cannot reach"))
    }

    @Test
    fun `unknown host is unreachable`() = runTest {
        failing(UnknownHostException("nope.local"))
        CrownFoundryClient.summary().failedWith<ApiError.Unreachable>()
    }

    @Test
    fun `no route to host is unreachable`() = runTest {
        failing(NoRouteToHostException("no route"))
        CrownFoundryClient.summary().failedWith<ApiError.Unreachable>()
    }

    @Test
    fun `a wrapped connect exception is still unreachable`() = runTest {
        failing(IllegalStateException("engine blew up", ConnectException("Connection refused")))
        CrownFoundryClient.health().failedWith<ApiError.Unreachable>()
    }

    @Test
    fun `a socket timeout reports the normal budget`() = runTest {
        failing(SocketTimeoutException("timed out"))

        val error = CrownFoundryClient.health().failedWith<ApiError.Timeout>()

        assertEquals(CrownFoundryClient.NORMAL_TIMEOUT_SECONDS, error.seconds)
    }

    @Test
    fun `a timeout on the ai turn reports the long budget`() = runTest {
        failing(SocketTimeoutException("timed out"))

        val error = CrownFoundryClient.generateAiTurn("m-1").failedWith<ApiError.Timeout>()

        assertEquals(CrownFoundryClient.AI_TURN_TIMEOUT_SECONDS, error.seconds)
        assertEquals(90, error.seconds)
    }

    @Test
    fun `an illegal move surfaces the legal ones`() = runTest {
        serving(Fixtures.ILLEGAL_MOVE, HttpStatusCode.BadRequest)

        val error = CrownFoundryClient.playMove("m-1", "11-16")
            .failedWith<ApiError.IllegalMove>()

        assertEquals(2, error.legalMoves.size)
        assertEquals("11x18", error.legalMoves[0].notation)
        assertEquals(listOf(15), error.legalMoves[0].captures)
        assertEquals("12x19", error.legalMoves[1].notation)
        assertEquals(19, error.legalMoves[1].to)
    }

    @Test
    fun `another 4xx is a rejection carrying the code and detail`() = runTest {
        serving(
            """{"ok": false, "error": "match_finished", "detail": "This match is over."}""",
            HttpStatusCode.BadRequest
        )

        val error = CrownFoundryClient.playMove("m-1", "11-15").failedWith<ApiError.Rejected>()

        assertEquals(400, error.status)
        assertEquals("match_finished", error.code)
        assertEquals("This match is over.", error.detail)
        assertEquals("This match is over.", error.message)
        assertTrue(error.legalMoves.isEmpty())
    }

    @Test
    fun `an ambiguous jump is a rejection`() = runTest {
        serving(
            """{"ok": false, "error": "ambiguous", "ambiguous": true,
                "detail": "More than one jump joins 11 and 25."}""",
            HttpStatusCode.BadRequest
        )

        val error = CrownFoundryClient.playMove("m-1", 11, 25).failedWith<ApiError.Rejected>()

        assertEquals("ambiguous", error.code)
        assertEquals(400, error.status)
    }

    @Test
    fun `a 404 is a rejection`() = runTest {
        serving("""{"ok": false, "error": "not_found", "detail": "No such match."}""",
            HttpStatusCode.NotFound)

        val error = CrownFoundryClient.match("nope").failedWith<ApiError.Rejected>()

        assertEquals(404, error.status)
        assertEquals("not_found", error.code)
    }

    @Test
    fun `503 means the brain is down whatever the body says`() = runTest {
        serving("""{"ok": false, "error": "service_unavailable", "detail": "Ollama is restarting."}""",
            HttpStatusCode.ServiceUnavailable)

        val error = CrownFoundryClient.generateAiTurn("m-1")
            .failedWith<ApiError.BrainUnavailable>()

        assertEquals("Ollama is restarting.", error.detail)
    }

    @Test
    fun `brain_unavailable is the brain being down`() = runTest {
        serving("""{"ok": false, "error": "brain_unavailable", "detail": "No active policy."}""",
            HttpStatusCode.BadRequest)

        val error = CrownFoundryClient.generateAiTurn("m-1")
            .failedWith<ApiError.BrainUnavailable>()

        assertEquals("No active policy.", error.detail)
    }

    @Test
    fun `ai_unavailable is the brain being down`() = runTest {
        serving("""{"ok": false, "error": "ai_unavailable", "detail": "Worker offline."}""",
            HttpStatusCode.InternalServerError)

        val error = CrownFoundryClient.generateAiTurn("m-1")
            .failedWith<ApiError.BrainUnavailable>()

        assertEquals("Worker offline.", error.detail)
    }

    @Test
    fun `a 500 is a rejection with what detail survived`() = runTest {
        serving("""{"ok": false, "error": "server_error", "detail": "IntegrityError"}""",
            HttpStatusCode.InternalServerError)

        val error = CrownFoundryClient.performance().failedWith<ApiError.Rejected>()

        assertEquals(500, error.status)
        assertEquals("server_error", error.code)
        assertEquals("IntegrityError", error.detail)
    }

    @Test
    fun `a django html 500 still yields a rejection`() = runTest {
        backend {
            respond(
                "<html><body><h1>Server Error (500)</h1></body></html>",
                HttpStatusCode.InternalServerError,
                headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        val error = CrownFoundryClient.summary().failedWith<ApiError.Rejected>()

        assertEquals(500, error.status)
        assertEquals("http_500", error.code)
        assertTrue(error.detail.contains("Server Error (500)"))
    }

    @Test
    fun `a body of the wrong shape is malformed`() = runTest {
        serving("""{"ok": true, "matches": "not-a-list"}""")

        val error = CrownFoundryClient.matches().failedWith<ApiError.Malformed>()

        assertTrue(error.detail.isNotEmpty())
        assertEquals("The referee's answer made no sense", error.message)
    }

    @Test
    fun `an unparseable body is malformed`() = runTest {
        backend {
            respond(
                "<!doctype html><title>Login</title>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html")
            )
        }

        CrownFoundryClient.health().failedWith<ApiError.Malformed>()
    }

    @Test
    fun `a json array where an object belongs is malformed`() = runTest {
        serving("[1, 2, 3]")
        CrownFoundryClient.summary().failedWith<ApiError.Malformed>()
    }

    @Test
    fun `ok false under a 200 is a failure, not a success`() = runTest {
        serving("""{"ok": false, "error": "match_finished", "detail": "Already resigned."}""")

        val error = CrownFoundryClient.resign("m-1").failedWith<ApiError.Rejected>()

        assertEquals(200, error.status)
        assertEquals("match_finished", error.code)
        assertEquals("Already resigned.", error.detail)
    }

    @Test
    fun `ok false under a 200 still carries an illegal move's hints`() = runTest {
        serving(Fixtures.ILLEGAL_MOVE)

        val error = CrownFoundryClient.playMove("m-1", "11-16")
            .failedWith<ApiError.IllegalMove>()

        assertEquals(listOf("11x18", "12x19"), error.legalMoves.map { it.notation })
    }

    @Test
    fun `ok false under a 200 can also mean the brain is down`() = runTest {
        serving("""{"ok": false, "error": "ollama_unavailable", "detail": "Model not pulled."}""")

        val error = CrownFoundryClient.generateAiTurn("m-1")
            .failedWith<ApiError.BrainUnavailable>()

        assertEquals("Model not pulled.", error.detail)
    }

    @Test
    fun `a 400 with no parseable body is still a rejection`() = runTest {
        backend {
            respond("bad request", HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "text/plain"))
        }

        val error = CrownFoundryClient.startMatch().failedWith<ApiError.Rejected>()

        assertEquals(400, error.status)
        assertEquals("http_400", error.code)
        assertEquals("bad request", error.detail)
    }
}
