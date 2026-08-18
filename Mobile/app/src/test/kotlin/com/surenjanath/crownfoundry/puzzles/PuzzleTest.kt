package com.surenjanath.crownfoundry.puzzles

import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.Board
import com.surenjanath.crownfoundry.engine.MoveQuality
import com.surenjanath.crownfoundry.engine.PuzzleSeed
import com.surenjanath.crownfoundry.engine.ScoredMove
import com.surenjanath.crownfoundry.engine.VariantRules
import com.surenjanath.crownfoundry.offline.Puzzle
import com.surenjanath.crownfoundry.offline.PuzzleStore
import com.surenjanath.crownfoundry.ui.components.board.TapResult
import com.surenjanath.crownfoundry.ui.screens.puzzles.PuzzleSession
import com.surenjanath.crownfoundry.ui.screens.puzzles.PuzzleVerdict
import com.surenjanath.crownfoundry.ui.screens.puzzles.puzzleSubtitle
import com.surenjanath.crownfoundry.ui.screens.puzzles.verdictLine
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
 * The practice loop: a mistake becomes a stored puzzle, a stored puzzle becomes a position with
 * one right answer, and answering it is recorded once.
 *
 * The engine decides which positions qualify - that is proven in `:engine`. What matters here is
 * that a puzzle survives the round trip to disk with an answer that is still legal, and that the
 * scoring cannot be gamed by tapping around or by being shown the answer.
 */
class PuzzleTest {

    private lateinit var directory: File
    private lateinit var store: PuzzleStore

    /** The opening position: Black to move, seven legal moves, none of them captures. */
    private val openingFen = Board.initial().toFen()

