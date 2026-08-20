package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.EngineApi
import com.surenjanath.crownfoundry.api.EngineManifestDto
import com.surenjanath.crownfoundry.api.EngineSyncDto
import com.surenjanath.crownfoundry.api.OfflineMatchDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.SyncAcceptedDto
import com.surenjanath.crownfoundry.api.SyncRejectedDto
import com.surenjanath.crownfoundry.engine.ARTIFACT_FORMAT
import com.surenjanath.crownfoundry.engine.EngineArtifact
import com.surenjanath.crownfoundry.engine.EngineHeader
import com.surenjanath.crownfoundry.engine.FEATURE_SIZE
import com.surenjanath.crownfoundry.engine.QNetwork
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Is my engine current, and what happens when it is not?"
 *
 * This is the file that pins the behaviour the whole feature was asked for: a device that has
 * fallen behind says so, a device that has never connected says something different, and a
 * download that is not what was advertised is thrown away rather than installed.
 */
class EngineSyncTest {

    private lateinit var directory: File
    private lateinit var preferences: EnginePreferences
    private lateinit var matches: LocalMatchStore
    private lateinit var api: FakeEngineApi
    private lateinit var sync: EngineSync

    @Before
    fun setUp() {
        directory = File.createTempFile("crownfoundry", "sync").let {
            it.delete(); it.mkdirs(); it
        }
        preferences = EnginePreferences(FakeSharedPreferences())
        matches = LocalMatchStore(File(directory, "matches.json"))
        api = FakeEngineApi()
        sync = EngineSync(api, matches, preferences, EngineStore)

        runBlocking {
            EngineStore.resetForTest()
            EngineStore.initialise(directory, preferences)
        }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
        EngineStore.resetForTest()
    }

    // --- the update check --------------------------------------------------------------------

    @Test
    fun `a device that has never connected has no engine and says so`() {
        val state = EngineStore.state
        assertEquals(EngineStatus.Missing, state.status)
        assertFalse(state.canPlayOffline)
        assertTrue(state.needsUpdate)
        assertEquals("AI engine needs updating", state.headline)
    }

    @Test
    fun `the download is pinned to the version the manifest named`() = runTest {
        // Otherwise training publishing a new policy between the manifest and the download would
        // have the device verify vN+1 bytes against a vN checksum and discard a sound engine.
        api.publish(version = 7, elo = 1300)

        sync.refresh()

        assertEquals(7, api.lastRequestedVersion)
    }

    @Test
    fun `the first refresh downloads and installs`() = runTest {
        api.publish(version = 3, elo = 1240)

        val result = sync.refresh()

        assertTrue("$result", result is EngineSync.Result.Updated)
        assertEquals(3, (result as EngineSync.Result.Updated).to)
        assertEquals(EngineStatus.Ready, EngineStore.state.status)
        assertTrue(EngineStore.state.canPlayOffline)
        assertFalse(EngineStore.state.needsUpdate)
        assertEquals("v3", EngineStore.state.label)
        assertTrue(EngineStore.state.headline.contains("ready offline"))
    }

    @Test
    fun `a second refresh against the same version downloads nothing`() = runTest {
        api.publish(version = 3)
        sync.refresh()
        val downloads = api.downloads

        val result = sync.refresh()

        assertTrue("$result", result is EngineSync.Result.UpToDate)
        assertEquals(downloads, api.downloads)
    }

    @Test
    fun `a newer policy on the server makes the local engine stale`() = runTest {
        api.publish(version = 3)
        sync.refresh()

        api.publish(version = 4)
        preferences.autoUpdate = false
        val result = sync.refresh()

        assertTrue("$result", result is EngineSync.Result.UpdateAvailable)
        val state = EngineStore.state
        assertEquals(EngineStatus.Stale, state.status)
        // Stale is still playable - that is the whole point of shipping weights rather than a URL.
        assertTrue(state.canPlayOffline)
        assertTrue(state.needsUpdate)
        assertEquals("AI engine needs updating", state.headline)
        assertEquals(1, state.versionsBehind)
        assertTrue(state.detail, state.detail.contains("v3"))
        assertTrue(state.detail, state.detail.contains("v4"))
    }

    @Test
    fun `forcing an update takes it even with auto-update off`() = runTest {
        api.publish(version = 3)
        sync.refresh()
        api.publish(version = 9)
        preferences.autoUpdate = false

        val result = sync.refresh(force = true)

        assertEquals(9, (result as EngineSync.Result.Updated).to)
        assertEquals(EngineStatus.Ready, EngineStore.state.status)
    }

