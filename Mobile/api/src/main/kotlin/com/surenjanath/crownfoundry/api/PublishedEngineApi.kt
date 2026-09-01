package com.surenjanath.crownfoundry.api

import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The engine, fetched from wherever it was published rather than from a running referee.
 *
 * This is what lets a build with no backend still get better opponents. The Play Store build ships
 * `crownfoundry.backendUrl=none`: there is no Django to ask, so before this existed the policy
 * inside the APK was the policy forever, and improving it meant shipping a new APK and waiting on
 * review. Pointing the same update machinery at a static manifest turns a trained policy into
 * something the phone picks up on its own, from a file in a repository.
 *
 * Nothing is trusted because of where it came from. [EngineSync] verifies the size and the SHA-256
 * from the manifest before installing, refuses a format newer than this build reads, and writes
 * through a staging file - all of which it already did against the server, and all of which
 * matters more against a URL than against a host the app was built pointing at.
 *
 * Uploads are the half that cannot work here: a static file has nowhere to put a game. That is
 * what [acceptsUploads] says, and it is why offline games stay in the outbox rather than being
 * thrown at a URL that will answer 405 forever.
 */
class PublishedEngineApi(
    /** Absolute URL of the manifest JSON. */
    private val manifestUrl: String,
    private val transport: EngineTransport = KtorEngineTransport
) : EngineApi {

    override val acceptsUploads: Boolean get() = false

    /**
     * The last manifest seen, so [downloadEngine] knows where the artifact lives.
     *
     * A static host has no `?version=` to pin against, so the pinning [EngineSync] relies on comes
     * from the manifest naming a versioned file - `policy-v43.cfe`, not `policy.cfe`. Publishing
     * to a single unversioned path would still work, and would still be checksum-verified; it
     * would just mean a device that read the manifest and then downloaded a minute later could
     * find the bytes had moved under it, and discard a sound engine as corrupt.
     */
    @Volatile
    private var lastManifest: EngineManifestDto? = null

    override suspend fun engineManifest(): Outcome<EngineManifestDto> {
        val outcome = transport.getJson(
            url = manifestUrl,
            serializer = EngineManifestDto.serializer(),
            timeoutSeconds = MANIFEST_TIMEOUT_SECONDS
        )
        if (outcome is Outcome.Success) lastManifest = outcome.value
        return outcome
    }

    override suspend fun downloadEngine(version: Int?): Outcome<ByteArray> {
        val manifest = lastManifest ?: when (val fetched = engineManifest()) {
            is Outcome.Success -> fetched.value
            is Outcome.Failure -> return fetched
        }

        if (version != null && manifest.version != version) {
            // The published manifest moved on between the two requests. Reporting it is better
            // than downloading the newer bytes and failing them against the older checksum.
            return Outcome.Failure(
                ApiError.Rejected(
                    409, "version_moved",
                    "The published engine is now v${manifest.version}, not v$version."
                )
            )
        }

        return transport.getBytes(
            url = resolve(manifest.url, manifestUrl),
            timeoutSeconds = DOWNLOAD_TIMEOUT_SECONDS
        )
    }

    override suspend fun syncOfflineMatches(
        playerId: String?,
        matches: List<OfflineMatchDto>
    ): Outcome<EngineSyncDto> = Outcome.Failure(
        ApiError.Rejected(
            405, "uploads_unsupported",
            "This build reads the engine from a published file, which has nowhere to put a game."
        )
    )

    private companion object {
        const val MANIFEST_TIMEOUT_SECONDS = 15
        const val DOWNLOAD_TIMEOUT_SECONDS = 60
    }
}

/**
 * Resolve [url] from the manifest against the manifest's own location.
 *
 * Absolute URLs are taken as they are, so a manifest on Pages can point at a release asset on a
 * different host. A relative one is resolved beside the manifest, which is what makes a published
 * directory work without every entry restating the domain.
 */
internal fun resolve(url: String, manifestUrl: String): String {
    val target = url.trim()
    if (target.isEmpty()) return manifestUrl
    if (target.startsWith("http://") || target.startsWith("https://")) return target

    val base = manifestUrl.substringBefore('?').substringBefore('#')
    if (target.startsWith("/")) {
        val separator = base.indexOf("://")
        val authorityEnd = base.indexOf('/', separator + 3)
        val origin = if (authorityEnd < 0) base else base.substring(0, authorityEnd)
        return origin + target
    }
    return base.substringBeforeLast('/', base) + "/" + target
}

/** The two requests a published engine source makes. Separated so it can be faked in tests. */
interface EngineTransport {
    suspend fun <T> getJson(
        url: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        timeoutSeconds: Int
    ): Outcome<T>

    suspend fun getBytes(url: String, timeoutSeconds: Int): Outcome<ByteArray>
}

/** The real one, over the same Ktor client the referee is reached through. */
object KtorEngineTransport : EngineTransport {

    override suspend fun <T> getJson(
        url: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        timeoutSeconds: Int
    ): Outcome<T> = try {
        val response = CrownFoundryClient.httpClient.request(url) {
            method = HttpMethod.Get
            timeout {
                requestTimeoutMillis = timeoutSeconds * 1000L
                connectTimeoutMillis = timeoutSeconds * 1000L
                socketTimeoutMillis = timeoutSeconds * 1000L
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            Outcome.Failure(httpFailure(response.status.value, body))
        } else {
            try {
                Outcome.Success(apiJson.decodeFromString(serializer, body))
            } catch (failure: Exception) {
                // A published manifest that is not JSON is usually a 404 page served with a 200,
                // which is a very ordinary thing for a static host to do.
                Outcome.Failure(
                    ApiError.Rejected(
                        200, "manifest_unreadable",
                        "The published manifest at $url is not readable (${failure.message})."
                    )
                )
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        coroutineContext.ensureActive()
        Outcome.Failure(asApiError(failure, url, timeoutSeconds))
    }

    override suspend fun getBytes(url: String, timeoutSeconds: Int): Outcome<ByteArray> = try {
        val response = CrownFoundryClient.httpClient.request(url) {
            method = HttpMethod.Get
            timeout {
                requestTimeoutMillis = timeoutSeconds * 1000L
                connectTimeoutMillis = timeoutSeconds * 1000L
                socketTimeoutMillis = timeoutSeconds * 1000L
            }
        }
        if (response.status.isSuccess()) Outcome.Success(response.body<ByteArray>())
        else Outcome.Failure(httpFailure(response.status.value, response.bodyAsText()))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        coroutineContext.ensureActive()
        Outcome.Failure(asApiError(failure, url, timeoutSeconds))
    }
}
