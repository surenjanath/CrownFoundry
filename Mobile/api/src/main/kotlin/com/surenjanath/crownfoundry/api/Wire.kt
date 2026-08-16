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
    val detail = dto?.detail?.takeIf { it.isNotBlank() } ?: recoverDetail(body)

    return when {
        // Checked before the status so a `"ok": false` 200 still redraws the board's hints.
        code == ApiCodes.ILLEGAL_MOVE -> ApiError.IllegalMove(dto?.legalMoves.orEmpty())
        status == 503 || code in ApiCodes.BRAIN_DOWN -> ApiError.BrainUnavailable(detail)
        else -> ApiError.Rejected(status, code, detail, dto?.legalMoves.orEmpty())
    }
}

/** A Django debug page or a proxy's plain text still carries a hint worth showing. */
private fun recoverDetail(body: String): String = body.trim().let {
    if (it.length > 200) it.take(200) + "…" else it
}

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
