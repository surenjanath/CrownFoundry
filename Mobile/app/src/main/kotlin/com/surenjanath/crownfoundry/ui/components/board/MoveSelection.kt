package com.surenjanath.crownfoundry.ui.components.board

import androidx.compose.runtime.Immutable
import com.surenjanath.crownfoundry.api.MoveDto

/**
 * `11x18x25` -> `[18, 25]`, `11-15` -> `[15]`: every square the mover lands on, in order, the
 * origin excluded. The notation is the only place the intermediate landings of a multi-jump are
 * carried, so it - not [MoveDto.to] - is what the board narrows against.
 */
fun MoveDto.landingSquares(): List<Int> {
    val tokens = notation.split('-', 'x')
    if (tokens.size < 2) return listOf(to)

    val landings = ArrayList<Int>(tokens.size - 1)
    for (index in 1 until tokens.size) {
        val square = tokens[index].trim().toIntOrNull() ?: return listOf(to)
        landings.add(square)
    }
    return landings
}

object MoveTree {
    /** Captures are compulsory: when one exists, nothing else is legal. */
    fun capturesPending(moves: List<MoveDto>) = moves.any(MoveDto::isJump)

    /**
     * The server already applies the compulsory-capture rule, but filtering again here means the
     * board draws the same restriction it enforces even if the two ever disagree.
     */
    fun playable(moves: List<MoveDto>): List<MoveDto> =
        if (capturesPending(moves)) moves.filter(MoveDto::isJump) else moves

    fun selectableSquares(moves: List<MoveDto>): Set<Int> =
        playable(moves).mapTo(LinkedHashSet()) { it.from }

    /** The selection for tapping [square], or null when no legal move starts there. */
    fun begin(moves: List<MoveDto>, square: Int): BoardSelection? {
        val candidates = playable(moves).filter { it.from == square }
        return if (candidates.isEmpty()) null else BoardSelection(square, candidates)
    }
}

/**
 * A move being typed out with the finger. A simple move is one tap on a destination; a triple jump
 * is three, because after each hop [candidates] narrows to the moves that still match the path.
 */
@Immutable
data class BoardSelection(
    val origin: Int,
    val candidates: List<MoveDto>,
    val path: List<Int> = emptyList()
) {
    /** Where the piece is standing right now - the origin, or the last hop taken. */
    val square: Int get() = path.lastOrNull() ?: origin

    val isMidJump: Boolean get() = path.isNotEmpty()

    /** The squares that are legal to tap next. */
    val destinations: Set<Int> =
        candidates.mapNotNullTo(LinkedHashSet()) { it.landingSquares().getOrNull(path.size) }

    /** Opponent pieces already jumped over on this path, in order. */
    val capturedSoFar: List<Int> =
        candidates.firstOrNull()?.captures?.take(path.size).orEmpty()

    /** Every piece that one of the offered destinations would take. */
    val threatened: Set<Int> =
        candidates.mapNotNullTo(LinkedHashSet()) { it.captures.getOrNull(path.size) }

    /** The piece taken by landing on [destination], if that hop is a capture. */
    fun captureAt(destination: Int): Int? = candidates
        .firstOrNull { it.landingSquares().getOrNull(path.size) == destination }
        ?.captures?.getOrNull(path.size)

    fun advance(destination: Int): SelectionStep {
        val hop = path.size
        val narrowed = candidates.filter { it.landingSquares().getOrNull(hop) == destination }
        if (narrowed.isEmpty()) return SelectionStep.Rejected

        val next = path + destination

        // A jump must be played to completion, so no legal move's landing list is a strict prefix
        // of another's: the first candidate that ends here is the move, unambiguously.
        val completed = narrowed.firstOrNull { it.landingSquares().size == next.size }

        return if (completed != null) SelectionStep.Completed(completed)
        else SelectionStep.Continued(copy(candidates = narrowed, path = next))
    }
}

sealed interface SelectionStep {
    data class Continued(val selection: BoardSelection) : SelectionStep
    data class Completed(val move: MoveDto) : SelectionStep
    data object Rejected : SelectionStep
}
