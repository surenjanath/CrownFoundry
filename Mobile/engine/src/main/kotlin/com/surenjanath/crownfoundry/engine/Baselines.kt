package com.surenjanath.crownfoundry.engine

import kotlin.random.Random

/**
 * Fixed opponents the device can measure itself against - the Kotlin half of
 * `Backend/ai/baselines.py`.
 *
 * Neither of them learns, and that is the entire point. On the server these exist so a training
 * run can prove it improved something; on the phone they carry more weight than that, because the
 * phone has no corpus and no second opinion. They are the only evidence available on the device
 * that a set of weights got better rather than merely different, which is what
 * [guardScore] turns into a veto over saving them.
 */
fun interface Player {
    /** The move to play. [explore] permits deliberately suboptimal choices; baselines ignore it. */
    fun select(board: Board, explore: Boolean): Move
}

/** Uniformly random over the legal moves. The floor any real policy has to clear. */
class RandomPlayer(private val random: Random) : Player {
    override fun select(board: Board, explore: Boolean): Move {
        val moves = board.legalMoves()
        if (moves.isEmpty()) throw IllegalMove("no legal moves in this position")
        return moves[random.nextInt(moves.size)]
    }
}

/**
 * One-ply material grabber: take the most, crown when you can, break ties by notation.
 *
 * A stubborn baseline in draughts, because captures are mandatory and greed is often right.
 * Beating it consistently requires actually looking ahead. Deterministic, so a score against it
 * is repeatable.
 */
class GreedyPlayer : Player {

    private fun material(board: Board, side: Int): Float {
        var own = 0f
        var other = 0f
        for (square in 1..SQUARE_COUNT) {
            val code = board.codes[square]
            if (code == EMPTY) continue
            val value = if (isKing(code)) KING_VALUE else 1f
            if (sideOfPiece(code) == side) own += value else other += value
        }
        return own - other
    }

    override fun select(board: Board, explore: Boolean): Move {
        val moves = board.legalMoves()
        if (moves.isEmpty()) throw IllegalMove("no legal moves in this position")

        val side = board.sideToMove
        var best: Move? = null
        var bestValue = Float.NEGATIVE_INFINITY

        for (move in moves) {
            val after = board.apply(move)
            var value = material(after, side)
            when (after.winner()) {
                side -> value += 100f
                opponent(side) -> value -= 100f
                else -> Unit
            }
            // Subtract what the opponent can take straight back, so it does not hang pieces for
            // free the way a pure material count would.
            value -= after.legalMoves().maxOfOrNull { it.captures.size } ?: 0

            if (value > bestValue ||
                (value == bestValue && best != null && move.notation() < best.notation())
            ) {
                best = move
                bestValue = value
            }
        }
        return best ?: moves.first()
    }
}

/** The policy under training, playing at a fixed strength. */
class NetworkPlayer(
    private val net: QNetwork,
    private val knobs: Knobs,
    private val random: Random = Random.Default
) : Player {
    override fun select(board: Board, explore: Boolean): Move =
        LocalAgent(net, knobs, MistakeMemory.NONE, random).select(board, explore).first
}
