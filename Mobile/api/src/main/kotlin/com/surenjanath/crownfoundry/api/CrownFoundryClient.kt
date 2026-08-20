package com.surenjanath.crownfoundry.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The one client the app talks through. A single engine for the process, a base URL that Settings
 * can move underneath it, and no exception ever crossing back into a composable.
 */
object CrownFoundryClient : CheckersApi, EngineApi {

    /** Enough for the referee, which only ever moves pieces on a board. */
    const val NORMAL_TIMEOUT_SECONDS = 10

    /**
     * The engine artifact is small - about 110 KB for the shipped architecture - but it may be
     * arriving over whatever the phone has, and a download that gives up at ten seconds would
     * leave offline mode permanently unavailable on a bad connection.
     */
    const val DOWNLOAD_TIMEOUT_SECONDS = 60

    /** A full outbox means the server is replaying and training on every game in it. */
    const val SYNC_TIMEOUT_SECONDS = 120

    /**
     * The AI turn may be waiting on a local LLM to finish a sentence. Ten seconds would abandon a
     * move the backend is about to make, so this one call gets its own, per-request, budget.
     */
    const val AI_TURN_TIMEOUT_SECONDS = 90

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL
        set(value) {
            field = normaliseBaseUrl(value)
        }

    private val engineClient: HttpClient by lazy { buildClient(OkHttp.create()) }

    @Volatile
    private var installed: HttpClient? = null

    internal val httpClient: HttpClient get() = installed ?: engineClient

    /** Test seam: swap the engine without touching any of the call sites. `null` restores OkHttp. */
    internal fun installEngine(engine: HttpClientEngine?) {
        installed?.close()
        installed = engine?.let(::buildClient)
    }

    private fun buildClient(engine: HttpClientEngine) = HttpClient(engine) {
        expectSuccess = false

        install(ContentNegotiation) { json(apiJson) }
        install(ContentEncoding) { gzip(); deflate() }
        install(HttpTimeout) {
            requestTimeoutMillis = NORMAL_TIMEOUT_SECONDS.seconds
            connectTimeoutMillis = NORMAL_TIMEOUT_SECONDS.seconds
            socketTimeoutMillis = NORMAL_TIMEOUT_SECONDS.seconds
        }
    }

    // --- endpoints ------------------------------------------------------------------------------

    override suspend fun health(): Outcome<HealthDto> =
        call("/api/health/", HealthDto.serializer())

    override suspend fun startMatch(
        difficulty: String,
        playerId: String?,
        rules: MatchRulesDto?
    ): Outcome<MatchDto> =
        call(
            path = "/api/match/start/",
            serializer = MatchDto.serializer(),
            method = HttpMethod.Post,
            body = buildJsonObject {
                put("difficulty", difficulty)
                playerId?.let { put("player_id", it) }
                rules?.let {
                    put("rules", buildJsonObject {
                        put("flying_kings", it.flyingKings)
                        put("men_capture_backwards", it.menCaptureBackwards)
                        put("mandatory_capture", it.mandatoryCapture)
                    })
                }
            }
        )

    override suspend fun match(matchId: String): Outcome<MatchDto> =
        call("/api/match/${matchId.trim()}/", MatchDto.serializer())

    override suspend fun matches(playerId: String?, limit: Int): Outcome<MatchListDto> =
        call(
            path = "/api/matches/",
            serializer = MatchListDto.serializer(),
            query = buildList {
                playerId?.let { add("player_id" to it) }
                add("limit" to limit.toString())
            }
        )

    override suspend fun playMove(matchId: String, move: String): Outcome<MoveResultDto> =
        postMove(buildJsonObject {
            put("match_id", matchId)
            put("player_move", move)
        })

    override suspend fun playMove(matchId: String, from: Int, to: Int): Outcome<MoveResultDto> =
        postMove(buildJsonObject {
            put("match_id", matchId)
            put("from", from)
            put("to", to)
        })

    private suspend fun postMove(body: JsonObject) = call(
        path = "/api/match/move/",
        serializer = MoveResultDto.serializer(),
        method = HttpMethod.Post,
        body = body
    )

    override suspend fun generateAiTurn(matchId: String): Outcome<AiTurnDto> = call(
        path = "/api/ai/generate-turn/",
        serializer = AiTurnDto.serializer(),
        method = HttpMethod.Post,
        body = buildJsonObject { put("match_id", matchId) },
        timeoutSeconds = AI_TURN_TIMEOUT_SECONDS
    )

