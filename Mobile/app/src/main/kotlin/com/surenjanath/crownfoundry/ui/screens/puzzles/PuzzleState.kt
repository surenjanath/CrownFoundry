package com.surenjanath.crownfoundry.ui.screens.puzzles

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.surenjanath.crownfoundry.api.MoveDto
import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.BLACK
import com.surenjanath.crownfoundry.engine.Board
import com.surenjanath.crownfoundry.offline.Puzzle
import com.surenjanath.crownfoundry.offline.PuzzleStore
import com.surenjanath.crownfoundry.offline.toDto
import com.surenjanath.crownfoundry.offline.toDtos
import com.surenjanath.crownfoundry.offline.toEngineRules
import com.surenjanath.crownfoundry.ui.components.board.BoardSelection
import com.surenjanath.crownfoundry.ui.components.board.MoveTree
import com.surenjanath.crownfoundry.ui.components.board.TapResult
import com.surenjanath.crownfoundry.ui.components.board.resolveTap

/**
 * One puzzle, being attempted.
 *
 * A puzzle is a position with exactly one answer, so this is a much smaller machine than the game:
 * there is no referee to ask and no opponent to reply. The rules engine generates the legal moves,
 * the same tap resolver the live board uses turns fingers into a move, and the answer is a string
 * comparison against what the engine would have played.
 *
 * Nothing here writes to the store. Recording an attempt is the screen's to do, once, at the
 * moment the attempt finishes - a state machine that persisted on every tap would count a change
 * of mind as a failure.
 */

sealed interface PuzzleVerdict {
    data object Unanswered : PuzzleVerdict

    data class Correct(val notation: String) : PuzzleVerdict

    data class Wrong(val notation: String) : PuzzleVerdict

    /** The player asked to be shown. It counts as a finished attempt, and not a successful one. */
    data object Revealed : PuzzleVerdict

    val isFinished get() = this !is Unanswered

    /** True only for a genuine solve; being shown the answer is not solving it. */
    val isCorrect get() = this is Correct
}

@Stable
class PuzzleSession private constructor(
    val puzzle: Puzzle,
    private val board: Board
) {
    val pieces: List<PieceDto> = board.toDto().pieces
    val legalMoves: List<MoveDto> = board.legalMoves().toDtos()

    /** Whose move it is, in the wire's vocabulary, so the screen can say whose turn to find. */
    val sideToMove: String = if (board.sideToMove == BLACK) Side.BLACK else Side.WHITE

    val mustCapture: Boolean = MoveTree.capturesPending(legalMoves)

    var selection by mutableStateOf<BoardSelection?>(null)
        private set

    var verdict by mutableStateOf<PuzzleVerdict>(PuzzleVerdict.Unanswered)
        private set

    /** Wrong answers this sitting. The store counts attempts; this drives the wording. */
    var misses by mutableStateOf(0)
        private set

    val acceptsTaps get() = !verdict.isFinished

    /** The squares to ring once the answer is on screen - the move that was there. */
    val answerSquares: List<Int>
        get() = legalMoves.firstOrNull { it.notation == puzzle.best }
            ?.let { listOf(it.from, it.to) }
            .orEmpty()

    fun tap(square: Int): TapResult {
        if (!acceptsTaps) return TapResult.Ignored

        val result = resolveTap(legalMoves, selection, square)

        selection = when (result) {
            is TapResult.Selected -> result.selection
            is TapResult.Advanced -> result.selection
            is TapResult.Cleared, is TapResult.Ready -> null
            is TapResult.Ignored -> selection
        }

        if (result is TapResult.Ready) answer(result.notation)

        return result
    }

    fun answer(notation: String) {
        verdict = if (notation == puzzle.best) {
            PuzzleVerdict.Correct(notation)
        } else {
            misses += 1
            PuzzleVerdict.Wrong(notation)
        }
    }

    fun reveal() {
        if (verdict.isCorrect) return
        verdict = PuzzleVerdict.Revealed
    }

    /**
     * Another go at the same position, from a wrong answer only.
     *
     * A wrong answer already counted and this does not erase it. Revealing is deliberately a dead
     * end: allowing a retry after being shown the move would let any puzzle be recorded as solved.
     */
    fun retry() {
        if (verdict !is PuzzleVerdict.Wrong) return
        selection = null
        verdict = PuzzleVerdict.Unanswered
    }

    companion object {
        /**
         * `null` for a puzzle whose position will not parse, or whose answer is not legal in it.
         *
         * Both mean the stored puzzle is wrong rather than hard, and a puzzle with no reachable
         * right answer is the fastest way to make the feature feel broken.
         */
        fun of(puzzle: Puzzle): PuzzleSession? {
            val board = runCatching {
                Board.fromFen(puzzle.fen, rules = puzzle.rules.toEngineRules())
            }.getOrNull() ?: return null

            val session = PuzzleSession(puzzle, board)
            if (session.legalMoves.none { it.notation == puzzle.best }) return null
            return session
        }
    }
}

/** The line under a finished puzzle: what happened, and what was there instead. */
fun verdictLine(session: PuzzleSession): String? = when (val verdict = session.verdict) {
    is PuzzleVerdict.Unanswered -> null

    is PuzzleVerdict.Correct -> "Correct. ${verdict.notation} was the move."

    is PuzzleVerdict.Wrong ->
        "${verdict.notation} is not it. Try again, or reveal the answer."

    is PuzzleVerdict.Revealed ->
        "${session.puzzle.best} was the move. You played ${session.puzzle.played} at the time."
}

/** The one-line description of a puzzle in the list. */
fun puzzleSubtitle(puzzle: Puzzle): String {
    val quality = puzzle.quality.ifBlank { "Mistake" }
    return when {
        puzzle.solvedFirstTime -> "$quality · solved first time"
        puzzle.solved -> "$quality · solved"
        puzzle.attempts > 0 -> "$quality · ${puzzle.attempts} ${tries(puzzle.attempts)} so far"
        else -> "$quality · move ${puzzle.ply}, from one of your games"
    }
}

private fun tries(count: Int) = if (count == 1) "try" else "tries"

/** Reads the store into a list the screen can draw, newest and unsolved first. */
class PuzzleListHolder(private val store: PuzzleStore?) {

    var isLoading by mutableStateOf(true)
        private set

    var puzzles by mutableStateOf<List<Puzzle>>(emptyList())
        private set

    val solved get() = puzzles.count { it.solved }

    suspend fun load() {
        isLoading = true
        puzzles = store?.all().orEmpty()
        isLoading = false
    }

    suspend fun record(id: String, solved: Boolean) {
        store?.record(id, solved)
        load()
    }
}