    @Test
    fun `auto-update takes a new version without being asked`() = runTest {
        api.publish(version = 3)
        sync.refresh()
        api.publish(version = 4)

        assertTrue(sync.refresh() is EngineSync.Result.Updated)
        assertEquals(4, EngineStore.state.header?.serverVersion)
    }

    // --- refusing bad downloads ---------------------------------------------------------------

    @Test
    fun `a download that does not match its checksum is discarded`() = runTest {
        api.publish(version = 3)
        api.corruptDownload = true

        val result = sync.refresh()

        assertTrue("$result", result is EngineSync.Result.Rejected)
        assertTrue((result as EngineSync.Result.Rejected).detail.contains("checksum"))
        // Nothing was installed, so the device is still honestly reporting that it has no engine.
        assertEquals(EngineStatus.Missing, EngineStore.state.status)
    }

    @Test
    fun `a truncated download is discarded`() = runTest {
        api.publish(version = 3)
        api.truncateDownload = true

        assertTrue(sync.refresh() is EngineSync.Result.Rejected)
        assertEquals(EngineStatus.Missing, EngineStore.state.status)
    }

    @Test
    fun `a future artifact format is refused before it is even downloaded`() = runTest {
        api.publish(version = 3, format = ARTIFACT_FORMAT + 1)

        val result = sync.refresh()

        assertTrue("$result", result is EngineSync.Result.Rejected)
        assertTrue((result as EngineSync.Result.Rejected).detail.contains("Update CrownFoundry"))
        assertEquals(0, api.downloads)
    }

    @Test
    fun `an unreachable referee is reported and changes nothing`() = runTest {
        api.publish(version = 3)
        sync.refresh()

        api.manifestOutcome = Outcome.Failure(ApiError.Unreachable("http://nope:8000"))
        val result = sync.refresh()

        assertTrue("$result", result is EngineSync.Result.Failed)
        assertEquals(EngineStatus.Ready, EngineStore.state.status)
        assertEquals(3, EngineStore.state.header?.serverVersion)
    }

    // --- the outbox ---------------------------------------------------------------------------

    @Test
    fun `finished offline games are sent and marked done`() = runTest {
        api.publish(version = 2)
        sync.refresh()
        val first = seedFinishedMatch("11-15", "22-18")
        val second = seedFinishedMatch("9-14")

        val result = sync.uploadOutbox("player-1")

        assertEquals(2, result.imported)
        assertEquals(0, result.remaining)
        assertTrue(matches.pendingUploads().isEmpty())
        assertEquals(setOf(first, second), api.uploaded.map { it.localId }.toSet())
        assertEquals(listOf("11-15", "22-18"), api.uploaded.first { it.localId == first }.moves)
    }

    @Test
    fun `a game the server will never accept stops being retried`() = runTest {
        val doomed = seedFinishedMatch("11-15")
        api.rejectAllWith = "illegal_move"

        val result = sync.uploadOutbox("player-1")

        assertEquals(1, result.discarded)
        assertEquals(0, result.remaining)
        assertTrue("a permanently rejected game must leave the outbox", matches.pendingUploads().isEmpty())
        assertTrue(doomed.isNotEmpty())
    }

    @Test
    fun `a transient failure keeps the game for next time`() = runTest {
        seedFinishedMatch("11-15")
        api.rejectAllWith = "server_exploded"

        val result = sync.uploadOutbox("player-1")

        assertEquals(0, result.imported)
        assertEquals(1, matches.pendingUploads().size)
    }

    @Test
    fun `a failed call leaves the whole outbox alone`() = runTest {
        seedFinishedMatch("11-15")
        api.syncOutcome = Outcome.Failure(ApiError.Unreachable("http://nope:8000"))

        val result = sync.uploadOutbox("player-1")

        assertNotNull(result.failure)
        assertEquals(1, result.remaining)
        assertEquals(1, matches.pendingUploads().size)
    }

    @Test
    fun `the manifest in a sync response updates the engine state`() = runTest {
        api.publish(version = 2)
        sync.refresh()
        seedFinishedMatch("11-15")

        // Importing the game trained the server, so it answers with a version this device lacks.
        api.publish(version = 3)
        sync.uploadOutbox("player-1")

        assertEquals(3, EngineStore.state.serverVersion)
        assertEquals(EngineStatus.Stale, EngineStore.state.status)
    }

    @Test
    fun `synchronise does both halves and leaves the device current`() = runTest {
        api.publish(version = 2)
        seedFinishedMatch("11-15")

        val (upload, refresh) = sync.synchronise("player-1")

        assertEquals(1, upload.imported)
        assertTrue("$refresh", refresh is EngineSync.Result.Updated)
        assertEquals(EngineStatus.Ready, EngineStore.state.status)
        assertEquals(0, EngineStore.state.pendingUploads)
    }

