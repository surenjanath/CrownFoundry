package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.Side
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The corpus of games played offline.
 *
 * Two things here are load-bearing beyond "it round-trips": what gets thrown away when the store
 * fills up, and whether a file that will not parse takes the app down with it.
 */
class LocalMatchStoreTest {

    private lateinit var file: File
    private lateinit var store: LocalMatchStore

    @Before
    fun setUp() {
        file = File.createTempFile("crownfoundry-matches", ".json")
        file.delete()
        store = LocalMatchStore(file)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `a match round trips through the file`() = runTest {
        val rules = MatchRulesDto(flyingKings = false, menCaptureBackwards = false)
        val created = store.create("hard", rules, engineVersion = 12)
        store.appendMove(created.matchId, "11-15", 0, Side.HUMAN)
        store.appendMove(created.matchId, "22-18", 0, Side.AI, reasoning = "Playing 22-18: ...")
        store.appendMove(created.matchId, "15x22", 1, Side.HUMAN)
        store.finish(created.matchId, Side.HUMAN)

        val reloaded = LocalMatchStore(file).find(created.matchId)

        assertNotNull(reloaded)
        assertEquals(listOf("11-15", "22-18", "15x22"), reloaded!!.moves)
        assertEquals(Side.HUMAN, reloaded.winner)
        assertEquals(rules, reloaded.rules)
        assertEquals(12, reloaded.engineVersion)
        assertEquals(1, reloaded.humanCaptures)
        assertEquals(0, reloaded.aiCaptures)
        assertEquals(listOf("Playing 22-18: ..."), reloaded.reasoning)
        assertTrue(reloaded.isFinished)
    }

    @Test
    fun `match ids are recognisably local`() = runTest {
        val created = store.create("adaptive", null, 1)
        assertTrue(LocalMatchStore.isOffline(created.matchId))
        assertFalse(LocalMatchStore.isOffline("2c9b8a1e-0000-0000-0000-000000000000"))
        assertFalse(LocalMatchStore.isOffline(null))
    }

    @Test
    fun `only finished, un-uploaded games are in the outbox`() = runTest {
        val active = store.create("easy", null, 1)
        store.appendMove(active.matchId, "11-15", 0, Side.HUMAN)

        val done = store.create("easy", null, 1)
        store.appendMove(done.matchId, "11-15", 0, Side.HUMAN)
        store.finish(done.matchId, Side.AI)

        assertEquals(listOf(done.localId), store.pendingUploads().map { it.localId })

        store.markUploaded(listOf(done.localId))
        assertTrue(store.pendingUploads().isEmpty())
        // Idempotent: a duplicated response must not throw or resurrect anything.
        store.markUploaded(listOf(done.localId, "never-existed"))
        assertTrue(store.pendingUploads().isEmpty())
    }

    @Test
    fun `an empty game is never offered to the server`() = runTest {
        val empty = store.create("easy", null, 1)
        store.finish(empty.matchId, Side.AI)
        assertTrue(store.pendingUploads().isEmpty())
    }

    @Test
    fun `trimming drops synced games before un-synced ones`() = runTest {
        // Fill well past the cap, alternating between games the server already has and games it
        // does not. Losing the latter throws away learning nobody else has a copy of.
        repeat(140) { index ->
            val match = store.create("easy", null, 1)
            store.appendMove(match.matchId, "11-15", 0, Side.HUMAN)
            store.finish(match.matchId, Side.AI)
            if (index % 2 == 0) store.markUploaded(listOf(match.localId))
        }

        val all = store.all()
        assertTrue("store grew past its cap: ${all.size}", all.size <= 100)
        // 70 games were never uploaded, and all of them should have survived a 100-game cap.
        assertEquals(70, store.pendingUploads().size)
    }

    @Test
    fun `the newest active match always survives a trim`() = runTest {
        repeat(120) { index ->
            val match = store.create("easy", null, 1)
            store.appendMove(match.matchId, "11-15", 0, Side.HUMAN)
            store.finish(match.matchId, Side.AI)
            store.markUploaded(listOf(match.localId))
        }
        val current = store.create("hard", null, 1)

        assertNotNull("the match just created was evicted", store.find(current.matchId))
    }

    @Test
    fun `a corpus that will not parse starts empty rather than crashing`() = runTest {
        file.writeText("{ this is not json")
        val broken = LocalMatchStore(file)

        assertTrue(broken.all().isEmpty())
        // And it recovers: the next write replaces the garbage.
        val created = broken.create("easy", null, 1)
        assertNotNull(LocalMatchStore(file).find(created.matchId))
    }

    @Test
    fun `mistakes are remembered per position and bounded`() = runTest {
        store.recordMistakes(listOf("fen-a" to "11-15", "fen-a" to "9-14", "fen-b" to "12-16"))

        val memory = store.mistakeMemory()
        assertEquals(setOf("11-15", "9-14"), memory.knownMistakes("fen-a"))
        assertEquals(setOf("12-16"), memory.knownMistakes("fen-b"))
        assertTrue(memory.knownMistakes("fen-unknown").isEmpty())

        // Recording the same pair twice must not duplicate it.
        store.recordMistakes(listOf("fen-a" to "11-15"))
        assertEquals(2, store.mistakeMemory().knownMistakes("fen-a").size)

        // Unbounded memory would eventually condemn most of the opening.
        store.recordMistakes((0..500).map { "fen-$it" to "11-15" })
        assertTrue(store.mistakeMemory().knownMistakes("fen-500").isNotEmpty())
        assertTrue(store.mistakeMemory().knownMistakes("fen-a").isEmpty())
    }

    @Test
    fun `the opponent profile reads the human's own moves`() = runTest {
        // Black (the human) opens 11-15, White replies 22-18, Black takes 15x22.
        val match = store.create("adaptive", null, 1)
        for ((notation, captures, side) in listOf(
            Triple("11-15", 0, Side.HUMAN),
            Triple("22-18", 0, Side.AI),
            Triple("15x22", 1, Side.HUMAN)
        )) {
            store.appendMove(match.matchId, notation, captures, side)
        }
        store.finish(match.matchId, Side.HUMAN)

        val profile = store.opponentProfile()

        assertEquals(1, profile.totalGames)
        assertEquals(1f, profile.winRate, 1e-6f)
        // One capture across two human plies.
        assertEquals(0.5f, profile.styleAggression, 1e-6f)
        assertEquals(0f, profile.styleKingRush, 1e-6f)
    }

    @Test
    fun `an unplayed corpus has a neutral profile`() = runTest {
        val profile = store.opponentProfile()
        assertEquals(0, profile.totalGames)
        assertEquals(0f, profile.winRate, 0f)
        assertEquals(0f, profile.styleAggression, 0f)
    }

    @Test
    fun `appending to an unknown match reports it rather than inventing one`() = runTest {
        assertNull(store.appendMove("offline-nope", "11-15", 0, Side.HUMAN))
        assertNull(store.finish("offline-nope", Side.AI))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `clearing removes everything`() = runTest {
        val match = store.create("easy", null, 1)
        store.recordMistakes(listOf("fen-a" to "11-15"))
        store.finish(match.matchId, Side.AI)

        store.clear()

        assertTrue(store.all().isEmpty())
        assertTrue(store.mistakeMemory().knownMistakes("fen-a").isEmpty())
        assertTrue(LocalMatchStore(file).all().isEmpty())
    }
}
