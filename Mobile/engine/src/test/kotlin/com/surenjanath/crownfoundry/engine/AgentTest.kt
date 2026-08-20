package com.surenjanath.crownfoundry.engine

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search and selection.
 *
 * These run against a hand-built evaluator rather than the shipped weights: a single linear layer
 * reading the `material_diff` feature, which makes the network a plain piece counter. That turns
 * "does the search work?" into a question with an arithmetic answer, instead of one that depends
 * on whatever the policy happened to learn last Tuesday.
 */
class AgentTest {

    /** A network whose value is exactly the material difference, in pieces, kings worth 1.6. */
    private fun materialNetwork() = QNetwork(intArrayOf(FEATURE_SIZE, 1)).also {
        // Feature 129 is `material_diff`, already divided by 12 by the encoder.
        it.weights[0][MATERIAL_DIFF_INDEX] = 12f
    }

    private fun agent(
        depth: Int,
        risk: Float = 1f,
        epsilon: Float = 0f,
        memory: MistakeMemory = MistakeMemory.NONE,
        random: Random = Random(1)
    ) = LocalAgent(
        net = materialNetwork(),
        knobs = Knobs(depth = depth, epsilon = epsilon, risk = risk, topK = 5),
        memory = memory,
        random = random
    )

    @Test
    fun `the material network reads the position it is given`() {
        val net = materialNetwork()
        // White is a piece up here: four men against three.
        assertEquals(1f, net.predict(encode(Board.fromFen("W:W11,22,30,32:B9,15,17"), WHITE)), 1e-4f)
        assertEquals(-1f, net.predict(encode(Board.fromFen("W:W11,22,30,32:B9,15,17"), BLACK)), 1e-4f)
        assertEquals(0f, net.predict(encode(Board.initial(), BLACK)), 1e-4f)
    }

    @Test
    fun `it takes the jump that wins the most material`() {
        // White may play 11x18 for one piece or 22x13x6 for two. Both concede a recapture, so the
        // double jump is right at every depth - and a depth-1 agent that only counted its own
        // capture would still get this one, which is why the next test exists.
        val board = Board.fromFen("W:W11,22,30,32:B9,15,17")
        val (move, considered) = agent(depth = 2).select(board)

        assertEquals("22x13x6", move.notation())
        assertEquals("22x13x6", considered.first().notation)
        assertTrue(considered.first().q > considered.last().q)
    }

    @Test
    fun `quiescence stops it from hanging a piece`() {
        // Every move here leaves White a piece down, but 27-24 walks into a jump on top of that.
        // The quiescence extension is what sees the recapture; without it all four moves tie.
        val board = Board.fromFen("W:W10,27:B8,12,20")
        val scored = agent(depth = 1).scoreMoves(board)
        val values = scored.associate { it.move.notation() to it.value }

        assertEquals(setOf("27-23", "27-24", "10-6", "10-7"), values.keys)
        assertTrue(
            "27-24 scored ${values["27-24"]}, best was ${scored.first().value}",
            values.getValue("27-24") < scored.first().value
        )
        assertNotEquals("27-24", scored.first().move.notation())
    }

    @Test
    fun `it finds a win that is one move away`() {
        // Black has a single man on 15; White's jump takes it and ends the game.
        val board = Board.fromFen("W:W19,30:B15")
        val (move, _) = agent(depth = 3).select(board)

        assertEquals("19x10", move.notation())
        assertTrue(board.apply(move).isTerminal())
    }

    @Test
    fun `a decided position is worth the terminal value, sooner being better`() {
        val quick = agent(depth = 4).evaluate(Board.fromFen("W:W19,30:B15"), WHITE)
        assertTrue("a won position should score near +$TERMINAL_VALUE, got $quick", quick > 9f)

        // White to move with nothing left on the board: decided, and decided against it.
        val lost = agent(depth = 4).evaluate(Board.fromFen("W:W:B15"), WHITE)
        assertTrue("an annihilated position should score near -$TERMINAL_VALUE, got $lost", lost < -9f)
    }

    @Test
    fun `selection is deterministic when exploration is off`() {
        val board = Board.initial()
        val first = agent(depth = 2, random = Random(1)).select(board).first
        val second = agent(depth = 2, random = Random(999)).select(board).first
        assertEquals(first.notation(), second.notation())
    }

    @Test
    fun `exploration only ever fires when it is asked for`() {
        val board = Board.initial()

        // explore = false is the path a replayed or resumed turn takes: no dice, ever.
        val quiet = agent(depth = 1, epsilon = 1f, random = Random(5))
        quiet.select(board, explore = false)
        assertFalse(quiet.lastExplored)

        var explored = 0
        repeat(20) { seed ->
            val noisy = agent(depth = 1, epsilon = 1f, random = Random(seed.toLong()))
            noisy.select(board, explore = true)
            if (noisy.lastExplored) explored++
        }
        assertTrue("exploration fired $explored times in 20 tries at epsilon 1.0", explored > 10)
    }

