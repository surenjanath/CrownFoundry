package com.surenjanath.crownfoundry.api

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertTrue

/**
 * A stand-in referee. Every test in this module talks to [MockEngine] - nothing here opens a socket.
 */
abstract class MockBackendTest {

    val requests: MutableList<HttpRequestData> = Collections.synchronizedList(mutableListOf())

    val lastRequest: HttpRequestData get() = requests.last()

    fun backend(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) {
        CrownFoundryClient.installEngine(
            MockEngine { request ->
                requests += request
                handler(request)
            }
        )
    }

    /** The common case: one canned body for whatever is asked. */
    fun serving(body: String, status: HttpStatusCode = HttpStatusCode.OK) = backend {
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @After
    fun resetClient() {
        CrownFoundryClient.installEngine(null)
        CrownFoundryClient.baseUrl = DEFAULT_BASE_URL
        requests.clear()
    }
}

val HttpRequestData.bodyText: String get() = (body as? TextContent)?.text.orEmpty()

val HttpRequestData.bodyJson: JsonObject
    get() = Json.parseToJsonElement(bodyText).jsonObject

val HttpRequestData.path: String get() = url.encodedPath

fun HttpRequestData.query(name: String): String? = url.parameters[name]

inline fun <reified T : ApiError> Outcome<*>.failedWith(): T {
    assertTrue("expected a failure, got $this", this is Outcome.Failure)
    val reason = (this as Outcome.Failure).reason
    assertTrue(
        "expected ${T::class.simpleName}, got ${reason::class.simpleName}: ${reason.message}",
        reason is T
    )
    return reason as T
}

fun <T> Outcome<T>.succeeded(): T {
    assertTrue("expected a success, got ${(this as? Outcome.Failure)?.reason?.message}", isSuccess)
    return (this as Outcome.Success).value
}
