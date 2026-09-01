package com.surenjanath.crownfoundry.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published engine source, which is what a build with no referee updates itself from.
 *
 * The transport is faked rather than the HTTP engine, because what is worth pinning here is the
 * decisions - which URL, which version, what to do when the manifest moves - and not Ktor.
 */
class PublishedEngineApiTest {

    private val manifestUrl = "https://example.test/CrownFoundry/engine/manifest.json"

    private class FakeTransport(
        var manifestJson: String? = null,
        var bytes: ByteArray? = null,
        var jsonFailure: ApiError? = null,
        var bytesFailure: ApiError? = null
    ) : EngineTransport {
        val jsonUrls = ArrayList<String>()
        val byteUrls = ArrayList<String>()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> getJson(
            url: String,
            serializer: KSerializer<T>,
            timeoutSeconds: Int
        ): Outcome<T> {
            jsonUrls.add(url)
            jsonFailure?.let { return Outcome.Failure(it) }
            val body = manifestJson ?: return Outcome.Failure(
                ApiError.Rejected(404, "not_found", "no manifest")
            )
            return Outcome.Success(apiJson.decodeFromString(serializer, body))
        }

        override suspend fun getBytes(url: String, timeoutSeconds: Int): Outcome<ByteArray> {
            byteUrls.add(url)
            bytesFailure?.let { return Outcome.Failure(it) }
            return bytes?.let { Outcome.Success(it) }
                ?: Outcome.Failure(ApiError.Rejected(404, "not_found", "no artifact"))
        }
    }

    private fun manifest(version: Int, url: String) = """
        {"ok":true,"format":1,"version":$version,"architecture":"148-128-64-1",
         "feature_size":148,"elo":1200,"games_trained":10,"size_bytes":4,
         "checksum":"abcd","created_at":"","notes":"","url":"$url"}
    """.trimIndent()

    // --- URL resolution -------------------------------------------------------------------

    @Test
    fun `an absolute url in the manifest is used as it stands`() {
        assertEquals(
            "https://cdn.other.test/policy-v9.cfe",
            resolve("https://cdn.other.test/policy-v9.cfe", manifestUrl)
        )
    }

    @Test
    fun `a relative url resolves beside the manifest`() {
        assertEquals(
            "https://example.test/CrownFoundry/engine/policy-v9.cfe",
            resolve("policy-v9.cfe", manifestUrl)
        )
    }

    @Test
    fun `a rooted url resolves against the origin`() {
        assertEquals(
            "https://example.test/policy-v9.cfe",
            resolve("/policy-v9.cfe", manifestUrl)
        )
    }

    @Test
    fun `an empty url falls back to the manifest's own address`() {
        assertEquals(manifestUrl, resolve("", manifestUrl))
    }

    // --- the manifest ---------------------------------------------------------------------

    @Test
    fun `the manifest is read from the url it was configured with`() = runTest {
        val transport = FakeTransport(manifestJson = manifest(43, "policy-v43.cfe"))
        val api = PublishedEngineApi(manifestUrl, transport)

        val outcome = api.engineManifest()
        assertTrue(outcome is Outcome.Success)
        assertEquals(43, (outcome as Outcome.Success).value.version)
        assertEquals(listOf(manifestUrl), transport.jsonUrls)
    }

    @Test
    fun `a manifest that will not parse is reported rather than thrown`() = runTest {
        val transport = FakeTransport(
            jsonFailure = ApiError.Rejected(200, "manifest_unreadable", "not json")
        )
        val outcome = PublishedEngineApi(manifestUrl, transport).engineManifest()
        assertTrue(outcome is Outcome.Failure)
    }

    // --- the download ---------------------------------------------------------------------

    @Test
    fun `the artifact is fetched from where the manifest points`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifest(43, "policy-v43.cfe"),
            bytes = byteArrayOf(1, 2, 3, 4)
        )
        val api = PublishedEngineApi(manifestUrl, transport)
        api.engineManifest()

        val outcome = api.downloadEngine(43)
        assertTrue(outcome is Outcome.Success)
        assertEquals(
            listOf("https://example.test/CrownFoundry/engine/policy-v43.cfe"),
            transport.byteUrls
        )
    }

    /** A download with no manifest in hand fetches one rather than guessing at a path. */
    @Test
    fun `downloading without a manifest fetches one first`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifest(43, "policy-v43.cfe"),
            bytes = byteArrayOf(1, 2, 3, 4)
        )
        val outcome = PublishedEngineApi(manifestUrl, transport).downloadEngine(null)

        assertTrue(outcome is Outcome.Success)
        assertEquals(1, transport.jsonUrls.size)
    }

    /**
     * The pinning the server does with `?version=`, done by refusing instead.
     *
     * A static host serves whatever is there now. If the published manifest moved between the
     * device reading it and downloading, the bytes would be checked against the wrong checksum
     * and a sound engine discarded as corrupt - so the mismatch is reported as itself.
     */
    @Test
    fun `a version that moved under the download is refused, not fetched`() = runTest {
        val transport = FakeTransport(
            manifestJson = manifest(44, "policy-v44.cfe"),
            bytes = byteArrayOf(1, 2, 3, 4)
        )
        val api = PublishedEngineApi(manifestUrl, transport)
        api.engineManifest()

        val outcome = api.downloadEngine(43)
        assertTrue(outcome is Outcome.Failure)
        assertEquals(
            "version_moved",
            ((outcome as Outcome.Failure).reason as ApiError.Rejected).code
        )
        assertTrue("a refused download still fetched bytes", transport.byteUrls.isEmpty())
    }

    // --- uploads --------------------------------------------------------------------------

    @Test
    fun `a published source does not accept uploads`() = runTest {
        val api = PublishedEngineApi(manifestUrl, FakeTransport())
        assertFalse(api.acceptsUploads)

        val outcome = api.syncOfflineMatches(null, listOf(OfflineMatchDto(localId = "a")))
        assertTrue(outcome is Outcome.Failure)
    }

    @Test
    fun `the referee does accept uploads`() {
        assertTrue(CrownFoundryClient.acceptsUploads)
    }
}
