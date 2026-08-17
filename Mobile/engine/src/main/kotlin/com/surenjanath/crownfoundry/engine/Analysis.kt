package com.surenjanath.crownfoundry.engine

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Scoring a finished game move by move - the coach.
 *
 * The engine already knows how to rank every legal move in a position. Analysis is that same
 * search, pointed backwards: for each ply, what did the mover actually play, what would the engine
 * have played, and how much did the difference cost? That gap is the whole product. Everything
 * else here is presentation.
 *
 * Two decisions worth stating, because both change the numbers a player is shown:
 *
 * * **The risk bonus is off.** It is a style preference, not an evaluation, and reporting "this
 *   move was worth 1.4" with a boldness nudge baked in would be lying about what the number means.
 * * **The evaluation is read off the search that was already run.** Scoring a move produces the
 *   value of the position it leads to from the *mover's* perspective, so flipping the sign for
 *   White gives a whole-game curve for free. The encoder is perspective-symmetric but the learned
 *   value head is not exactly antisymmetric, so the curve is a faithful indicator of who stands
 *   better rather than a quantity you could arbitrage against a second opinion.
 */

enum class MoveQuality(val label: String) {
    Best("Best"),
    Good("Good"),
    Inaccuracy("Inaccuracy"),
    Mistake("Mistake"),
    Blunder("Blunder");

    /** The two worth interrupting someone about - and the two that become puzzles. */
    val isError get() = this == Mistake || this == Blunder
}

/**
 * How much a move has to give up to earn each label, in the search's own units.
 *
 * For a trained policy roughly one unit is one man, which is the scale these were chosen on. They
 * are constants rather than magic numbers so a change is a change to one line, and so the puzzle
 * generator and the review screen can never disagree about what counts as a blunder.
 */
object QualityThresholds {
    const val BEST = 0.05f
    const val GOOD = 0.35f
    const val INACCURACY = 1.0f
    const val MISTAKE = 2.5f

    fun of(loss: Float): MoveQuality = when {
        loss <= BEST -> MoveQuality.Best
        loss < GOOD -> MoveQuality.Good
        loss < INACCURACY -> MoveQuality.Inaccuracy
        loss < MISTAKE -> MoveQuality.Mistake
        else -> MoveQuality.Blunder
    }
}

data class AnalysedMove(
    /** 1-based, counting every half-move, so it lines up with the review scrubber. */
    val ply: Int,
    val side: Int,
    val notation: String,
    /** The position *before* the move, which is what a puzzle needs. */
    val fen: String,
    /** What the engine would have played here. */
    val best: String,
    /** How much the played move gave up against [best]. Never negative. */
    val loss: Float,
    val quality: MoveQuality,
    /** After the move, from Black's point of view: positive means Black stands better. */
    val evaluation: Float,
    /** The engine's shortlist, best first, for "what else was there?". */
    val alternatives: List<ScoredMove>
) {
    val wasBest get() = quality == MoveQuality.Best || notation == best
}

data class GameAnalysis(
    val moves: List<AnalysedMove>,
    /** Black's evaluation of the opening position, so the curve starts somewhere honest. */
    val openingEvaluation: Float,
    val depth: Int
) {
    val isEmpty get() = moves.isEmpty()

    /** The opening, then one point per ply. Positive means Black is better. */
    val evaluationSeries: List<Float>
        get() = buildList(moves.size + 1) {
            add(openingEvaluation)
            moves.forEach { add(it.evaluation) }
        }

    fun movesBy(side: Int) = moves.filter { it.side == side }

    fun count(side: Int, quality: MoveQuality) = moves.count { it.side == side && it.quality == quality }

    fun errors(side: Int) = moves.filter { it.side == side && it.quality.isError }

    /** Average loss per move, in the search's units. The single number that says "how well?". */
    fun averageLoss(side: Int): Float {
        val own = movesBy(side)
        if (own.isEmpty()) return 0f
        return own.sumOf { it.loss.toDouble() }.toFloat() / own.size
    }

    fun accuracy(side: Int): Float {
        val own = movesBy(side)
        if (own.isEmpty()) return 0f
        return own.count { it.quality == MoveQuality.Best || it.quality == MoveQuality.Good }
            .toFloat() / own.size
    }

    /** The move that cost the most. The one thing to show if there is only room for one. */
    fun turningPoint(side: Int? = null): AnalysedMove? =
        moves.filter { side == null || it.side == side }.maxByOrNull { it.loss }
            ?.takeIf { it.quality.isError }
}

