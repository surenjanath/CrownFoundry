package com.surenjanath.crownfoundry.engine

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credit assignment and the optimiser.
 *
 * The reward tables are pinned against `ai.agent.build_transitions` for one concrete game, played
 * out by the engine and printed from the backend. Getting these wrong is the failure mode that
 * looks like nothing at all: the device would keep training happily and keep teaching itself the
 * opposite of what the server teaches.
 */
class LearningTest {

    /** The line the backend's reference numbers were generated from. */
    private val moves = listOf(
        "11-16", "24-20", "8-11", "28-24", "9-14", "23-18", "14x23",
        "26x19", "16x23", "27x18", "10-15", "31-27", "4-8", "21-17"
    )

    private val plies by lazy { replayMoves(moves) }

    private fun rewardsOf(winner: Int?, side: Int) =
        buildTransitions(plies, winner, side).map { round4(it.reward) }

    private fun returnsOf(winner: Int?, side: Int) =
        buildTransitions(plies, winner, side).map { it.monteCarloReturn }

    private fun round4(value: Float) = Math.round(value * 10000f) / 10000f

    /**
     * Returns accumulate over the whole game, so the float32 arithmetic here diverges from the
     * backend's float64 in the fourth decimal. Rewards are exact and are compared as such; these
     * are compared on a tolerance that is still far tighter than anything learning reacts to.
     */
    private fun assertReturns(expected: List<Float>, actual: List<Float>) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("return $i of $actual", expected[i], actual[i], 1e-3f)
        }
    }

    @Test
    fun `the line replays cleanly`() {
        assertEquals(moves.size, plies.size)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0), plies.map { it.move.captures.size })
        assertTrue(plies.none { it.move.crowned })
    }

    @Test
    fun `white's rewards and returns match the backend`() {
        assertEquals(listOf(0f, 0f, -2f, 0f, 2f, 0f, 10f), rewardsOf(WHITE, WHITE))
        assertReturns(listOf(7.1749f, 7.5526f, 7.9501f, 10.4737f, 11.025f, 9.5f, 10f), returnsOf(WHITE, WHITE))
    }

    @Test
    fun `a loss smears an extra penalty over the closing moves`() {
        // The discounted return already carries the -10 backwards; the tail penalty is what makes
        // the agent stop walking into the same losing line, so its exact shape matters.
        assertEquals(
            listOf(0f, -0.3333f, -2.6667f, -1f, 0.6667f, -1.6667f, -12f),
            rewardsOf(BLACK, WHITE)
        )
        assertReturns(listOf(-13.1484f, -13.8405f, -14.218f, -12.1593f, -11.7467f, -13.0667f, -12f), returnsOf(BLACK, WHITE))
    }

    @Test
    fun `an unfinished game carries only the material rewards`() {
        assertEquals(listOf(0f, 0f, -2f, 0f, 2f, 0f, 0f), rewardsOf(null, WHITE))
        assertReturns(listOf(-0.176f, -0.1853f, -0.195f, 1.9f, 2f, 0f, 0f), returnsOf(null, WHITE))
    }

    @Test
    fun `black's side of the same game matches the backend`() {
        assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0f, 10f), rewardsOf(BLACK, BLACK))
        assertReturns(listOf(7.3509f, 7.7378f, 8.1451f, 8.5738f, 9.025f, 9.5f, 10f), returnsOf(BLACK, BLACK))
        assertReturns(listOf(-12.9725f, -13.6552f, -14.023f, -14.0593f, -13.7467f, -13.0667f, -12f), returnsOf(WHITE, BLACK))
    }

    @Test
    fun `a draw earns neither side anything beyond the material`() {
        assertReturns(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f), returnsOf(DRAW_RESULT, BLACK))
    }

    @Test
    fun `transitions carry the position they came from`() {
        val transitions = buildTransitions(plies, WHITE, WHITE)

        assertEquals(FEATURE_SIZE, transitions[0].action.size)
        assertEquals("24-20", transitions[0].notation)
        // Every transition but the last bootstraps off the next one.
        assertNotNull(transitions[0].nextState)
        assertNull(transitions.last().nextState)
        assertTrue(transitions.last().done)
    }

    @Test
    fun `a side that never moved has nothing to learn`() {
        assertTrue(buildTransitions(emptyList(), WHITE, WHITE).isEmpty())
    }

    // --- the optimiser -----------------------------------------------------------------------

    @Test
    fun `training drives the loss down`() {
        val net = QNetwork(intArrayOf(FEATURE_SIZE, 32, 1)).apply { randomise(seed = 5) }
        val random = Random(3)
        val inputs = List(24) { FloatArray(FEATURE_SIZE) { random.nextFloat() - 0.5f } }
        val targets = FloatArray(24) { random.nextFloat() * 4f - 2f }

        val first = net.trainBatch(inputs, targets)
        var last = first
        repeat(200) { last = net.trainBatch(inputs, targets) }

        assertTrue("loss went $first -> $last", last < first * 0.5f)
    }

    @Test
    fun `gradients match finite differences`() {
        // The one check that says the backward pass really is the derivative of the forward pass,
        // rather than something that merely makes the loss go down. A transposed weight matrix
        // passes every other test in this file.
        val net = QNetwork(intArrayOf(6, 5, 4, 1), huberDelta = 0f, gradClip = 0f)
            .apply { randomise(seed = 21) }
        val random = Random(8)
        val inputs = List(4) { FloatArray(6) { random.nextFloat() + 0.25f } }
        val targets = FloatArray(4) { random.nextFloat() }

        val gradients = net.lossAndGradients(inputs, targets)
        val epsilon = 1e-3f

        for (layer in 0 until net.nLayers) {
            for (index in net.weights[layer].indices step 7) {
                val original = net.weights[layer][index]

                net.weights[layer][index] = original + epsilon
                val up = net.loss(inputs, targets)
                net.weights[layer][index] = original - epsilon
                val down = net.loss(inputs, targets)
                net.weights[layer][index] = original

                val numeric = (up - down) / (2 * epsilon)
                val analytic = gradients.weights[layer][index]
                // Float32 finite differences are coarse; compare on a relative scale.
                val tolerance = 2e-3f + 0.02f * abs(analytic)
                assertEquals(
                    "layer $layer weight $index: numeric $numeric vs analytic $analytic",
                    numeric, analytic, tolerance
                )
            }
        }
    }

    @Test
    fun `bias gradients match finite differences too`() {
        val net = QNetwork(intArrayOf(6, 5, 1), huberDelta = 0f, gradClip = 0f)
            .apply { randomise(seed = 33) }
        val random = Random(9)
        val inputs = List(5) { FloatArray(6) { random.nextFloat() + 0.25f } }
        val targets = FloatArray(5) { random.nextFloat() * 2f }

        val gradients = net.lossAndGradients(inputs, targets)
        val epsilon = 1e-3f

        for (layer in 0 until net.nLayers) {
            for (index in net.biases[layer].indices) {
                val original = net.biases[layer][index]

                net.biases[layer][index] = original + epsilon
                val up = net.loss(inputs, targets)
                net.biases[layer][index] = original - epsilon
                val down = net.loss(inputs, targets)
                net.biases[layer][index] = original

                val numeric = (up - down) / (2 * epsilon)
                val analytic = gradients.biases[layer][index]
                assertEquals(
                    "layer $layer bias $index",
                    numeric, analytic, 2e-3f + 0.02f * abs(analytic)
                )
            }
        }
    }

    @Test
    fun `the huber loss keeps a wild target from dominating the batch`() {
        val squared = QNetwork(intArrayOf(4, 3, 1), huberDelta = 0f, gradClip = 0f)
            .apply { randomise(seed = 12) }
        val huber = QNetwork(intArrayOf(4, 3, 1), huberDelta = 1f, gradClip = 0f)
            .apply { randomise(seed = 12) }

        val inputs = List(3) { i -> FloatArray(4) { (i + 1).toFloat() } }
        val targets = floatArrayOf(0f, 0f, 50f)

        val squaredNorm = squared.lossAndGradients(inputs, targets).weights[0].sumOf { abs(it).toDouble() }
        val huberNorm = huber.lossAndGradients(inputs, targets).weights[0].sumOf { abs(it).toDouble() }

        assertTrue("huber $huberNorm should be far below squared $squaredNorm", huberNorm < squaredNorm)
    }

    @Test
    fun `learning from a match moves the values toward the outcome`() {
        val net = QNetwork(intArrayOf(FEATURE_SIZE, 64, 1)).apply { randomise(seed = 2) }
        val learner = OfflineLearner(net, random = Random(4))

        val winningAfterstate = encode(plies[plies.size - 1].after, WHITE)
        val before = net.predict(winningAfterstate)

        val report = learner.learnFromMatch(plies, winner = WHITE, aiSide = WHITE, epochs = 12)

        assertEquals(7, report.transitions)
        assertTrue(report.replaySize >= 7)
        assertTrue(
            "value went $before -> ${net.predict(winningAfterstate)} for a won game",
            net.predict(winningAfterstate) > before
        )
    }

    @Test
    fun `learning from a loss pushes the other way`() {
        val net = QNetwork(intArrayOf(FEATURE_SIZE, 64, 1)).apply { randomise(seed = 2) }
        val learner = OfflineLearner(net, random = Random(4))

        val losingAfterstate = encode(plies[plies.size - 1].after, WHITE)
        val before = net.predict(losingAfterstate)

        learner.learnFromMatch(plies, winner = BLACK, aiSide = WHITE, epochs = 12)

        assertTrue(
            "value went $before -> ${net.predict(losingAfterstate)} for a lost game",
            net.predict(losingAfterstate) < before
        )
    }

    // --- replay ------------------------------------------------------------------------------

    @Test
    fun `the replay buffer evicts oldest first`() {
        val buffer = ReplayBuffer(capacity = 5)
        repeat(12) { i ->
            buffer.push(
                Transition(FloatArray(FEATURE_SIZE), i.toFloat(), null, true, i.toFloat())
            )
        }
        assertEquals(5, buffer.size)
        assertEquals(7f, buffer.sample(5, prioritized = false).minOf { it.reward }, 0f)
    }

    @Test
    fun `the replay buffer survives a round trip through bytes`() {
        val buffer = ReplayBuffer(capacity = 50)
        buffer.extend(buildTransitions(plies, WHITE, WHITE))
        val restored = ReplayBuffer.fromBytes(buffer.toBytes(), capacity = 50)

        assertEquals(buffer.size, restored.size)
        val original = buffer.sample(buffer.size, prioritized = false).sortedBy { it.reward }
        val recovered = restored.sample(restored.size, prioritized = false).sortedBy { it.reward }
        for (i in original.indices) {
            assertEquals(original[i].reward, recovered[i].reward, 1e-6f)
            assertTrue(original[i].action.contentEquals(recovered[i].action))
            assertEquals(original[i].done, recovered[i].done)
            assertEquals(original[i].nextState == null, recovered[i].nextState == null)
        }
    }

    @Test
    fun `unreadable replay bytes give an empty buffer rather than a crash`() {
        assertTrue(ReplayBuffer.fromBytes(null).isEmpty())
        assertTrue(ReplayBuffer.fromBytes(ByteArray(3)).isEmpty())
        assertTrue(ReplayBuffer.fromBytes(ByteArray(200) { 7 }).isEmpty())
    }

    @Test
    fun `a move list that stops being legal replays as far as it can`() {
        val partial = replayMoves(listOf("11-15", "23-18", "totally-not-a-move"))
        assertEquals(2, partial.size)
    }
}
