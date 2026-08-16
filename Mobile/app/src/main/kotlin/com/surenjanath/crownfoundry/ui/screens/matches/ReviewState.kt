package com.surenjanath.crownfoundry.ui.screens.matches

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.enums.Difficulty

/**
 * A finished match, taken apart into positions you can step through.
 *
 * The referee stores a FEN against every ply but only sends a `pieces` array for the live board,
 * so each position here is parsed out of its own FEN by [Fen]. Ply 0 is the opening - the board
 * before anyone moved - which is why the scrubber reads "0 of 48" at the start and not "1 of 48".
 */
data class ReviewPly(
    val index: Int,
    val position: Position,
    val side: String?,
    val move: String?,
    val turn: Int?,
    val reasoning: String?
) {
    val isAi: Boolean get() = side == Side.AI
    val isHuman: Boolean get() = side == Side.HUMAN

    /** The squares this move touched, for the ring on the board. */
    val highlightedSquares: List<Int> get() = Fen.squaresOfMove(move)
}

data class ReviewState(
    val isLoading: Boolean = true,
    val match: MatchDto? = null,
    val plies: List<ReviewPly> = emptyList(),
    val plyIndex: Int = 0,
    val error: ApiError? = null
) {
    val current: ReviewPly? get() = plies.getOrNull(plyIndex)
    val canStepBack: Boolean get() = plyIndex > 0
    val canStepForward: Boolean get() = plyIndex < plies.lastIndex

    /** "12 of 48". The opening counts as a position, not as a move. */
    val scrubberLabel: String
        get() = if (plies.isEmpty()) "no positions" else "$plyIndex of ${plies.lastIndex}"
}

/** Builds the replay: the opening, then one position per recorded move. */
fun pliesOf(match: MatchDto): List<ReviewPly> {
    val opening = Fen.parse(match.initialBoard) ?: Fen.parseOrEmpty(Fen.OPENING)

    val plies = mutableListOf(
        ReviewPly(
            index = 0,
            position = opening,
            side = null,
            move = null,
            turn = 0,
            reasoning = null
        )
    )

    match.history.forEachIndexed { index, entry ->
        // A ply whose FEN did not survive the round trip keeps the previous board rather than
        // blanking it: the move and the reasoning are still worth reading.
        val position = Fen.parse(entry.fen) ?: plies.last().position

        plies += ReviewPly(
            index = index + 1,
            position = position,
            side = entry.side,
            move = entry.move,
            turn = entry.turn,
            reasoning = entry.reasoning?.takeIf { it.isNotBlank() }
        )
    }

    return plies
}

/** The one line at the top of the review: who won, in how many turns, at what difficulty. */
fun reviewHeadline(match: MatchDto): String {
    val turns = match.turnNumber
    val difficulty = Difficulty.fromWire(match.difficulty).label

    val result = when {
        !match.isFinished -> "Still going"
        match.winner == Side.HUMAN -> "You won"
        match.winner == Side.AI -> "It won"
        else -> "Drawn"
    }

    val length = when {
        turns <= 0 -> ""
        match.isFinished -> " in $turns ${if (turns == 1) "turn" else "turns"}"
        else -> ", $turns ${if (turns == 1) "turn" else "turns"} in"
    }

    return "$result$length · $difficulty"
}

class ReviewStateHolder(
    private val api: CheckersApi,
    private val matchId: String
) {
    var state by mutableStateOf(ReviewState())
        private set

    suspend fun load() {
        state = state.copy(isLoading = true, error = null)

        state = when (val outcome = api.match(matchId)) {
            is Outcome.Success -> {
                val plies = pliesOf(outcome.value)
                ReviewState(
                    isLoading = false,
                    match = outcome.value,
                    plies = plies,
                    // Opening a finished match on move one would mean scrubbing through it to see
                    // how it ended; the last position is the one the reader came for.
                    plyIndex = plies.lastIndex.coerceAtLeast(0),
                    error = null
                )
            }

            is Outcome.Failure -> ReviewState(
                isLoading = false,
                error = outcome.reason
            )
        }
    }

    fun seek(index: Int) {
        val last = state.plies.lastIndex
        state = state.copy(plyIndex = if (last < 0) 0 else index.coerceIn(0, last))
    }

    fun stepForward() = seek(state.plyIndex + 1)

    fun stepBack() = seek(state.plyIndex - 1)

    fun toStart() = seek(0)

    fun toEnd() = seek(state.plies.lastIndex)
}
