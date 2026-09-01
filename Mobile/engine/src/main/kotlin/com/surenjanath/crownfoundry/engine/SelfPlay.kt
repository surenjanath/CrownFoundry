package com.surenjanath.crownfoundry.engine

import kotlin.random.Random

/**
 * Self-play on the device - the part of `Backend/ai/training.py` the phone was missing.
 *
 * Until this existed the on-device policy could only learn from games a human sat through, which
 * is about ten optimiser steps per game against a network with sixteen thousand behind it. A
 * player would have to grind out several hundred matches to move the weights as far as one minute
 * of self-play does, and in the meantime the only games in the buffer came from one opponent - so
 * what little the policy did learn was fitted to a single person's habits.
 *
 * The two pieces that make this safe rather than merely fast are both borrowed from the server:
 *
 * * **a curriculum.** Pure self-play from a mediocre policy converges on a mediocre policy that
 *   beats itself. The first third of a session is played against [RandomPlayer], the second
 *   against [GreedyPlayer] and only the last third against itself, so the weights are anchored to
 *   opponents that do not drift with them.
 * * **a guard.** [guardScore] benchmarks the weights against those same two fixed opponents
 *   before and after, and a session that scores worse is thrown away. Training that can only ever
 *   help is training you can leave running.
 */

/** A played-out game: who won, and every ply in order. */
class GameRecord(@JvmField val winner: Int?, @JvmField val plies: List<Ply>)

/**
 * Play one game to its end.
 *
 * [maxPlies] is a hard stop, not a rule of draughts. The engine's own draw conditions - forty
 * plies without progress, a position seen three times - end almost every game long before it, but
 * a policy early in training can find a shuffle that satisfies neither, and a training session
 * must not be able to hang on one game.
 */
fun playGame(
    black: Player,
    white: Player,
    rules: VariantRules = VariantRules.DEFAULT,
    explore: Boolean = false,
    maxPlies: Int = 240,
    from: Board = Board.initial(rules)
): GameRecord {
    var board = from
    val plies = ArrayList<Ply>(64)

    while (plies.size < maxPlies) {
        val decided = board.winner()
        if (decided != null) return GameRecord(decided, plies)

        val player = if (board.sideToMove == BLACK) black else white
        val move = try {
            player.select(board, explore)
        } catch (failure: IllegalMove) {
            // No legal move is a loss for the side to move, which `winner()` would have caught;
            // anything else here is a policy that proposed nonsense, and the honest answer is an
            // unfinished game rather than a result nobody played for.
            return GameRecord(board.winner(), plies)
        }

        val after = board.apply(move)
        plies.add(Ply(board, move, after, board.sideToMove))
        board = after
    }

    // Out of plies. A game neither side could finish is a draw for training purposes - scoring it
    // as a loss would punish whichever seat happened to be on move.
    return GameRecord(board.winner() ?: DRAW_RESULT, plies)
}

/**
 * A position a few random plies into a game.
 *
 * Two deterministic policies play the same game every time, so a head-to-head between them is one
 * data point however many times it is repeated. Starting each pairing from a different position
 * is what turns that into a measurement. Four plies is enough to reach genuinely distinct
 * middlegames and few enough that neither side is handed a won position to convert.
 *
 * A drawn or decided opening is discarded and re-drawn, so the caller always gets a live game.
 */
fun randomOpening(
    random: Random,
    plies: Int = 4,
    rules: VariantRules = VariantRules.DEFAULT
): Board {
    repeat(8) {
        var board = Board.initial(rules)
        var ok = true
        repeat(plies) {
            val moves = board.legalMoves()
            if (moves.isEmpty()) {
                ok = false
                return@repeat
            }
            board = board.apply(moves[random.nextInt(moves.size)])
        }
        if (ok && board.winner() == null) return board
    }
    return Board.initial(rules)
}

/** How a set of games against one opponent came out. */
data class EvalResult(
    val games: Int,
    val wins: Int,
    val draws: Int,
    val losses: Int,
    val avgPlies: Int
) {
    /** Wins plus half the draws, over the games played. 1.0 is winning everything. */
    val score: Float get() = if (games == 0) 0f else (wins + 0.5f * draws) / games
}

/**
 * Play [games] against [opponent], alternating seats.
 *
 * Alternating matters more in draughts than the symmetry suggests: Black moves first and the
 * opening advantage is real, so a fixed-seat measurement of an even policy comes back lopsided.
 */
