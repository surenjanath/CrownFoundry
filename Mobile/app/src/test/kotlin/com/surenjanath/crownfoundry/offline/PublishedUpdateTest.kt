package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.EngineTransport
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.PublishedEngineApi
import com.surenjanath.crownfoundry.engine.EngineArtifact
import com.surenjanath.crownfoundry.engine.EngineHeader
import com.surenjanath.crownfoundry.engine.FEATURE_SIZE
import com.surenjanath.crownfoundry.engine.QNetwork
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole update path, joined up: a published manifest, through [EngineSync], into an installed
 * engine.
 *
 * The pieces were each covered already - `PublishedEngineApiTest` for which URL gets fetched and
 * `EngineSyncTest` for what the sync does with a manifest - but nothing drove the two together,
 * so "the device notices a newly published policy and ends up playing it" was the one claim with
 * no test behind it. That is the claim the whole channel exists for.
 *
 * The transport is faked rather than the HTTP engine: what is worth pinning is the decisions, and
 * that the bytes which come out of the far end are the ones that end up on disk. That the real
 * URL serves them is a separate fact, and one checked against the live site rather than in a
 * test.
 */
class PublishedUpdateTest {

    private val manifestUrl = "https://example.test/CrownFoundry/engine/manifest.json"

    private lateinit var directory: File
    private lateinit var preferences: EnginePreferences
    private lateinit var matches: LocalMatchStore

