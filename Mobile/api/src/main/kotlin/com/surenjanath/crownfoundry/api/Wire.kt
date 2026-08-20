package com.surenjanath.crownfoundry.api

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.serialization.JsonConvertException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.ktor.client.network.sockets.ConnectTimeoutException as KtorConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException as KtorSocketTimeoutException
import java.net.SocketTimeoutException as JavaSocketTimeoutException

/**
 * Everything between the socket and the DTOs: how a URL is spelled, and how a failure is named.
 * Kept apart from [CrownFoundryClient] so both halves can be exercised without an engine.
 */

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal val apiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

internal object ApiCodes {
    const val ILLEGAL_MOVE = "illegal_move"

    /** Every code the referee has used to say "the opponent isn't thinking right now". */
    val BRAIN_DOWN = setOf("brain_unavailable", "ai_unavailable", "ollama_unavailable")
}

const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"

/**
 * Settings hands us whatever the user typed. Accept `host:port`, tolerate trailing slashes and
 * whitespace, and fall back to the emulator loopback rather than ever holding an unusable URL.
 */
fun normaliseBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return DEFAULT_BASE_URL

    val separator = trimmed.indexOf("://")
    val scheme = if (separator > 0) trimmed.substring(0, separator).trim().lowercase() else "http"
    val rest = if (separator > 0) trimmed.substring(separator + 3) else trimmed
    val authority = rest.trim().trim('/')

    if (authority.isEmpty() || scheme.isEmpty()) return DEFAULT_BASE_URL
    return "$scheme://$authority"
}

/** Reads `ok` off an already-parsed body; absent (the analytics summary) counts as fine. */
internal fun JsonObject.okFlag(): Boolean? = (this["ok"] as? JsonPrimitive)
    ?.takeIf { !it.isString }
    ?.content
    ?.toBooleanStrictOrNull()

/**
 * Turns the referee's refusal into something a screen can draw. [status] is 200 when the body
 * said `"ok": false` under a success status - the failure is the same either way.
 */
internal fun httpFailure(status: Int, body: String): ApiError {
    val dto = runCatching { apiJson.decodeFromString(ErrorDto.serializer(), body) }.getOrNull()
    val code = dto?.error?.takeIf { it.isNotBlank() } ?: "http_$status"
    val detail = dto?.detail?.takeIf { it.isNotBlank() } ?: recoverDetail(status, body)

    return when {
        // Checked before the status so a `"ok": false` 200 still redraws the board's hints.
        code == ApiCodes.ILLEGAL_MOVE -> ApiError.IllegalMove(dto?.legalMoves.orEmpty())
        status == 503 || code in ApiCodes.BRAIN_DOWN -> ApiError.BrainUnavailable(detail)
        else -> ApiError.Rejected(status, code, detail, dto?.legalMoves.orEmpty())
    }
}

/**
 * A readable sentence from a response that was not the JSON we asked for.
 *
 * Something in front of the referee - Django's own error pages, a proxy, a captive portal - answers
 * in HTML, and the first 200 characters of HTML are `<!DOCTYPE html><html lang="en"><head><meta…`.
 * Showing that to a player is worse than showing nothing: it reads as the app being broken rather
 * than the server being unreachable, and it buries the one useful word on the page.
 *
 * Django puts that useful word in the `<title>` - "Bad Request (400)", "Forbidden (403)" - so it is
 * preferred, then any real text on the page, and only then a plain statement of what happened.
 */
private fun recoverDetail(status: Int, body: String): String {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return "The server returned $status with an empty body."

    if (!looksLikeMarkup(trimmed)) {
        return if (trimmed.length > 200) trimmed.take(200) + "…" else trimmed
    }

    TITLE.find(trimmed)?.groupValues?.getOrNull(1)?.let { title ->
        val cleaned = collapse(title)
        // Django names the status in its title ("Bad Request (400)"); python's http.server just
        // says "Error response", which on its own tells the player nothing. Add the code unless
        // it is already there, so every one of these reads as a specific thing that happened.
        if (cleaned.isNotBlank()) {
            return if (cleaned.contains(status.toString())) "The server answered: $cleaned"
            else "The server answered $status: $cleaned"
        }
    }

    val text = collapse(
        trimmed
            .replace(SCRIPT_OR_STYLE, " ")
            .replace(TAG, " ")
            .replace(ENTITY, " ")
    )
    if (text.isNotBlank()) return if (text.length > 200) text.take(200) + "…" else text

    return "The server returned $status as a web page instead of data."
}

private fun looksLikeMarkup(body: String): Boolean =
    body.startsWith("<") || body.contains("<html", ignoreCase = true)

private fun collapse(raw: String): String = raw.replace(WHITESPACE, " ").trim()

private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SCRIPT_OR_STYLE =
    Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG = Regex("<[^>]*>")
private val ENTITY = Regex("&[a-zA-Z#0-9]{1,8};")
private val WHITESPACE = Regex("\\s+")

/**
 * Ktor's exceptions in the board's terms. The cause chain is walked because the engine wraps
 * (an OkHttp `ConnectException` arrives inside a Ktor request failure).
 */
internal fun asApiError(cause: Throwable, url: String, timeoutSeconds: Int): ApiError {
    val seen = HashSet<Throwable>()
    var link: Throwable? = cause
    while (link != null && seen.add(link)) {
        when (link) {
            is HttpRequestTimeoutException,
            is KtorConnectTimeoutException,
            is KtorSocketTimeoutException,
            is JavaSocketTimeoutException -> return ApiError.Timeout(timeoutSeconds)

            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is PortUnreachableException -> return ApiError.Unreachable(url)

            is JsonConvertException,
            is SerializationException -> return ApiError.Malformed(link.message.orEmpty())
        }
        link = link.cause
    }

    // Anything else that got as far as the socket is, to the player, simply a backend that is not there.
    return if (cause is IOException) ApiError.Unreachable(url)
    else ApiError.Malformed(cause.message ?: cause::class.java.simpleName)
}