fun evaluate(
    agent: Player,
    opponent: Player,
    games: Int,
    rules: VariantRules = VariantRules.DEFAULT,
    maxPlies: Int = 160,
    /**
     * How many random plies each pairing starts from. Zero plays every game from the opening,
     * which is right for a random opponent and useless for two deterministic ones.
     */
    openingPlies: Int = 4,
    random: Random = Random(0)
): EvalResult {
    var wins = 0
    var draws = 0
    var losses = 0
    var plies = 0

    // Each opening is played twice, once from each seat. Black moves first and the opening
    // advantage in draughts is real, so scoring a position from one seat only measures the seat.
    var opening = Board.initial(rules)
    for (i in 0 until games) {
        if (i % 2 == 0) {
            opening = if (openingPlies > 0) randomOpening(random, openingPlies, rules)
            else Board.initial(rules)
        }
        val agentSide = if (i % 2 == 0) opening.sideToMove else opponent(opening.sideToMove)
        val black = if (agentSide == BLACK) agent else opponent
        val white = if (agentSide == BLACK) opponent else agent
        val record = playGame(black, white, rules, explore = false, maxPlies = maxPlies, from = opening)
        plies += record.plies.size
        when (record.winner) {
            agentSide -> wins++
            DRAW_RESULT, null -> draws++
            else -> losses++
        }
    }

    return EvalResult(
        games = games,
        wins = wins,
        draws = draws,
        losses = losses,
        avgPlies = if (games == 0) 0 else plies / games
    )
}

/**
 * How well [net] does against the fixed baselines, cheaply enough to run around a training run.
 *
 * Deliberately shallow and short. It does not need to measure strength precisely; it needs to
 * notice a set of weights falling off a cliff, and a two-ply search against a random and a greedy
 * opponent notices that with room to spare. Returns the mean of the two scores, so 1.0 is beating
 * both every time and 0.0 is losing every game.
 *
 * [seed] fixes the random baseline's games, so two calls either side of a training run are
 * comparing policies rather than comparing dice.
 */
fun guardScore(
    net: QNetwork,
    games: Int = 8,
    depth: Int = 2,
    seed: Long = 1234L,
    maxPlies: Int = 120
): Float {
    val knobs = Knobs(depth = depth, epsilon = 0f, risk = 0.6f, topK = 5)
    val agent = NetworkPlayer(net, knobs)
    val againstGreedy = evaluate(
        agent, GreedyPlayer(), games, maxPlies = maxPlies, random = Random(seed)
    )
    val againstRandom = evaluate(
        agent, RandomPlayer(Random(seed)), games, maxPlies = maxPlies, random = Random(seed + 1)
    )
    return (againstGreedy.score + againstRandom.score) / 2f
}

/**
 * The verdict on a candidate set of weights.
 *
 * [baseline] is the floor and [match] is the measurement. Keeping both is the point: the shipped
 * policy already beats the fixed opponents every game, so a baseline score on its own saturates
 * at 1.0 and can report a policy getting worse but never one getting better. The head-to-head
 * against the weights being replaced has no such ceiling - it is a fresh measurement at whatever
 * strength the policy has actually reached.
 */
data class GuardVerdict(
    /** Score against Random and Greedy. Catches a policy that has fallen off a cliff. */
    val baseline: Float,
    /** Score against the weights this candidate would replace. 0.5 is level. */
    val match: Float,
    val accepted: Boolean,
    val reason: String
) {
    val summary: String get() = "baseline ${pct(baseline)}, head-to-head ${pct(match)}"
}

private fun pct(value: Float) = "${Math.round(value * 100)}%"

/**
 * Whether [candidate] should replace [champion].
 *
 * Both conditions have to hold. The head-to-head is what admits an improvement; the baseline
 * floor is what stops a policy that has learned to beat its own previous version by exploiting a
 * shared blind spot - a real failure mode in self-play, and one the fixed opponents notice
 * immediately because they do not share the blind spot.
 *
 * [margin] is what the candidate has to clear rather than merely tie. A head-to-head over a few
 * dozen games is noisy, and accepting every coin-flip would let the policy random-walk; requiring
 * it to be visibly ahead means the weights only move when there is evidence they should.
 */
