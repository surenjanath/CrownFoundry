package com.surenjanath.crownfoundry.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Search, move selection and opponent modelling on the device - the playing half of
 * `Backend/ai/agent.py`.
 *
 * The value head scores *afterstates*, so a shallow alpha-beta over the same evaluator is a strict
 * improvement on the raw network rather than a different thing bolted on. That matters more
 * offline than online: search is the part of the strength that costs no download and degrades
 * gracefully, so a device holding an out-of-date policy still plays a respectable game while it
 * waits for a connection.
 */

/** Rewards, straight from `prd.md` section 3. Shared with [OfflineLearner]. */
const val REWARD_WIN = 10f
const val REWARD_LOSS = -10f
const val REWARD_CAPTURE = 2f
const val REWARD_CROWN = 3f
const val REWARD_PIECE_LOST = -2f

/** Value the search assigns to a decided position, matched to [REWARD_WIN]. */
const val TERMINAL_VALUE = 10f

/**
 * Deduction applied to a candidate the AI has already been punished for playing in this exact
 * position. Large enough to reorder near-ties, small enough not to override a real blunder check.
 */
const val MISTAKE_PENALTY = 1.5f

/** Matches `CROWNFOUNDRY.SEARCH_NODE_BUDGET` on the server, so both sides search the same tree. */
const val DEFAULT_NODE_BUDGET = 4000

data class Knobs(
    val depth: Int,
    val epsilon: Float,
    val risk: Float,
    val topK: Int,
    /**
     * Nodes the search may expand before it falls back on the static evaluation.
     *
     * The backend's figure, unchanged. It was worth measuring rather than assuming: alpha-beta
     * prunes hard enough at depth 4 that the budget is rarely the binding constraint, and a full
     * turn costs single-digit milliseconds against the shipped 148-128-64-1 network. Handicapping
     * the device would have bought nothing and cost the player a weaker opponent offline than on -
     * see `SearchBudgetTest`, which measures this and prints what it found.
     */
    val nodeBudget: Int = DEFAULT_NODE_BUDGET
)

/** What the device knows about the human it is playing. The offline half of opponent modelling. */
data class OpponentProfile(
    val totalGames: Int = 0,
    /** The *human's* win rate. A high one means the current policy is in a rut. */
    val winRate: Float = 0f,
    val styleAggression: Float = 0f,
    val styleKingRush: Float = 0f
)

data class ScoredMove(val notation: String, val q: Float)

/** One legal move, its search value, and whether the agent has been burned by it here before. */
data class Candidate(val move: Move, val value: Float, val repeatMistake: Boolean)

/** Positions and moves the agent has already been punished for. Backed by the local match store. */
fun interface MistakeMemory {
    fun knownMistakes(fen: String): Set<String>

    companion object {
        val NONE = MistakeMemory { emptySet() }
    }
}

private fun clamp(value: Float, low: Float, high: Float) = max(low, min(high, value))

/** Map a difficulty - and, for `adaptive`, the opponent model - onto concrete settings. */
fun knobsFor(
    difficulty: String?,
    profile: OpponentProfile? = null,
    baseDepth: Int = 4,
    nodeBudget: Int = DEFAULT_NODE_BUDGET
): Knobs {
    val base = max(1, baseDepth)
    return when (difficulty?.trim()?.lowercase() ?: "adaptive") {
        // An honest handicap: shallow search and a third of its moves thrown away at random.
        "easy" -> Knobs(depth = 1, epsilon = 0.35f, risk = 0.2f, topK = 3, nodeBudget = nodeBudget)
        "normal" -> Knobs(min(2, base), 0.10f, 0.5f, 4, nodeBudget)
        "hard" -> Knobs(base, 0f, 0.7f, 5, nodeBudget)

        else -> {
            var depth = base
            var epsilon = 0.06f
            var risk = 0.6f
            if (profile != null && profile.totalGames >= 3) {
                // Losing to this human means the policy is in a rut: search harder and explore
                // more, because repeating the same losing line is the one guaranteed failure.
                val deficit = clamp(profile.winRate - 0.5f, 0f, 0.5f)
                depth = base + if (profile.winRate > 0.6f) 1 else 0
                epsilon = clamp(0.03f + 0.30f * deficit, 0.02f, 0.20f)
                // An aggressive opponent trades pieces off; meet that with a lower risk appetite
                // so the agent stops offering material. A king-rusher is punished by holding the
                // back rank, which is what a low risk appetite does.
                risk = clamp(0.65f - 0.4f * profile.styleAggression - 0.2f * profile.styleKingRush,
                    0.1f, 0.9f)
            }
            Knobs(depth, epsilon, risk, 5, nodeBudget)
        }
    }
}