    /** Serves whatever has been "published", the way a static host does. */
    private class Published(
        var version: Int,
        var blob: ByteArray,
        var checksum: String = EngineArtifact.checksum(blob)
    ) : EngineTransport {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        var manifestFetches = 0
        var downloads = 0

        fun publish(version: Int, blob: ByteArray) {
            this.version = version
            this.blob = blob
            this.checksum = EngineArtifact.checksum(blob)
        }

        private fun manifestJson() = """
            {"ok":true,"format":1,"version":$version,"architecture":"$FEATURE_SIZE-8-1",
             "feature_size":$FEATURE_SIZE,"elo":1200,"games_trained":1,
             "size_bytes":${blob.size},"checksum":"$checksum",
             "created_at":"","notes":"","url":"policy-v$version.cfe"}
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T> getJson(
            url: String,
            serializer: KSerializer<T>,
            timeoutSeconds: Int
        ): Outcome<T> {
            manifestFetches++
            return Outcome.Success(json.decodeFromString(serializer, manifestJson()))
        }

        override suspend fun getBytes(url: String, timeoutSeconds: Int): Outcome<ByteArray> {
            downloads++
            return Outcome.Success(blob)
        }
    }

    private fun artifact(version: Int, seed: Long): ByteArray {
        val net = QNetwork(intArrayOf(FEATURE_SIZE, 8, 1)).apply { randomise(seed) }
        return EngineArtifact.write(net, EngineHeader(version = version, elo = 1200))
    }

    @Before
    fun setUp() {
        directory = File.createTempFile("crownfoundry", "published").let {
            it.delete(); it.mkdirs(); it
        }
        preferences = EnginePreferences(FakeSharedPreferences())
        matches = LocalMatchStore(File(directory, "matches.json"))
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        EngineStore.resetForTest()
    }

    /** Start with [version] already installed, the way a fresh install starts from its asset. */
    private fun install(version: Int, seed: Long): ByteArray {
        val blob = artifact(version, seed)
        runBlocking {
            EngineStore.resetForTest()
            EngineStore.initialise(directory, preferences) { blob }
        }
        return blob
    }

    // --- the claim the channel exists for ---------------------------------------------------

    @Test
    fun `a device on v43 picks up a newly published v44 and plays it`() = runTest {
        install(version = 43, seed = 1)
        assertEquals(43, EngineStore.state.header?.serverVersion)

        val published = Published(version = 44, blob = artifact(44, seed = 2))
        val sync = EngineSync(
            PublishedEngineApi(manifestUrl, published), matches, preferences, EngineStore
        )

        val result = sync.refresh()

        assertTrue("expected an update, got $result", result is EngineSync.Result.Updated)
        assertEquals(43, (result as EngineSync.Result.Updated).from)
        assertEquals(44, result.to)

        // Installed in memory...
        assertEquals(44, EngineStore.state.header?.serverVersion)
        assertEquals(EngineStatus.Ready, EngineStore.state.status)

        // ...and on disk, so the next launch reads it rather than the asset it shipped with.
        val onDisk = EngineArtifact.read(File(directory, "policy.cfe").readBytes())
        assertEquals(44, onDisk.first.version)
    }

    @Test
    fun `the weights actually change, not just the version number`() = runTest {
        install(version = 43, seed = 1)
        val before = EngineStore.withNetwork { it.weights[0][0] }

        val published = Published(version = 44, blob = artifact(44, seed = 999))
        EngineSync(PublishedEngineApi(manifestUrl, published), matches, preferences, EngineStore)
            .refresh()

        val after = EngineStore.withNetwork { it.weights[0][0] }
        assertNotEquals("the installed policy is still the old one", before, after)
    }

    @Test
    fun `a device already on the published version downloads nothing`() = runTest {
        val blob = install(version = 44, seed = 2)
        val published = Published(version = 44, blob = blob)
        val sync = EngineSync(
            PublishedEngineApi(manifestUrl, published), matches, preferences, EngineStore
        )

        val result = sync.refresh()

        assertTrue("expected up-to-date, got $result", result is EngineSync.Result.UpToDate)
        assertEquals(0, published.downloads)
    }

    /** Publishing again is what a nightly training run does; the device has to follow along. */
    @Test
    fun `successive publishes are each picked up in turn`() = runTest {
        install(version = 43, seed = 1)
        val published = Published(version = 43, blob = artifact(43, seed = 1))
        val sync = EngineSync(
            PublishedEngineApi(manifestUrl, published), matches, preferences, EngineStore
        )

        for (version in 44..47) {
            published.publish(version, artifact(version, seed = version.toLong()))
            val result = sync.refresh()
            assertTrue("v$version was not taken: $result", result is EngineSync.Result.Updated)
            assertEquals(version, EngineStore.state.header?.serverVersion)
        }
    }

    // --- what stops a bad publish landing ----------------------------------------------------

    /** The checksum is the only thing standing between a truncated transfer and a broken opponent. */
    @Test
    fun `bytes that do not match the published checksum are refused`() = runTest {
        install(version = 43, seed = 1)

        val published = Published(version = 44, blob = artifact(44, seed = 2))
        // The manifest still advertises the checksum of what was published; the bytes are not it.
        published.blob = artifact(44, seed = 7)

        val sync = EngineSync(
            PublishedEngineApi(manifestUrl, published), matches, preferences, EngineStore
        )
        val result = sync.refresh()

        assertTrue("expected a rejection, got $result", result is EngineSync.Result.Rejected)
        assertEquals("the bad download was installed", 43, EngineStore.state.header?.serverVersion)
    }

    @Test
    fun `a device with auto-update off is told it is behind but keeps what it has`() = runTest {
        install(version = 43, seed = 1)
        preferences.autoUpdate = false

        val published = Published(version = 44, blob = artifact(44, seed = 2))
        val sync = EngineSync(
            PublishedEngineApi(manifestUrl, published), matches, preferences, EngineStore
        )

        val result = sync.refresh()

        assertTrue(result is EngineSync.Result.UpdateAvailable)
        assertEquals(43, EngineStore.state.header?.serverVersion)
        assertEquals(EngineStatus.Stale, EngineStore.state.status)
        assertEquals(0, published.downloads)

        // "Update now" overrides the preference, which is what the button in Settings does.
        assertTrue(sync.refresh(force = true) is EngineSync.Result.Updated)
        assertEquals(44, EngineStore.state.header?.serverVersion)
    }

    /** A published source has nowhere to put a game, so the outbox is left alone. */
    @Test
    fun `offline games are not thrown at a static host`() = runTest {
        install(version = 43, seed = 1)
        matches.create(difficulty = "hard", rules = null, engineVersion = 43).also {
            matches.appendMove(it.matchId, "11-15", 0, "black")
            matches.finish(it.matchId, "black")
        }
        assertEquals(1, matches.pendingUploads().size)

        val published = Published(version = 43, blob = artifact(43, seed = 1))
        val upload = EngineSync(
            PublishedEngineApi(manifestUrl, published), matches, preferences, EngineStore
        ).uploadOutbox(playerId = null)

        assertEquals(0, upload.imported)
        assertEquals(null, upload.failure)
        // Still waiting, so a device later pointed at a real referee still has its games.
        assertEquals(1, matches.pendingUploads().size)
        assertEquals(1, upload.remaining)
    }

    @Test
    fun `a device with no engine at all installs the published one`() = runTest {
        runBlocking {
            EngineStore.resetForTest()
            EngineStore.initialise(directory, preferences, bundled = null)
        }
        assertEquals(EngineStatus.Missing, EngineStore.state.status)
        assertFalse(EngineStore.state.canPlayOffline)

        val published = Published(version = 44, blob = artifact(44, seed = 2))
        EngineSync(PublishedEngineApi(manifestUrl, published), matches, preferences, EngineStore)
            .refresh()

        assertEquals(EngineStatus.Ready, EngineStore.state.status)
        assertTrue(EngineStore.state.canPlayOffline)
        assertEquals(44, EngineStore.state.header?.serverVersion)
    }
}