    @Before
    fun setUp() {
        directory = File.createTempFile("crownfoundry", "puzzles").let {
            it.delete()
            it.mkdirs()
            it
        }
        store = PuzzleStore(File(directory, "puzzles.json"))
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun seed(
        best: String = "11-15",
        played: String = "9-13",
        fen: String = openingFen,
        loss: Float = 2.8f
    ) = PuzzleSeed(
        fen = fen,
        rules = VariantRules.DEFAULT,
        best = best,
        played = played,
        loss = loss,
        quality = MoveQuality.Blunder,
        ply = 7,
        alternatives = listOf(ScoredMove(best, 1.0f), ScoredMove(played, 1.0f - loss))
    )

    // --- the store --------------------------------------------------------------------------

    @Test
    fun `collecting a game's mistakes stores them`() = runTest {
        val added = store.collect(listOf(seed(), seed(best = "12-16")), matchId = "match-1")

        assertEquals(2, added)
        assertEquals(2, store.all().size)
        assertEquals("match-1", store.all().first().matchId)
    }

    @Test
    fun `reviewing the same game twice does not double the list`() = runTest {
        store.collect(listOf(seed()), matchId = "match-1")
        val again = store.collect(listOf(seed()), matchId = "match-1")

        assertEquals(0, again)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `re-collecting does not undo progress`() = runTest {
        store.collect(listOf(seed()), matchId = "match-1")
        val id = store.all().first().id
        store.record(id, solved = true)

        store.collect(listOf(seed()), matchId = "match-1")

        assertTrue(store.find(id)!!.solved)
    }

    @Test
    fun `an attempt is recorded, and solved sticks`() = runTest {
        store.collect(listOf(seed()), matchId = "match-1")
        val id = store.all().first().id

        store.record(id, solved = false)
        assertFalse(store.find(id)!!.solved)
        assertEquals(1, store.find(id)!!.attempts)

        store.record(id, solved = true)
        assertTrue(store.find(id)!!.solved)
        assertEquals(2, store.find(id)!!.attempts)

        // A later wrong answer does not take a solve away.
        store.record(id, solved = false)
        assertTrue(store.find(id)!!.solved)
    }

    @Test
    fun `puzzles survive a restart`() = runTest {
        store.collect(listOf(seed()), matchId = "match-1")
        val id = store.all().first().id
        store.record(id, solved = true)

        val reopened = PuzzleStore(File(directory, "puzzles.json"))

        assertEquals(1, reopened.all().size)
        assertTrue(reopened.find(id)!!.solved)
    }

    @Test
    fun `unsolved puzzles outlive solved ones when the list fills`() = runTest {
        // Sixty-one distinct positions, so the store has to drop one.
        val seeds = (1..61).map { seed(fen = "$openingFen#$it") }
        store.collect(seeds, matchId = "match-1")

        val ids = store.all().map { it.id }
        store.record(ids.first(), solved = true)

        // One more arrival forces the trim, and the solved one is the expendable one.
        store.collect(listOf(seed(fen = "$openingFen#new")), matchId = "match-2")

        assertEquals(60, store.all().size)
        assertNull(store.find(ids.first()))
    }

    @Test
    fun `a corrupt file reads as no puzzles rather than a crash`() = runTest {
        File(directory, "puzzles.json").writeText("{ this is not json")

        assertTrue(PuzzleStore(File(directory, "puzzles.json")).all().isEmpty())
    }

    // --- one puzzle, being attempted ------------------------------------------------------------

    private fun puzzle(best: String = "11-15", fen: String = openingFen) = Puzzle(
        id = Puzzle.idOf(fen, best),
        fen = fen,
        rules = MatchRulesDto(),
        best = best,
        played = "9-13",
        loss = 2.8f,
        quality = "Blunder",
        ply = 7
    )

    @Test
    fun `a puzzle sets up on its own position`() {
        val session = PuzzleSession.of(puzzle())!!

        assertEquals(24, session.pieces.size)
        assertEquals(7, session.legalMoves.size)
        assertEquals(Side.BLACK, session.sideToMove)
        assertFalse(session.mustCapture)
        assertTrue(session.acceptsTaps)
    }

    @Test
    fun `a puzzle whose answer is not legal is refused rather than shown`() {
        // 23-19 is White's move, and it is Black to play here.
        assertNull(PuzzleSession.of(puzzle(best = "23-19")))
        assertNull(PuzzleSession.of(puzzle(fen = "not a position")))
    }

    @Test
    fun `playing the engine's move solves it`() {
        val session = PuzzleSession.of(puzzle())!!

        assertTrue(session.tap(11) is TapResult.Selected)
        val ready = session.tap(15)

        assertEquals(TapResult.Ready("11-15"), ready)
        assertTrue(session.verdict.isCorrect)
        assertEquals(0, session.misses)
        assertFalse(session.acceptsTaps)
        assertEquals(listOf(11, 15), session.answerSquares)
    }

    @Test
    fun `any other move is wrong, and can be tried again`() {
        val session = PuzzleSession.of(puzzle())!!

        session.tap(9)
        session.tap(13)

        assertTrue(session.verdict is PuzzleVerdict.Wrong)
        assertEquals(1, session.misses)
        assertNotNull(verdictLine(session))

        session.retry()
        assertEquals(PuzzleVerdict.Unanswered, session.verdict)
        assertTrue(session.acceptsTaps)

        session.tap(11)
        session.tap(15)
        assertTrue(session.verdict.isCorrect)
        // The miss is not erased by getting it right afterwards.
        assertEquals(1, session.misses)
    }

    @Test
    fun `being shown the answer is not solving it`() {
        val session = PuzzleSession.of(puzzle())!!

        session.reveal()

        assertEquals(PuzzleVerdict.Revealed, session.verdict)
        assertFalse(session.verdict.isCorrect)
        assertTrue(session.verdict.isFinished)
        // And it cannot be un-revealed into a solve.
        session.retry()
        assertEquals(PuzzleVerdict.Revealed, session.verdict)
    }

    @Test
    fun `a solved puzzle cannot be revealed after the fact`() {
        val session = PuzzleSession.of(puzzle())!!

        session.answer("11-15")
        session.reveal()

        assertTrue(session.verdict.isCorrect)
    }

    @Test
    fun `taps are ignored once the puzzle is over`() {
        val session = PuzzleSession.of(puzzle())!!
        session.answer("11-15")

        assertEquals(TapResult.Ignored, session.tap(9))
        assertNull(session.selection)
    }

    // --- wording ------------------------------------------------------------------------------

    @Test
    fun `the list says where a puzzle stands`() {
        val fresh = puzzle()
        assertTrue(puzzleSubtitle(fresh).contains("move 7"))

        assertTrue(puzzleSubtitle(fresh.copy(solved = true, attempts = 1))
            .contains("solved first time"))

        assertTrue(puzzleSubtitle(fresh.copy(solved = true, attempts = 3)).endsWith("solved"))

        assertTrue(puzzleSubtitle(fresh.copy(attempts = 2)).contains("2 tries"))
        assertTrue(puzzleSubtitle(fresh.copy(attempts = 1)).contains("1 try"))
    }
}