fun gate(
    candidate: QNetwork,
    champion: QNetwork,
    matchGames: Int = 20,
    baselineGames: Int = 12,
    depth: Int = 2,
    margin: Float = 0.55f,
    /**
     * How far the baseline may slip from the champion's before the candidate is refused.
     *
     * Loose, deliberately. A dozen games against a random opponent is a noisy measurement - it
     * swings fifteen points either way on weights that have not changed - and a tight floor
     * spends that noise rejecting candidates the head-to-head says are better. The floor is here
     * to catch a policy that has fallen off a cliff, which looks like forty points, not fifteen.
     */
    tolerance: Float = 0.18f,
    seed: Long = 1234L
): GuardVerdict {
    val knobs = Knobs(depth = depth, epsilon = 0f, risk = 0.6f, topK = 5)
    val match = evaluate(
        NetworkPlayer(candidate, knobs),
        NetworkPlayer(champion, knobs),
        games = matchGames,
        random = Random(seed)
    ).score

    val championBaseline = guardScore(champion, games = baselineGames, depth = depth, seed = seed)
    val candidateBaseline = guardScore(candidate, games = baselineGames, depth = depth, seed = seed)

    return when {
        candidateBaseline < championBaseline - tolerance -> GuardVerdict(
            candidateBaseline, match, false,
            "it lost ground against the fixed opponents"
        )

        match < margin -> GuardVerdict(
            candidateBaseline, match, false,
            "it did not clearly beat the policy it would replace"
        )

        else -> GuardVerdict(candidateBaseline, match, true, "it plays better than what it replaces")
    }
}

/** Which opponent the learner faces on game [index] of [games], 1-based. */
fun opponentKind(index: Int, games: Int, curriculum: Curriculum): String = when (curriculum) {
    Curriculum.SelfOnly -> OPPONENT_SELF
    Curriculum.VersusGreedy -> OPPONENT_GREEDY
    Curriculum.Mixed -> {
        val n = maxOf(1, games)
        val i = index.coerceAtLeast(1)
        when {
            i <= n / 3.0 -> OPPONENT_RANDOM
            i <= 2.0 * n / 3.0 -> OPPONENT_GREEDY
            else -> OPPONENT_SELF
        }
    }
}

const val OPPONENT_RANDOM = "random"
const val OPPONENT_GREEDY = "greedy"
const val OPPONENT_SELF = "self"

enum class Curriculum {
    /** Random, then greedy, then itself. The default, and the only one that reliably improves. */
    Mixed,
    VersusGreedy,
    SelfOnly
}

/** What one training session did. [kept] is false when the guard rejected the result. */
data class SelfPlayReport(
    val gamesPlayed: Int,
    val transitions: Int,
    val loss: Float,
    val verdict: GuardVerdict,
    val durationMs: Long,
    val replaySize: Int
) {
    val kept: Boolean get() = verdict.accepted

    /** How far ahead of the weights it replaced the new policy came out, as a percentage point. */
    val edge: Float get() = verdict.match - 0.5f
}

/** Progress during a session, for a screen that shows what is happening. */
data class SelfPlayProgress(
    val gameIndex: Int,
    val games: Int,
    val opponent: String,
    val winner: Int?,
    val plies: Int,
    val loss: Float
)

/**
 * Runs self-play sessions against [net], in place.
 *
 * The network is mutated rather than replaced because [EngineStore] hands the same object to the
 * search; a trainer that swapped in a new one would leave the game screen playing the old weights
 * until the next launch.
 */
