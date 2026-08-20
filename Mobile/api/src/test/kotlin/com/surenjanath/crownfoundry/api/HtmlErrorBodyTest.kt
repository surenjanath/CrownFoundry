package com.surenjanath.crownfoundry.api

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the player is shown when the thing that answered was not the referee.
 *
 * Django's error pages, a reverse proxy, a captive portal on hotel wifi - all of them answer in
 * HTML. The failure path used to put the first 200 characters of that body straight into the
 * message, so the Insights and Matches screens displayed `<!DOCTYPE html><html lang="en"><head>…`.
 * That reads as a broken app rather than an unreachable server, and it hides the one word on the
 * page that says what actually went wrong.
 */
class HtmlErrorBodyTest : MockBackendTest() {

    /** Django's real 400, which is what a hostname missing from ALLOWED_HOSTS produces. */
    private val djangoBadRequest = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta http-equiv="content-type" content="text/html; charset=utf-8">
          <title>Bad Request (400)</title>
          <style type="text/css">html * { padding:0; margin:0; }</style>
        </head>
        <body>
          <h1>Bad Request (400)</h1><p></p>
        </body>
        </html>
    """.trimIndent()

    private fun detailFor(status: HttpStatusCode, body: String, contentType: String): String =
        (httpFailure(status.value, body) as ApiError.Rejected).detail

    @Test
    fun `a django error page never reaches the player as markup`() {
        val detail = detailFor(HttpStatusCode.BadRequest, djangoBadRequest, "text/html")
        assertFalse("markup leaked into the message: $detail", detail.contains("<"))
        assertFalse(detail.contains("DOCTYPE"))
    }

    @Test
    fun `the title of a django error page is what gets shown`() {
        val detail = detailFor(HttpStatusCode.BadRequest, djangoBadRequest, "text/html")
        assertTrue("expected the page title, got: $detail", detail.contains("Bad Request (400)"))
    }

    @Test
    fun `a forbidden page says forbidden`() {
        val body = "<html><head><title>Forbidden (403)</title></head><body><h1>Forbidden</h1></body></html>"
        val detail = detailFor(HttpStatusCode.Forbidden, body, "text/html")
        assertTrue(detail.contains("Forbidden (403)"))
        assertFalse(detail.contains("<"))
    }

    @Test
    fun `a page with no title falls back to its text`() {
        val body = "<html><body><h1>Service temporarily unavailable</h1></body></html>"
        val detail = detailFor(HttpStatusCode.BadGateway, body, "text/html")
        assertTrue(detail.contains("Service temporarily unavailable"))
        assertFalse(detail.contains("<"))
    }

    @Test
    fun `a page with neither title nor text still says something honest`() {
        val body = "<html><head><style>body{color:red}</style></head><body></body></html>"
        val detail = detailFor(HttpStatusCode.BadGateway, body, "text/html")
        assertFalse(detail.contains("<"))
        assertTrue("expected a plain statement, got: $detail", detail.contains("502"))
    }

    @Test
    fun `an empty body says so rather than showing nothing`() {
        val detail = detailFor(HttpStatusCode.InternalServerError, "", "text/html")
        assertTrue(detail.contains("500"))
    }

    /**
     * The real cause of the markup on Insights and Matches: the app was pointed at a directory
     * server (`python -m http.server`) rather than the referee, and every API path returned this.
     */
    @Test
    fun `a python http server error page reads as a specific failure`() {
        val body = """
            <!DOCTYPE HTML>
            <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <title>Error response</title>
                </head>
                <body>
                    <h1>Error response</h1>
                    <p>Error code: 404</p>
                </body>
            </html>
        """.trimIndent()

        val detail = detailFor(HttpStatusCode.NotFound, body, "text/html")
        assertFalse("markup leaked: $detail", detail.contains("<"))
        assertTrue("expected the status, got: $detail", detail.contains("404"))
        assertTrue(detail.contains("Error response"))
    }

    @Test
    fun `a title that already names the status does not repeat it`() {
        val detail = detailFor(HttpStatusCode.BadRequest, djangoBadRequest, "text/html")
        assertEquals("The server answered: Bad Request (400)", detail)
    }

    @Test
    fun `a plain text body is still passed through unchanged`() {
        val detail = detailFor(HttpStatusCode.Forbidden, "Dashboard token required.", "text/plain")
        assertTrue(detail.contains("Dashboard token required."))
    }

    @Test
    fun `the house json error contract still wins over any of this`() {
        val body = """{"ok": false, "error": "match_not_found", "detail": "No match with id 7."}"""
        val error = httpFailure(404, body) as ApiError.Rejected
        assertTrue(error.detail.contains("No match with id 7."))
    }

    @Test
    fun `a very long html page is truncated`() {
        val body = "<html><body>" + "word ".repeat(500) + "</body></html>"
        val detail = detailFor(HttpStatusCode.BadGateway, body, "text/html")
        assertTrue(detail.length <= 210)
    }

    @Test
    fun `analytics reached through the real client surfaces a clean message`() = runTest {
        // The end the player actually sees: an Insights fetch against a server answering in HTML.
        backend {
            respond(
                content = djangoBadRequest,
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8")
            )
        }
        val outcome = CrownFoundryClient.summary()

        val reason = (outcome as Outcome.Failure).reason
        val detail = (reason as ApiError.Rejected).detail
        assertFalse("markup reached the Insights screen: $detail", detail.contains("<"))
        assertTrue(detail.contains("Bad Request (400)"))
    }
}
