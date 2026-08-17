package com.surenjanath.crownfoundry.engine

import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a turn takes, against the real architecture.
 *
 * Offline mode lives or dies on this. A shipped policy is 148-128-64-1, and every leaf of the
 * search is a forward pass through it; get the node budget wrong and the opponent's turn reads as
 * the app having frozen. The bounds below are deliberately loose - a CI machine is not a phone,
 * and this is here to catch an order-of-magnitude regression, not to police milliseconds.
 *
 * The measured numbers are printed so a change in the search can be judged rather than guessed at.
 */
class SearchBudgetTest {

    private fun shippedNetwork() =
        QNetwork(intArrayOf(FEATURE_SIZE, 128, 64, 1)).apply { randomise(seed = 3) }

    @Test
    fun `a turn at the shipping defaults finishes fast enough to feel instant`() {
        val net = shippedNetwork()
        val agent = LocalAgent(net, knobsFor("hard"))

        // Warm the JIT: the first search in a process is not the one the player waits on.
        repeat(3) { agent.select(Board.initial()) }

        var board = Board.initial()
        var worst = 0L
        var total = 0L
        var turns = 0

        val random = Random(11)
        repeat(30) {
            if (board.isTerminal()) return@repeat
            val started = System.nanoTime()
            val (move, _) = agent.select(board, explore = false)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            worst = maxOf(worst, elapsed)
            total += elapsed
            turns++
            // Play a random legal reply so the search sees a genuine spread of positions rather
            // than one deterministic line.
            board = board.apply(move)
            if (board.isTerminal()) return@repeat
            val replies = board.legalMoves()
            board = board.apply(replies[random.nextInt(replies.size)])
        }

        println("search: $turns turns, mean ${total / maxOf(turns, 1)}ms, worst ${worst}ms")
        assertTrue("no turns were measured", turns >= 10)
        assertTrue("worst turn took ${worst}ms", worst < 3_000)
    }

    @Test
    fun `training on a finished game is quick enough to run at the game-over dialog`() {
        val net = shippedNetwork()
        val moves = listOf(
            "11-16", "24-20", "8-11", "28-24", "9-14", "23-18", "14x23",
            "26x19", "16x23", "27x18", "10-15", "31-27", "4-8", "21-17"
        )
        val plies = replayMoves(moves)
        val learner = OfflineLearner(net, ReplayBuffer(capacity = 500), random = Random(2))

        // Warm-up, then the measurement.
        learner.learnFromMatch(plies, WHITE, WHITE, epochs = 3)

        val started = System.nanoTime()
        val report = learner.learnFromMatch(plies, WHITE, WHITE, epochs = 3)
        val elapsed = (System.nanoTime() - started) / 1_000_000

        println("post-match training: ${report.transitions} transitions in ${elapsed}ms")
        assertTrue("training took ${elapsed}ms", elapsed < 2_000)
    }

    @Test
    fun `a single evaluation is cheap enough for the search to spend thousands of them`() {
        val net = shippedNetwork()
        val vector = encode(Board.initial(), BLACK)

        repeat(2_000) { net.predict(vector) }

        val started = System.nanoTime()
        val iterations = 20_000
        repeat(iterations) { net.predict(vector) }
        val perCall = (System.nanoTime() - started) / iterations

        println("forward pass: ${perCall / 1000.0}µs per call")
        assertTrue("a forward pass took ${perCall}ns", perCall < 500_000)
    }
}