    @Test
    fun `an empty outbox is not a failure`() = runTest {
        val result = sync.uploadOutbox("player-1")
        assertEquals(0, result.imported)
        assertEquals(0, result.remaining)
        assertEquals(0, api.uploaded.size)
    }

    // --- local training against the version badge ---------------------------------------------

    @Test
    fun `local training shows on the badge without hiding the server version`() = runTest {
        api.publish(version = 6)
        sync.refresh()

        EngineStore.persistLocalTraining(gamesLearned = 2, loss = 0.4f, preferences = preferences)

        assertEquals("v6 +2", EngineStore.state.label)
        assertEquals(EngineStatus.Ready, EngineStore.state.status)
        // And it survives a reload, which is the point of writing it into the header.
        EngineStore.resetForTest()
        EngineStore.initialise(directory, preferences)
        assertEquals("v6 +2", EngineStore.state.label)
        assertEquals(6, EngineStore.state.header?.serverVersion)
    }

    @Test
    fun `a locally trained engine still knows when the server has moved on`() = runTest {
        api.publish(version = 6)
        sync.refresh()
        EngineStore.persistLocalTraining(gamesLearned = 3, loss = 0.2f, preferences = preferences)

        api.publish(version = 7)
        preferences.autoUpdate = false
        sync.refresh()

        assertEquals(EngineStatus.Stale, EngineStore.state.status)
        assertEquals("v6 +3", EngineStore.state.label)
        assertTrue(EngineStore.state.detail, EngineStore.state.detail.contains("3 offline games"))
    }

    // --- helpers -------------------------------------------------------------------------------

    private suspend fun seedFinishedMatch(vararg moves: String): String {
        val match = matches.create("adaptive", null, engineVersion = 1)
        for (notation in moves) {
            matches.appendMove(match.matchId, notation, 0, "black")
        }
        matches.finish(match.matchId, "white")
        return match.localId
    }
}

/** A referee that publishes engine artifacts from memory. */
private class FakeEngineApi : EngineApi {

    private var blob: ByteArray = ByteArray(0)
    private var manifest = EngineManifestDto(version = 0)

    var manifestOutcome: Outcome<EngineManifestDto>? = null
    var syncOutcome: Outcome<EngineSyncDto>? = null
    var corruptDownload = false
    var truncateDownload = false
    var rejectAllWith: String? = null

    var downloads = 0
        private set

    /** The version the client asked for, so tests can assert it pinned the manifest's. */
    var lastRequestedVersion: Int? = null
        private set

    val uploaded = mutableListOf<OfflineMatchDto>()

    fun publish(version: Int, elo: Int = 1200, format: Int = ARTIFACT_FORMAT) {
        val net = QNetwork(intArrayOf(FEATURE_SIZE, 16, 1)).apply { randomise(seed = version.toLong()) }
        blob = EngineArtifact.write(
            net,
            EngineHeader(version = version, elo = elo, gamesTrained = version * 10)
        )
        manifest = EngineManifestDto(
            ok = true,
            format = format,
            version = version,
            architecture = net.architecture,
            featureSize = FEATURE_SIZE,
            elo = elo,
            gamesTrained = version * 10,
            sizeBytes = blob.size.toLong(),
            checksum = EngineArtifact.checksum(blob)
        )
    }

    override suspend fun engineManifest(): Outcome<EngineManifestDto> =
        manifestOutcome ?: Outcome.Success(manifest)

    override suspend fun downloadEngine(version: Int?): Outcome<ByteArray> {
        downloads++
        lastRequestedVersion = version
        return Outcome.Success(
            when {
                truncateDownload -> blob.copyOf(blob.size / 2)
                corruptDownload -> blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
                else -> blob
            }
        )
    }

    override suspend fun syncOfflineMatches(
        playerId: String?,
        matches: List<OfflineMatchDto>
    ): Outcome<EngineSyncDto> {
        syncOutcome?.let { return it }

        rejectAllWith?.let { code ->
            return Outcome.Success(
                EngineSyncDto(
                    rejected = matches.map { SyncRejectedDto(localId = it.localId, error = code) },
                    engine = manifest
                )
            )
        }

        uploaded += matches
        return Outcome.Success(
            EngineSyncDto(
                accepted = matches.map { SyncAcceptedDto(localId = it.localId, matchId = "server-${it.localId}") },
                imported = matches.size,
                engine = manifest
            )
        )
    }
}