class SelfPlayTrainer(
    private val net: QNetwork,
    private val replay: ReplayBuffer,
    private val gamma: Float = DEFAULT_GAMMA,
    private val random: Random = Random.Default,
    /** Search depth for the learning agent. Two is the server's training depth. */
    private val depth: Int = 2,
    /** How often the learner throws a move away to see what happens. */
    private val epsilon: Float = 0.12f,
    /**
     * The learning rate for a session, as a fraction of the one the artifact was trained at.
     *
     * A tenth. The device is fine-tuning a network with thousands of games behind it using a few
     * dozen of its own, and at the full rate that is not fine-tuning - it is overwriting. Measured
     * on the shipped policy, sessions at the artifact's own rate came out behind the weights they
     * were challenging every single time.
     */
    private val learningRateScale: Float = 0.1f,
    /** Gradient steps over the replay buffer once the games have been played. */
    private val trainingSteps: Int = 60,
    private val batchSize: Int = 64,
    /**
     * How a trained candidate is judged against the weights it would replace.
     *
     * Injectable so the rollback can be tested for what it is - bookkeeping around a verdict -
     * without staking the test on which of two policies happens to win a short match. It also
     * lets a caller spend more games on the check than the default when it has the time.
     */
    private val judge: (candidate: QNetwork, champion: QNetwork, games: Int) -> GuardVerdict =
        { candidate, champion, games -> gate(candidate, champion, matchGames = games) }
) {


    /**
     * Play [games] and fit the policy to them, keeping the result only if it benchmarks no worse.
     *
     * [shouldContinue] is checked between games so a player who leaves the screen stops the work
     * rather than paying for it in the background. A cancelled session still goes through the
     * guard: the games it did play are real training and there is no reason to discard them, but
     * there is every reason to check them.
     */
    fun run(
        games: Int = 20,
        curriculum: Curriculum = Curriculum.Mixed,
        rules: VariantRules = VariantRules.DEFAULT,
        guardGames: Int = 20,
        onProgress: (SelfPlayProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true }
    ): SelfPlayReport {
        val started = System.nanoTime()
        // The weights as they stand are the champion. Everything the session does is a challenge
        // to them, and it either wins the match at the end or it never happened.
        val champion = net.copy()

        val knobs = Knobs(depth = depth, epsilon = epsilon, risk = 0.6f, topK = 5)
        var played = 0
        var transitions = 0
        var lossTotal = 0f
        var lossBatches = 0

        for (index in 1..maxOf(0, games)) {
            if (!shouldContinue()) break

            val kind = opponentKind(index, games, curriculum)
            val opponent: Player = when (kind) {
                OPPONENT_RANDOM -> RandomPlayer(Random(random.nextLong()))
                OPPONENT_GREEDY -> GreedyPlayer()
                else -> NetworkPlayer(net, knobs, random)
            }
            val learnerPlayer = NetworkPlayer(net, knobs, random)

            // Alternate seats, for the same reason `evaluate` does: a policy trained only as Black
            // learns the opening advantage as a property of the position rather than of the seat.
            val learnerSide = if (index % 2 == 1) BLACK else WHITE
            val black = if (learnerSide == BLACK) learnerPlayer else opponent
            val white = if (learnerSide == BLACK) opponent else learnerPlayer

            val record = playGame(black, white, rules, explore = true)
            played++

            // Collected, not fitted. Training inside the loop would have the policy chasing the
            // game it just played, and the opponent it plays next is itself - so a session spent
            // fitting game by game drifts somewhere neither the corpus nor the baselines follow.
            val fresh = buildTransitions(record.plies, record.winner, learnerSide, gamma)
            replay.extend(fresh)
            transitions += fresh.size

            onProgress(
                SelfPlayProgress(
                    gameIndex = index,
                    games = games,
                    opponent = kind,
                    winner = record.winner,
                    plies = record.plies.size,
                    loss = 0f
                )
            )
        }

        // One training phase over the whole buffer, so every step sees a mix of this session's
        // games and everything before them. This is what keeps a session from washing out what
        // the downloaded policy already knew.
        if (played > 0 && replay.size >= 16) {
            val rate = net.lr
            net.lr = rate * learningRateScale
            try {
                repeat(trainingSteps) {
                    if (!shouldContinue()) return@repeat
                    val batch = replay.sample(batchSize, prioritized = true)
                    if (batch.isEmpty()) return@repeat
                    lossTotal += net.trainBatch(
                        batch.map { it.action },
                        FloatArray(batch.size) {
                            batch[it].monteCarloReturn.coerceIn(-TERMINAL_VALUE, TERMINAL_VALUE)
                        }
                    )
                    lossBatches++
                }
            } finally {
                net.lr = rate
            }
        }

        val verdict = if (played == 0) {
            GuardVerdict(0f, 0.5f, accepted = false, reason = "no games were played")
        } else {
            judge(net, champion, guardGames)
        }
        if (!verdict.accepted) net.restoreFrom(champion)

        return SelfPlayReport(
            gamesPlayed = played,
            transitions = transitions,
            loss = if (lossBatches == 0) 0f else lossTotal / lossBatches,
            verdict = verdict,
            durationMs = (System.nanoTime() - started) / 1_000_000,
            replaySize = replay.size
        )
    }
}