    override suspend fun resign(matchId: String): Outcome<ResignDto> = call(
        path = "/api/match/${matchId.trim()}/resign/",
        serializer = ResignDto.serializer(),
        method = HttpMethod.Post,
        body = buildJsonObject { }
    )

    override suspend fun performance(): Outcome<PerformanceDto> =
        call("/api/analytics/ai-performance/", PerformanceDto.serializer())

    override suspend fun summary(): Outcome<AnalyticsSummaryDto> =
        call("/api/analytics/summary/", AnalyticsSummaryDto.serializer())

    // --- engine distribution ----------------------------------------------------------------

    override suspend fun engineManifest(): Outcome<EngineManifestDto> =
        call(ENGINE_MANIFEST_PATH, EngineManifestDto.serializer())

    override suspend fun downloadEngine(version: Int?): Outcome<ByteArray> {
        val url = baseUrl + ENGINE_DOWNLOAD_PATH +
                (version?.let { "?version=$it" } ?: "")
        return try {
            val response = httpClient.request(url) {
                method = HttpMethod.Get
                timeout {
                    requestTimeoutMillis = DOWNLOAD_TIMEOUT_SECONDS.seconds
                    socketTimeoutMillis = DOWNLOAD_TIMEOUT_SECONDS.seconds
                    connectTimeoutMillis = NORMAL_TIMEOUT_SECONDS.seconds
                }
            }
            if (response.status.isSuccess()) Outcome.Success(response.body<ByteArray>())
            else Outcome.Failure(httpFailure(response.status.value, response.bodyAsText()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            coroutineContext.ensureActive()
            Outcome.Failure(asApiError(failure, url, DOWNLOAD_TIMEOUT_SECONDS))
        }
    }

    override suspend fun syncOfflineMatches(
        playerId: String?,
        matches: List<OfflineMatchDto>
    ): Outcome<EngineSyncDto> = call(
        path = ENGINE_SYNC_PATH,
        serializer = EngineSyncDto.serializer(),
        method = HttpMethod.Post,
        body = buildJsonObject {
            playerId?.let { put("player_id", it) }
            put("matches", apiJson.encodeToJsonElement(ListSerializer(OfflineMatchDto.serializer()), matches))
        },
        timeoutSeconds = SYNC_TIMEOUT_SECONDS
    )

    /**
     * Settings' "test connection": probes a candidate URL without adopting it, so a typo cannot
     * strand a live match on a host that does not answer.
     */
    suspend fun testConnection(candidateUrl: String): Outcome<HealthDto> = call(
        path = "/api/health/",
        serializer = HealthDto.serializer(),
        base = normaliseBaseUrl(candidateUrl)
    )

    // --- plumbing -------------------------------------------------------------------------------

    private suspend fun <T> call(
        path: String,
        serializer: KSerializer<T>,
        method: HttpMethod = HttpMethod.Get,
        body: JsonObject? = null,
        query: List<Pair<String, String>> = emptyList(),
        timeoutSeconds: Int = NORMAL_TIMEOUT_SECONDS,
        base: String = baseUrl
    ): Outcome<T> {
        val url = base + path
        return try {
            val response = httpClient.request(url) {
                this.method = method
                timeout {
                    requestTimeoutMillis = timeoutSeconds.seconds
                    socketTimeoutMillis = timeoutSeconds.seconds
                    // Reaching the host is quick or not happening; only the answer is slow.
                    connectTimeoutMillis = NORMAL_TIMEOUT_SECONDS.seconds
                }
                query.forEach { (key, value) -> parameter(key, value) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }

            val text = response.bodyAsText()
            if (response.status.isSuccess()) decode(text, serializer)
            else Outcome.Failure(httpFailure(response.status.value, text))
        } catch (cancellation: CancellationException) {
            // A screen leaving composition cancels its calls; that is not a failure to draw.
            throw cancellation
        } catch (failure: Throwable) {
            coroutineContext.ensureActive()
            Outcome.Failure(asApiError(failure, url, timeoutSeconds))
        }
    }

    private fun <T> decode(text: String, serializer: KSerializer<T>): Outcome<T> = try {
        val element = apiJson.parseToJsonElement(text).jsonObject
        // A 200 carrying `"ok": false` is still a refusal - DRF views answer that way on soft errors.
        if (element.okFlag() == false) Outcome.Failure(httpFailure(200, text))
        else Outcome.Success(apiJson.decodeFromJsonElement(serializer, element))
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        Outcome.Failure(ApiError.Malformed(failure.message ?: text.take(120)))
    }

    private val Int.seconds get() = this * 1000L
}