class GameAnalyser(
    private val net: QNetwork,
    private val depth: Int = 4,
    private val nodeBudget: Int = DEFAULT_NODE_BUDGET,
    /** How many alternatives to keep per move for the "what else?" list. */
    private val alternatives: Int = 3
) {

    /**
     * Score every ply of [plies].
     *
     * Suspending and cancellable on purpose: a long game is a few seconds of search on a phone, and
     * a player who backs out of the review screen should not be charged for the rest of it.
     * [onProgress] is called on the calling context, once per ply.
     */
    suspend fun analyse(
        plies: List<Ply>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): GameAnalysis {
        if (plies.isEmpty()) return GameAnalysis(emptyList(), 0f, depth)

        val agent = LocalAgent(net, Knobs(depth, epsilon = 0f, risk = 0.5f, topK = alternatives + 1, nodeBudget))
        val analysed = ArrayList<AnalysedMove>(plies.size)

        val opening = plies.first().board
        val openingEvaluation = agent.evaluate(opening, BLACK, depth)

        for ((index, ply) in plies.withIndex()) {
            currentCoroutineContext().ensureActive()

            val scored = agent.scoreMoves(ply.board, applyRiskBonus = false)
            if (scored.isEmpty()) break

            val notation = ply.move.notation()
            val best = scored.first()
            val played = scored.firstOrNull { it.move.notation() == notation }
                // Only reachable if the move list and the rules disagree, which replayMoves would
                // already have stopped. Falling back to the best move reports no loss rather than
                // inventing one.
                ?: best

            val loss = (best.value - played.value).coerceAtLeast(0f)
            // The search scores from the mover's point of view; Black's curve is White's negated.
            val evaluation = if (ply.side == BLACK) played.value else -played.value

            analysed.add(
                AnalysedMove(
                    ply = index + 1,
                    side = ply.side,
                    notation = notation,
                    fen = ply.board.toFen(),
                    best = best.move.notation(),
                    loss = loss,
                    quality = QualityThresholds.of(loss),
                    evaluation = evaluation,
                    alternatives = scored.take(alternatives).map {
                        ScoredMove(it.move.notation(), round4(it.value))
                    }
                )
            )

            onProgress?.invoke(index + 1, plies.size)
        }

        return GameAnalysis(analysed, openingEvaluation, depth)
    }

    private fun round4(value: Float) = Math.round(value * 10000f) / 10000f
}

/**
 * A position worth practising, mined from a game the player got wrong.
 *
 * Deliberately not "a position the engine finds interesting": the whole appeal is that these are
 * *your* mistakes, in positions you actually reached, and the answer is a move you could have
 * played at the time.
 */
data class PuzzleSeed(
    val fen: String,
    val rules: VariantRules,
    val best: String,
    val played: String,
    val loss: Float,
    val quality: MoveQuality,
    val ply: Int,
    val alternatives: List<ScoredMove>
)

/**
 * Turn a game's analysis into puzzles for [side].
 *
 * Only errors qualify, and only positions with a real choice: a forced move is not a puzzle, and a
 * position where the engine's second choice is just as good would mark a correct answer wrong.
 */
fun puzzleSeedsFrom(
    analysis: GameAnalysis,
    side: Int,
    rules: VariantRules = VariantRules.DEFAULT,
    limit: Int = 3
): List<PuzzleSeed> = analysis.errors(side)
    .filter { it.alternatives.size > 1 }
    .filter { move ->
        // The gap between best and second-best has to be big enough that "the best move" is a
        // meaningful thing to ask for. Otherwise the player plays a fine move and is told no.
        val second = move.alternatives.getOrNull(1) ?: return@filter false
        (move.alternatives.first().q - second.q) > QualityThresholds.GOOD
    }
    .sortedByDescending { it.loss }
    .take(limit)
    .map {
        PuzzleSeed(
            fen = it.fen,
            rules = rules,
            best = it.best,
            played = it.notation,
            loss = it.loss,
            quality = it.quality,
            ply = it.ply,
            alternatives = it.alternatives
        )
    }

/** Rethrown rather than swallowed, so a cancelled analysis stays cancelled. */
internal fun rethrowIfCancellation(failure: Throwable) {
    if (failure is CancellationException) throw failure
}
