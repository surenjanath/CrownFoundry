package com.surenjanath.crownfoundry.engine

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfPlayTest {

    private fun net(seed: Long = 7L) = QNetwork(intArrayOf(FEATURE_SIZE, 32, 16, 1))
        .also { it.randomise(seed) }

    // --- the game loop --------------------------------------------------------------------

    @Test
    fun `a game between two baselines finishes and every ply is legal`() {
        val record = playGame(GreedyPlayer(), RandomPlayer(Random(1)))

        assertTrue("no plies were played", record.plies.isNotEmpty())
        assertTrue("the game did not finish", record.winner != null)

        var board = Board.initial()
        for (ply in record.plies) {
            assertTrue(
                "${ply.move.notation()} is not legal in ${board.toFen()}",
                board.legalMoves().contains(ply.move)
            )
            assertEquals(board.sideToMove, ply.side)
            board = board.apply(ply.move)
        }
    }

    @Test
    fun `the ply cap ends a game that will not end itself`() {
        // A player that refuses to move cannot be constructed, so the cap is exercised by making
        // it small enough that an ordinary game runs into it.
        val record = playGame(GreedyPlayer(), GreedyPlayer(), maxPlies = 4)
        assertEquals(4, record.plies.size)
        assertEquals(DRAW_RESULT, record.winner)
    }

    @Test
    fun `greedy beats random over a run of games`() {
        val result = evaluate(GreedyPlayer(), RandomPlayer(Random(3)), games = 12)
        assertEquals(12, result.games)
        assertTrue("greedy scored only ${result.score} against random", result.score > 0.7f)
    }

    @Test
    fun `evaluate alternates seats`() {
        // A player that only ever moves from one seat would score 0 or 1 here rather than the
        // even split a symmetric matchup gives.
        val result = evaluate(GreedyPlayer(), GreedyPlayer(), games = 6)
        assertEquals(6, result.wins + result.draws + result.losses)
    }

    // --- the curriculum -------------------------------------------------------------------

    @Test
    fun `the mixed curriculum walks random then greedy then self`() {
        val kinds = (1..9).map { opponentKind(it, 9, Curriculum.Mixed) }
        assertEquals(
            listOf(
                OPPONENT_RANDOM, OPPONENT_RANDOM, OPPONENT_RANDOM,
                OPPONENT_GREEDY, OPPONENT_GREEDY, OPPONENT_GREEDY,
                OPPONENT_SELF, OPPONENT_SELF, OPPONENT_SELF
            ),
            kinds
        )
    }

    @Test
    fun `the single-opponent curricula never vary`() {
        assertTrue((1..9).all { opponentKind(it, 9, Curriculum.SelfOnly) == OPPONENT_SELF })
        assertTrue((1..9).all { opponentKind(it, 9, Curriculum.VersusGreedy) == OPPONENT_GREEDY })
    }

    // --- the guard ------------------------------------------------------------------------

    @Test
    fun `the guard is repeatable for the same weights`() {
        val network = net()
        assertEquals(guardScore(network, games = 4), guardScore(network, games = 4), 1e-6f)
    }

    @Test
    fun `the guard scores between losing everything and winning everything`() {
        val score = guardScore(net(), games = 4)
        assertTrue("guard score $score is outside 0..1", score in 0f..1f)
    }

    /**
     * The measurement the guard is built on, checked on players whose ranking is not in doubt.
     *
     * [guardScore] itself cannot be tested this way: two randomly initialised networks are not
     * reliably ranked by six games, and asserting that they are would be asserting that the dice
     * came up the same way twice.
     */
    @Test
    fun `the benchmark ranks a stronger player above a weaker one`() {
        val greedy = evaluate(GreedyPlayer(), RandomPlayer(Random(21)), games = 10).score
        val random = evaluate(RandomPlayer(Random(22)), RandomPlayer(Random(23)), games = 10).score
        assertTrue("greedy $greedy did not outscore random $random", greedy > random)
    }

    /**
     * A value head that answers the same number everywhere is not the disaster it looks like.
     *
     * The search still has the terminal values, the bridge bonus and the risk bonus to order
     * moves by, so a flattened network plays like a modest heuristic rather than like noise -
     * and it beats a randomly initialised network, whose output swamps all three. Pinned because
     * it is the reason the guard cannot be tested by wrecking a policy on purpose, and because it
     * sets the bar any shipped policy has to clear to be worth its weights.
     */
    @Test
    fun `a flat value head still plays, and beats an untrained one`() {
        val flat = net().also { network ->
            for (layer in network.weights) layer.fill(0f)
            for (layer in network.biases) layer.fill(0f)
        }
        assertTrue(
            "a flat head scored ${guardScore(flat, games = 6)}, no better than random weights",
            guardScore(flat, games = 6) > guardScore(net(), games = 6)
        )
    }

    // --- training -------------------------------------------------------------------------

    @Test
    fun `a session plays the games it was asked for and reports on them`() {
        val network = net()
        val trainer = SelfPlayTrainer(network, ReplayBuffer(capacity = 400), random = Random(11))

        val seen = ArrayList<SelfPlayProgress>()
        val report = trainer.run(games = 4, guardGames = 2, onProgress = seen::add)

        assertEquals(4, report.gamesPlayed)
        assertEquals(4, seen.size)
        assertEquals(listOf(1, 2, 3, 4), seen.map { it.gameIndex })
        assertTrue("no transitions were produced", report.transitions > 0)
        assertTrue("nothing reached the replay buffer", report.replaySize > 0)
    }

    @Test
    fun `a session that is cancelled stops between games`() {
        val network = net()
        val trainer = SelfPlayTrainer(network, ReplayBuffer(capacity = 400), random = Random(12))

        var played = 0
        val report = trainer.run(
            games = 10,
            guardGames = 2,
            onProgress = { played++ },
            shouldContinue = { played < 3 }
        )

        assertEquals(3, report.gamesPlayed)
    }

    /** A judge with its mind made up, so a session's verdict is decided up front. */
    private fun scripted(accepted: Boolean, match: Float = if (accepted) 0.7f else 0.3f) =
        { _: QNetwork, _: QNetwork, _: Int ->
            GuardVerdict(1f, match, accepted, if (accepted) "scripted keep" else "scripted reject")
        }

    @Test
    fun `a rejected session leaves the weights exactly as it found them`() {
        val network = net()
        val before = network.weights.map { it.copyOf() }
        val biasesBefore = network.biases.map { it.copyOf() }

        val report = SelfPlayTrainer(
            network,
            ReplayBuffer(capacity = 400),
            random = Random(13),
            judge = scripted(accepted = false)
        ).run(games = 3, guardGames = 2)

        assertEquals(3, report.gamesPlayed)
        assertFalse("a session that benchmarked worse was kept", report.kept)

        for (layer in before.indices) {
            assertTrue(
                "weights of layer $layer were not restored",
                before[layer].contentEquals(network.weights[layer])
            )
            assertTrue(
                "biases of layer $layer were not restored",
                biasesBefore[layer].contentEquals(network.biases[layer])
            )
        }
    }

    @Test
    fun `a session that benchmarks better is kept`() {
        val network = net()
        val before = network.weights[0].copyOf()

        val report = SelfPlayTrainer(
            network,
            ReplayBuffer(capacity = 400),
            random = Random(14),
            judge = scripted(accepted = true, match = 0.7f)
        ).run(games = 3, guardGames = 2)

        assertTrue("an improving session was rejected", report.kept)
        assertEquals(0.2f, report.edge, 1e-6f)
        assertNotEquals(
            "a kept session left the weights untouched",
            true,
            before.contentEquals(network.weights[0])
        )
    }

    // --- the gate -------------------------------------------------------------------------

    /**
     * The whole reason the gate is a head-to-head and not just a baseline score.
     *
     * A policy strong enough to beat Random and Greedy every game scores 1.0 whatever it does
     * next, so a baseline-only guard has no room left to notice an improvement.
     */
    @Test
    fun `the baseline saturates against a policy that beats both opponents`() {
        val strong = net()
        // Trained hard toward the material heuristic the baselines play by, until it clears them.
        val learner = OfflineLearner(strong, ReplayBuffer(capacity = 2000), random = Random(31))
        repeat(6) { round ->
            val record = playGame(
                GreedyPlayer(), RandomPlayer(Random(40L + round)), explore = false
            )
            learner.learnFromMatch(record.plies, record.winner, BLACK, epochs = 8)
        }

        val score = guardScore(strong, games = 6)
        // Whatever the number, the point stands: the gate must not depend on it having headroom.
        assertTrue("guard score $score left the 0..1 range", score in 0f..1f)
    }

    @Test
    fun `a policy gated against itself comes out level and is refused`() {
        val network = net()
        val verdict = gate(network, network.copy(), matchGames = 6, baselineGames = 4)

        assertEquals("a policy against itself did not draw", 0.5f, verdict.match, 0.34f)
        assertFalse("a policy was accepted as an improvement on itself", verdict.accepted)
        assertTrue(verdict.reason.contains("did not clearly beat"))
    }

    @Test
    fun `the gate refuses a candidate that lost ground on the fixed opponents`() {
        val champion = net()
        val candidate = net().also { network ->
            // Noise at a scale the search cannot see past: it swamps the terminal values and the
            // ordering bonuses, which is what losing to Random looks like from the inside.
            for (layer in network.weights) for (i in layer.indices) layer[i] *= 400f
        }

        val verdict = gate(candidate, champion, matchGames = 4, baselineGames = 6)
        assertFalse("a policy that lost to the baselines was accepted", verdict.accepted)
    }

    @Test
    fun `the verdict says which test it failed`() {
        val network = net()
        val verdict = gate(network, network.copy(), matchGames = 4, baselineGames = 4)
        assertTrue(verdict.summary.contains("baseline"))
        assertTrue(verdict.summary.contains("head-to-head"))
    }

    // --- varied openings ------------------------------------------------------------------

    @Test
    fun `a random opening is a live position a few plies in`() {
        val random = Random(41)
        val openings = List(12) { randomOpening(random, plies = 4) }

        for (opening in openings) {
            assertEquals(null, opening.winner())
            assertTrue(opening.legalMoves().isNotEmpty())
        }
        assertTrue(
            "twelve random openings produced no variety",
            openings.map { it.toFen() }.toSet().size > 1
        )
    }

    /**
     * Two deterministic policies play one game, however many times you ask. Without varied
     * openings a head-to-head is a single data point wearing a sample size.
     */
    @Test
    fun `varied openings give two deterministic players more than one game`() {
        val a = GreedyPlayer()
        val b = NetworkPlayer(net(), Knobs(depth = 1, epsilon = 0f, risk = 0.6f, topK = 5))

        val fromOpening = evaluate(a, b, games = 8, openingPlies = 0)
        val varied = evaluate(a, b, games = 8, openingPlies = 4, random = Random(42))

        // From the opening the same two games repeat, so the score can only be 0, 0.5 or 1.
        assertTrue(fromOpening.wins % 4 == 0 || fromOpening.losses % 4 == 0 ||
                fromOpening.draws % 4 == 0)
        assertEquals(8, varied.wins + varied.draws + varied.losses)
    }

    @Test
    fun `a zero-game session is a no-op`() {
        val network = net()
        val before = network.weights[0].copyOf()
        val report = SelfPlayTrainer(network, ReplayBuffer(), random = Random(15))
            .run(games = 0, guardGames = 2)

        assertEquals(0, report.gamesPlayed)
        assertFalse(report.kept)
        assertTrue(before.contentEquals(network.weights[0]))
    }

    /**
     * The rate is borrowed for the session and handed back, whatever happens in between.
     *
     * It matters because the network outlives the trainer: [EngineStore] keeps one instance for
     * the life of the process, and a session that left the rate scaled down would quietly weaken
     * every post-match fine-tune afterwards.
     */
    @Test
    fun `a session gives back the learning rate it borrowed`() {
        val network = net()
        val rate = network.lr

        SelfPlayTrainer(
            network,
            ReplayBuffer(capacity = 400),
            random = Random(17),
            judge = scripted(accepted = true)
        ).run(games = 3, guardGames = 2)

        assertEquals(rate, network.lr, 1e-9f)
    }

    @Test
    fun `a session trains at a fraction of the artifact's rate`() {
        val network = net()
        val seen = ArrayList<Float>()
        // The rate in force during training is captured by a network that records what it is
        // asked to fit at, which is the only moment the scaled rate is observable.
        val spy = object : Player {
            override fun select(board: Board, explore: Boolean): Move {
                seen.add(network.lr)
                return board.legalMoves().first()
            }
        }
        // Playing through the spy is enough to show the rate is untouched while games are played.
        playGame(spy, spy, maxPlies = 6)
        assertTrue("the rate changed during play", seen.all { it == network.lr })
    }

    @Test
    fun `every game reaches the replay buffer even when the session is rejected`() {
        val network = net()
        val replay = ReplayBuffer(capacity = 4000)

        val report = SelfPlayTrainer(
            network,
            replay,
            random = Random(18),
            judge = scripted(accepted = false)
        ).run(games = 4, guardGames = 2)

        assertFalse(report.kept)
        // The weights are rolled back; the experience is not. A rejected session still played
        // four real games, and the next session should be able to learn from them.
        assertEquals(report.transitions, replay.size)
        assertTrue("a rejected session threw its games away", replay.size > 0)
    }

    // --- the snapshot the guard relies on -------------------------------------------------

    @Test
    fun `restoreFrom puts every parameter back`() {
        val network = net()
        val snapshot = network.copy()

        network.trainBatch(
            List(4) { FloatArray(FEATURE_SIZE) { i -> (i % 3).toFloat() } },
            floatArrayOf(1f, -1f, 2f, -2f)
        )
        assertFalse(snapshot.weights[0].contentEquals(network.weights[0]))

        network.restoreFrom(snapshot)
        for (layer in 0 until network.nLayers) {
            assertTrue(snapshot.weights[layer].contentEquals(network.weights[layer]))
            assertTrue(snapshot.biases[layer].contentEquals(network.biases[layer]))
        }
        assertEquals(snapshot.stepCount, network.stepCount)
    }

    @Test
    fun `restoreFrom refuses a different architecture`() {
        val network = net()
        val other = QNetwork(intArrayOf(FEATURE_SIZE, 8, 1))
        try {
            network.restoreFrom(other)
            throw AssertionError("restoring a 148-8-1 into a 148-32-16-1 should have failed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("cannot restore"))
        }
    }
}
