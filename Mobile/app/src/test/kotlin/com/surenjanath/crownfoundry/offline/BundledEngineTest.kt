package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.engine.EngineArtifact
import com.surenjanath.crownfoundry.engine.EngineHeader
import com.surenjanath.crownfoundry.engine.FEATURE_SIZE
import com.surenjanath.crownfoundry.engine.QNetwork
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first launch of an install that has never met a server.
 *
 * This is the Play Store case, and before the APK carried an engine it was a dead end: no
 * artifact on disk, no reachable referee, so `canPlayOffline` was false and tapping Play produced
 * an error rather than a game. These tests pin the fix at the level that matters - not "the file
 * parsed", but "a fresh device can play a move".
 */
class BundledEngineTest {

    private fun freshDirectory(): File =
        File.createTempFile("crownfoundry", "bundle").let {
            it.delete()
            it.mkdirs()
            it
        }

    private fun bundledArtifact(version: Int = 33): ByteArray = EngineArtifact.write(
        QNetwork(intArrayOf(FEATURE_SIZE, 24, 1)).apply { randomise(seed = 3) },
        EngineHeader(version = version, elo = 1180, gamesTrained = 6704, baseVersion = version)
    )

    @Test
    fun `a fresh install with no server can play offline`() = runBlocking {
        EngineStore.resetForTest()
        val preferences = EnginePreferences(FakeSharedPreferences())

        EngineStore.initialise(freshDirectory(), preferences) { bundledArtifact() }

        assertTrue(
            "a bundled engine must make the first launch playable",
            EngineStore.state.canPlayOffline
        )
        assertEquals(EngineStatus.Ready, EngineStore.state.status)
        assertEquals(33, EngineStore.state.header?.version)
    }

    @Test
    fun `the bundled engine is written to disk so the next launch reads it normally`() =
        runBlocking {
            EngineStore.resetForTest()
            val directory = freshDirectory()

            EngineStore.initialise(directory, EnginePreferences(FakeSharedPreferences())) {
                bundledArtifact()
            }
            assertTrue(File(directory, "policy.cfe").exists())

            // Second launch: same directory, and now nothing to seed from.
            EngineStore.resetForTest()
            EngineStore.initialise(directory, EnginePreferences(FakeSharedPreferences()))
            assertTrue(EngineStore.state.canPlayOffline)
        }

    @Test
    fun `an engine already on disk is not replaced by the bundled one`() = runBlocking {
        val directory = freshDirectory()
        val preferences = EnginePreferences(FakeSharedPreferences())
        EngineStore.resetForTest()
        EngineStore.initialise(directory, preferences)
        EngineStore.install(
            EngineArtifact.write(
                QNetwork(intArrayOf(FEATURE_SIZE, 24, 1)).apply { randomise(seed = 9) },
                EngineHeader(version = 90, elo = 1400, baseVersion = 90)
            ),
            preferences
        ).getOrThrow()

        EngineStore.resetForTest()
        EngineStore.initialise(directory, EnginePreferences(FakeSharedPreferences())) {
            bundledArtifact(version = 33)
        }

        assertEquals(
            "a downloaded engine outranks the one shipped in the APK",
            90,
            EngineStore.state.header?.version
        )
    }

    @Test
    fun `a build with no bundled asset still reports missing rather than crashing`() = runBlocking {
        EngineStore.resetForTest()
        EngineStore.initialise(freshDirectory(), EnginePreferences(FakeSharedPreferences())) {
            throw java.io.FileNotFoundException("policy.cfe")
        }

        assertEquals(EngineStatus.Missing, EngineStore.state.status)
        assertFalse(EngineStore.state.canPlayOffline)
    }

    @Test
    fun `a corrupt bundled asset degrades to missing rather than loading nonsense`() = runBlocking {
        EngineStore.resetForTest()
        EngineStore.initialise(freshDirectory(), EnginePreferences(FakeSharedPreferences())) {
            ByteArray(64) { 0x7f }
        }

        assertEquals(EngineStatus.Missing, EngineStore.state.status)
        assertFalse(EngineStore.state.canPlayOffline)
    }

    @Test
    fun `the bundled engine actually answers with a legal move`() = runBlocking {
        EngineStore.resetForTest()
        val directory = freshDirectory()
        val preferences = EnginePreferences(FakeSharedPreferences())
        EngineStore.initialise(directory, preferences) { bundledArtifact() }

        val api = OfflineCheckersApi(
            store = LocalMatchStore(File(directory, "matches.json")),
            preferences = preferences,
            searchDepth = 2,
            nodeBudget = 300
        )
        val match = api.startMatch("adaptive", playerId = null, rules = null)
        assertNotNull(match.valueOrNull)

        val id = match.valueOrNull!!.matchId
        val played = api.playMove(id, "11-15")
        assertNotNull("the bundled engine must referee a real move", played.valueOrNull)

        val turn = api.generateAiTurn(id)
        assertNotNull("the bundled engine must answer with a move", turn.valueOrNull)
    }
}