class LocalAgent(
    val net: QNetwork,
    val knobs: Knobs,
    private val memory: MistakeMemory = MistakeMemory.NONE,
    private val random: Random = Random.Default
) {
    /** Set by [select] so the caller can record what happened without re-deriving it. */
    var lastWasRepeatMistake: Boolean = false
        private set

    var lastExplored: Boolean = false
        private set

    // --- evaluation ---------------------------------------------------------------------

    private fun leaf(board: Board, perspective: Int, cache: HashMap<Long, Float>): Float {
        cache[board.positionHash]?.let { return it }

        var value = net.predict(encode(board, perspective))
        // The bridge (squares 30 and 32 for White, 1 and 3 for Black) is the one structural motif
        // a shallow search reliably misvalues, and the one club players punish hardest.
        value += if (perspective == WHITE) {
            var bonus = 0f
            if (board.codes[30] != EMPTY && sideOfPiece(board.codes[30]) == WHITE) bonus += 0.12f
            if (board.codes[32] != EMPTY && sideOfPiece(board.codes[32]) == WHITE) bonus += 0.12f
            if (board.codes[5] == WHITE_MAN) bonus -= 0.15f
            bonus
        } else {
            var bonus = 0f
            if (board.codes[1] != EMPTY && sideOfPiece(board.codes[1]) == BLACK) bonus += 0.12f
            if (board.codes[3] != EMPTY && sideOfPiece(board.codes[3]) == BLACK) bonus += 0.12f
            if (board.codes[28] == BLACK_MAN) bonus -= 0.15f
            bonus
        }

        cache[board.positionHash] = value
        return value
    }

    /** `null` while the game is live. Takes an already-computed move list to stay cheap. */
    private fun terminalValue(board: Board, moves: List<Move>, perspective: Int, ply: Int): Float? {
        if (moves.isEmpty()) {
            // Faster mates are worth more, so a won position is never traded for a slower one.
            val value = TERMINAL_VALUE - 0.01f * ply
            return if (board.sideToMove == perspective) -value else value
        }
        if (board.pliesSinceProgress >= NO_PROGRESS_PLIES) return 0f
        if (board.repetitionCount() >= REPETITION_LIMIT) return 0f
        return null
    }

    private fun search(
        board: Board,
        depth: Int,
        alphaIn: Float,
        betaIn: Float,
        perspective: Int,
        ply: Int,
        cache: HashMap<Long, Float>,
        budget: IntArray
    ): Float {
        var moves = board.legalMoves()
        terminalValue(board, moves, perspective, ply)?.let { return it }

        if (depth <= 0 || budget[0] <= 0) {
            // Quiescence: never stop the search in the middle of a forced capture chain, or the
            // evaluation is of a position that cannot legally persist.
            val jumps = if (depth > -2) moves.filter { it.isJump } else emptyList()
            if (jumps.isEmpty()) return leaf(board, perspective, cache)
            moves = jumps
        }

        budget[0] -= moves.size
        var children = moves.map { board.apply(it) }
        val maximizing = board.sideToMove == perspective

        if (children.size > 1) {
            // Order by the static evaluation so alpha-beta prunes early.
            val values = FloatArray(children.size) { leaf(children[it], perspective, cache) }
            val order = children.indices.sortedByDescending { if (maximizing) values[it] else -values[it] }
            children = order.map { children[it] }
        }

        var alpha = alphaIn
        var beta = betaIn
        if (maximizing) {
            var best = Float.NEGATIVE_INFINITY
            for (child in children) {
                best = max(best, search(child, depth - 1, alpha, beta, perspective, ply + 1, cache, budget))
                alpha = max(alpha, best)
                if (alpha >= beta) break
            }
            return best
        }
        var best = Float.POSITIVE_INFINITY
        for (child in children) {
            best = min(best, search(child, depth - 1, alpha, beta, perspective, ply + 1, cache, budget))
            beta = min(beta, best)
            if (alpha >= beta) break
        }
        return best
    }

    fun evaluate(board: Board, perspective: Int = board.sideToMove, depth: Int = knobs.depth): Float =
        search(
            board, depth, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, perspective, 0,
            HashMap(), intArrayOf(knobs.nodeBudget)
        )

    // --- move selection -----------------------------------------------------------------

    /** A small, deterministic preference nudge; the search does the real work. */
    private fun riskBonus(move: Move, after: Board): Float {
        var bonus = 0f
        if (move.captures.isNotEmpty()) bonus += 0.10f * move.captures.size * knobs.risk
        if (move.crowned) bonus += 0.20f * knobs.risk
        // The reply can jump us. A cautious setting dislikes that more than a bold one.
        if (after.hasJump()) bonus -= 0.15f * (1f - knobs.risk)
        return bonus
    }

    /**
     * Every legal move with its score and whether it is a known past mistake, best first.
     *
     * [applyRiskBonus] is on for play and off for analysis. The bonus is a *style* nudge - a bold
     * setting likes captures, a cautious one dislikes offering trades - and folding style into a
     * number presented to the player as "what this move was worth" would be misreporting it.
     */
    fun scoreMoves(board: Board, applyRiskBonus: Boolean = true): List<Candidate> {
        val moves = board.legalMoves()
        if (moves.isEmpty()) return emptyList()

        val perspective = board.sideToMove
        val depth = max(1, knobs.depth)
        val cache = HashMap<Long, Float>()
        val budget = intArrayOf(knobs.nodeBudget)
        val mistakes = try {
            memory.knownMistakes(board.toFen())
        } catch (failure: Exception) {
            emptySet()
        }

        val scored = ArrayList<Candidate>(moves.size)
        for (move in moves) {
            val after = board.apply(move)
            var value = search(
                after, depth - 1, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, perspective, 1,
                cache, budget
            )
            if (applyRiskBonus) value += riskBonus(move, after)
            val repeat = move.notation() in mistakes
            if (repeat) value -= MISTAKE_PENALTY
            scored.add(Candidate(move, value, repeat))
        }

        // Notation is the tiebreaker so equal-valued positions always resolve the same way.
        return scored.sortedWith(compareByDescending<Candidate> { it.value }.thenBy { it.move.notation() })
    }

    /** Choose a move. Returns it with the shortlist the UI shows under "considered". */
    fun select(board: Board, explore: Boolean = false): Pair<Move, List<ScoredMove>> {
        val scored = scoreMoves(board)
        if (scored.isEmpty()) throw IllegalMove("no legal moves in this position")

        var chosenIndex = 0
        lastExplored = false
        if (explore && knobs.epsilon > 0f && scored.size > 1 && random.nextFloat() < knobs.epsilon) {
            chosenIndex = random.nextInt(scored.size)
            lastExplored = chosenIndex != 0
        }

        val chosen = scored[chosenIndex]
        lastWasRepeatMistake = chosen.repeatMistake

        val topK = max(1, knobs.topK)
        val considered = scored.take(topK)
            .map { ScoredMove(it.move.notation(), roundTo4(it.value)) }
            .toMutableList()
        val notation = chosen.move.notation()
        if (considered.none { it.notation == notation }) {
            considered.add(ScoredMove(notation, roundTo4(chosen.value)))
        }
        return chosen.move to considered
    }

    private fun roundTo4(value: Float): Float = Math.round(value * 10000f) / 10000f
}

/** How much daylight the top move has over the field, squashed into `[0, 1]`. */
fun confidenceOf(considered: List<ScoredMove>): Float =
    confidenceOf(FloatArray(considered.size) { considered[it].q })
