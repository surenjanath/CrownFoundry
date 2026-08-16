package com.surenjanath.crownfoundry.ui.components.board

import androidx.compose.runtime.Immutable
import com.surenjanath.crownfoundry.api.PieceDto

/**
 * One move, described in the terms the board needs to play it back: where the piece came from,
 * every square it lands on in order, which pieces leave the board on which hop, and whether it is
 * crowned at the end.
 *
 * [captured] carries the whole [PieceDto] rather than the square number because by the time the
 * animation runs the referee's new board no longer contains those pieces, and a captured king has
 * to fade out wearing its crown.
 */
@Immutable
data class BoardAnimation(
    val id: Long,
    val origin: Int,
    val path: List<Int>,
    val captured: List<PieceDto> = emptyList(),
    val crowned: Boolean = false,
    val side: String
) {
    val destination: Int get() = path.lastOrNull() ?: origin

    val hops: Int get() = path.size.coerceAtLeast(1)

    /** The square the mover occupies at the start of hop [index]. */
    fun from(index: Int): Int = if (index == 0) origin else path[index - 1]

    fun to(index: Int): Int = path.getOrElse(index) { destination }

    companion object {
        /** Reads a canonical `11x18x25` back into an animation. */
        fun of(
            id: Long,
            notation: String,
            captured: List<PieceDto>,
            crowned: Boolean,
            side: String
        ): BoardAnimation? {
            val tokens = notation.split('-', 'x').mapNotNull { it.trim().toIntOrNull() }
            if (tokens.size < 2) return null

            return BoardAnimation(
                id = id,
                origin = tokens.first(),
                path = tokens.drop(1),
                captured = captured,
                crowned = crowned,
                side = side
            )
        }
    }
}

/** The faint line left behind by the move that was just played. */
@Immutable
data class BoardTrace(val from: Int, val to: Int)