    @Test
    fun `a known mistake is pushed down the list`() {
        val board = Board.fromFen("W:W11,22,30,32:B9,15,17")
        val clean = agent(depth = 2).scoreMoves(board)
        val best = clean.first().move.notation()

        val punished = agent(
            depth = 2,
            memory = { fen -> if (fen == board.toFen()) setOf(best) else emptySet() }
        ).scoreMoves(board)

        val penalised = punished.first { it.move.notation() == best }
        assertTrue(penalised.repeatMistake)
        assertEquals(
            clean.first().value - MISTAKE_PENALTY, penalised.value, 1e-4f
        )
    }

    @Test
    fun `a memory that throws does not cost the player their turn`() {
        val board = Board.initial()
        val brittle = agent(depth = 1, memory = { error("the local store is on fire") })
        assertEquals(7, brittle.scoreMoves(board).size)
    }

    @Test
    fun `there is nothing to select in a finished position`() {
        val over = Board.fromFen("B:W5,6,10:B1")
        assertTrue(over.legalMoves().isEmpty())
        assertThrows(IllegalMove::class.java) { agent(depth = 1).select(over) }
    }

    @Test
    fun `the shortlist is capped and always contains what was played`() {
        val board = Board.initial()
        val small = LocalAgent(materialNetwork(), Knobs(2, 0f, 0.6f, topK = 3))
        val (move, considered) = small.select(board)

        assertTrue(considered.size <= 4)
        assertTrue(considered.any { it.notation == move.notation() })
    }

    @Test
    fun `confidence rises with the gap to the runner-up`() {
        assertEquals(1f, confidenceOf(listOf(ScoredMove("11-15", 1f))), 1e-6f)

        val tied = confidenceOf(listOf(ScoredMove("a", 1f), ScoredMove("b", 1f)))
        val clear = confidenceOf(listOf(ScoredMove("a", 4f), ScoredMove("b", 1f)))
        assertEquals(0.5f, tied, 1e-4f)
        assertTrue("a three-point gap should read as confident, got $clear", clear > 0.99f)
    }

    // --- difficulty --------------------------------------------------------------------------

    @Test
    fun `difficulties map onto the settings the backend uses`() {
        assertEquals(Knobs(1, 0.35f, 0.2f, 3, DEFAULT_NODE_BUDGET), knobsFor("easy"))
        assertEquals(Knobs(2, 0.10f, 0.5f, 4, DEFAULT_NODE_BUDGET), knobsFor("normal"))
        assertEquals(Knobs(6, 0f, 0.7f, 5, DEFAULT_NODE_BUDGET), knobsFor("hard"))
        // The device searches the tree the server searches - same depth, same budget - so an
        // offline opponent is not a weaker one. `SearchBudgetTest` is why that is affordable.
        assertEquals(4000, DEFAULT_NODE_BUDGET)
        assertEquals(knobsFor("adaptive"), knobsFor(null))
        assertEquals(knobsFor("adaptive"), knobsFor("  ADAPTIVE "))
    }

    @Test
    fun `adaptive holds steady until it has seen enough games`() {
        val fresh = knobsFor("adaptive", OpponentProfile(totalGames = 2, winRate = 0.9f))
        assertEquals(knobsFor("adaptive"), fresh)
    }

    @Test
    fun `adaptive digs in against an opponent who is winning`() {
        val base = knobsFor("adaptive")
        val losing = knobsFor("adaptive", OpponentProfile(totalGames = 10, winRate = 0.8f))

        assertTrue("depth ${losing.depth} should exceed ${base.depth}", losing.depth > base.depth)
        assertEquals(0f, losing.epsilon, 0f)
        assertEquals(0f, base.epsilon, 0f)
    }

    @Test
    fun `adaptive stops offering material to an aggressive opponent`() {
        val calm = knobsFor("adaptive", OpponentProfile(totalGames = 10, winRate = 0.5f))
        val sharp = knobsFor(
            "adaptive",
            OpponentProfile(totalGames = 10, winRate = 0.5f, styleAggression = 1f, styleKingRush = 1f)
        )
        assertTrue("risk ${sharp.risk} should sit below ${calm.risk}", sharp.risk < calm.risk)
        assertTrue(sharp.risk >= 0.1f)
    }

    // --- the whole thing ---------------------------------------------------------------------

    @Test
    fun `two agents play a complete game without falling over`() {
        // The smoke test that matters: every position the search reaches is one the generator
        // produced, so a rules bug and a search bug both surface here as an exception or a game
        // that never ends.
        var board = Board.initial()
        val black = agent(depth = 2, random = Random(4))
        val white = agent(depth = 2, random = Random(5))
        val played = ArrayList<String>()

        repeat(300) {
            if (board.isTerminal()) return@repeat
            val mover = if (board.sideToMove == BLACK) black else white
            val (move, considered) = mover.select(board, explore = true)
            assertTrue(considered.isNotEmpty())
            assertTrue(board.legalMoves().any { it.notation() == move.notation() })
            played.add(move.notation())
            board = board.apply(move)
        }

        assertTrue("the game produced no moves", played.size > 10)
        // Whatever happened, the move list has to replay cleanly - that is exactly what the
        // server will do with it on sync.
        assertEquals(played.size, replayMoves(played).size)
    }

    companion object {
        /** `material_diff` sits immediately after `to_move_is_self` in the engineered block. */
        private const val MATERIAL_DIFF_INDEX = N_PLANES * PLANE_SIZE + 1
    }
}
