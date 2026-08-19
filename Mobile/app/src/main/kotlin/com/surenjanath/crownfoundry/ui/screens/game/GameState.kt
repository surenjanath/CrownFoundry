package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.surenjanath.crownfoundry.api.AiStatusDto
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.BoardDto
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.EvaluationDto
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.MoveDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.components.board.BoardAnimation
import com.surenjanath.crownfoundry.ui.components.board.BoardSelection
import com.surenjanath.crownfoundry.ui.components.board.BoardTrace
import com.surenjanath.crownfoundry.ui.components.board.MoveTree
import com.surenjanath.crownfoundry.ui.components.board.TapResult
import com.surenjanath.crownfoundry.ui.components.board.resolveTap

enum class GamePhase {
    /** Nothing asked of the referee yet. */
    Idle,

    /** Starting or resuming a match. */
    Loading,

    /** The human's move is the only thing the screen is waiting for. */
    HumanTurn,

    /** A move is with the referee. */
    Submitting,

    /** The opponent is deciding - this is the one that can take a minute and a half. */
    Thinking,

    /** The opponent's turn failed. The position stands; a retry will resume the loop. */
    Stalled,

    Over
}

enum class RetryAction { None, Begin, AiTurn }

/**
 * Who is on the other side of the board.
 *
 * One thing differs: in [PassAndPlay] no opponent turn is ever requested, so the board simply stays
 * live for whoever is to move. The board itself is drawn the same way in both, from Black's side,
 * the way a board on a table stays put while the players take turns reaching across it.
 */
enum class GameMode {
    VersusEngine,
    PassAndPlay;

    val isPassAndPlay get() = this == PassAndPlay
}

@Immutable
data class GameFailure(val error: ApiError, val retry: RetryAction) {
    val canRetry get() = retry != RetryAction.None
}

@Immutable
data class PieceCounts(
    val blackMen: Int = 0,
    val blackKings: Int = 0,
    val whiteMen: Int = 0,
    val whiteKings: Int = 0
) {
    val black get() = blackMen + blackKings
    val white get() = whiteMen + whiteKings

    companion object {
        fun of(pieces: List<PieceDto>) = PieceCounts(
            blackMen = pieces.count { it.isBlack && !it.king },
            blackKings = pieces.count { it.isBlack && it.king },
            whiteMen = pieces.count { it.isWhite && !it.king },
            whiteKings = pieces.count { it.isWhite && it.king }
        )
    }
}

/**
 * The whole game, as a piece of observable state.
 *
 * No ViewModel: the screen creates one of these, drives it from `LaunchedEffect`s, and the class
 * itself knows nothing about Compose beyond `mutableStateOf` - which is what makes the turn machine
 * testable on the JVM against a fake [CheckersApi].
 *
 * The referee is the only authority. Nothing is ever applied optimistically, and when a move comes
 * back rejected the server's `legal_moves` replace whatever the board thought it knew.
 */
@Stable
class GameState(
    private val api: CheckersApi,
    val difficulty: String = "adaptive",
    private val playerId: String? = null,
    val rules: MatchRulesDto? = null,
    val mode: GameMode = GameMode.VersusEngine,
    private val onMatchIdChanged: (String?) -> Unit = {}
) {
    var matchId by mutableStateOf<String?>(null)
        private set

    var pieces by mutableStateOf<List<PieceDto>>(emptyList())
        private set

    var fen by mutableStateOf("")
        private set

    var sideToMove by mutableStateOf(Side.BLACK)
        private set

    var legalMoves by mutableStateOf<List<MoveDto>>(emptyList())
        private set

    var turnNumber by mutableStateOf(0)
        private set

    var selection by mutableStateOf<BoardSelection?>(null)
        private set

    var animation by mutableStateOf<BoardAnimation?>(null)
        private set

    var lastMove by mutableStateOf<BoardTrace?>(null)
        private set

    var reasoning by mutableStateOf<String?>(null)
        private set

    var spokeThroughOllama by mutableStateOf(false)
        private set

    /** The notation the opponent actually played, so the candidate list can point at it. */
    var lastAiMove by mutableStateOf<String?>(null)
        private set

    var evaluation by mutableStateOf<EvaluationDto?>(null)
        private set

    var aiStatus by mutableStateOf(AiStatusDto())
        private set

    var phase by mutableStateOf(GamePhase.Idle)
        private set

    var winner by mutableStateOf<String?>(null)
        private set

    var failure by mutableStateOf<GameFailure?>(null)
        private set

    var aiTurns by mutableStateOf(0)
        private set

    val counts: PieceCounts get() = PieceCounts.of(pieces)

    val isOver get() = phase == GamePhase.Over

    val isBusy get() = phase == GamePhase.Loading ||
            phase == GamePhase.Submitting ||
            phase == GamePhase.Thinking

    /**
     * The board still answers to a finger only while the human genuinely has the move - and in
     * pass-and-play whoever has the move is a human, so the only question is the phase.
     */
    val acceptsTaps get() = phase == GamePhase.HumanTurn &&
            (mode.isPassAndPlay || sideToMove == Side.HUMAN)

    val mustCapture get() = MoveTree.capturesPending(legalMoves)

    val humanWon get() = winner == Side.HUMAN
    val aiWon get() = winner == Side.AI
    val isDraw get() = winner == Side.DRAW

    private var animationId = 0L

    // --- the turn machine ------------------------------------------------------------------------

    /** Resumes [existingMatchId], or starts a fresh match when it is null. */
    suspend fun begin(existingMatchId: String?) {
        phase = GamePhase.Loading
        failure = null

        val outcome =
            if (existingMatchId != null) api.match(existingMatchId)
            else api.startMatch(difficulty, playerId, rules)

        when (outcome) {
            is Outcome.Success -> {
                val match = outcome.value

                matchId = match.matchId
                onMatchIdChanged(match.matchId)
                aiStatus = match.ai
                turnNumber = match.turnNumber
                selection = null
                animation = null
                lastMove = null
                reasoning = match.history.lastOrNull { it.side == Side.AI }?.reasoning
                evaluation = null
                adopt(match.board, match.legalMoves)

                if (match.isFinished) {
                    finish(match.winner)
                } else {
                    phase = GamePhase.HumanTurn
                    // A resumed match can be sitting on the opponent's move - unless there is no
                    // opponent, in which case it is simply the other player's go.
                    if (sideToMove == Side.AI && !mode.isPassAndPlay) aiTurn()
                }
            }

            is Outcome.Failure -> {
                phase = GamePhase.Idle
                failure = GameFailure(outcome.reason, RetryAction.Begin)
            }
        }
    }

    /** Sends one canonical move - `11-15`, `11x18x25` - and chains straight into the AI's reply. */
    suspend fun play(notation: String) {
        val id = matchId ?: return
        if (phase == GamePhase.Over) return

        val previousPieces = pieces
        val previousMoves = legalMoves
        val previousFen = fen
        val previousSide = sideToMove

        phase = GamePhase.Submitting
        selection = null
        failure = null

        when (val outcome = api.playMove(id, notation)) {
            is Outcome.Success -> {
                val result = outcome.value

                turnNumber = result.turnNumber
                showMove(
                    // In pass-and-play the person who just moved may have been White.
                    notation = result.appliedMove.notation.ifEmpty { notation },
                    captures = result.appliedMove.captures,
                    crowned = result.appliedMove.crowned,
                    side = previousSide,
                    before = previousPieces
                )
                adopt(result.board, result.legalMoves)

                if (result.gameOver) finish(result.winner) else {
                    phase = GamePhase.HumanTurn
                    // Nobody to ask in pass-and-play: the board is simply the other player's now.
                    if (!mode.isPassAndPlay) aiTurn()
                }
            }

            is Outcome.Failure -> {
                // The referee is the rule book: put the position back and take its word for what
                // is legal from here, whatever the board had cached.
                pieces = previousPieces
                fen = previousFen
                sideToMove = previousSide

                val reason = outcome.reason
                legalMoves =
                    if (reason is ApiError.IllegalMove) reason.legalMoves else previousMoves

                failure = GameFailure(reason, RetryAction.None)
                phase = GamePhase.HumanTurn
            }
        }
    }

    /** The opponent's turn. Slow by design: Ollama is generating prose behind this call. */
    suspend fun aiTurn() {
        val id = matchId ?: return
        if (phase == GamePhase.Over) return

        val previousPieces = pieces

        phase = GamePhase.Thinking
        failure = null

        when (val outcome = api.generateAiTurn(id)) {
            is Outcome.Success -> {
                val turn = outcome.value

                aiTurns += 1
                turnNumber = turn.turnNumber
                reasoning = turn.aiReasoning.ifBlank { null }
                lastAiMove = turn.aiMove.ifBlank { null }
                spokeThroughOllama = turn.spokeThroughOllama
                evaluation = turn.evaluation

                showMove(
                    notation = turn.aiMove,
                    captures = turn.captures,
                    crowned = turn.crowned,
                    side = Side.AI,
                    before = previousPieces
                )
                adopt(turn.board, turn.legalMoves)

                if (turn.gameOver) finish(turn.winner) else phase = GamePhase.HumanTurn
            }

            is Outcome.Failure -> {
                failure = GameFailure(outcome.reason, RetryAction.AiTurn)
                phase = GamePhase.Stalled
            }
        }
    }

    suspend fun retry() {
        when (failure?.retry) {
            RetryAction.Begin -> begin(matchId)
            RetryAction.AiTurn -> {
                phase = GamePhase.HumanTurn
                aiTurn()
            }

            else -> Unit
        }
    }

    suspend fun resign() {
        val id = matchId ?: return
        if (phase == GamePhase.Over) return

        when (val outcome = api.resign(id)) {
            is Outcome.Success -> finish(outcome.value.winner ?: Side.AI)
            is Outcome.Failure -> failure = GameFailure(outcome.reason, RetryAction.None)
        }
    }

    suspend fun rematch() {
        matchId = null
        winner = null
        reasoning = null
        lastAiMove = null
        evaluation = null
        aiTurns = 0
        phase = GamePhase.Idle
        begin(null)
    }

    fun dismissFailure() {
        failure = null
    }

    fun clearAnimation() {
        animation = null
    }

    // --- input -----------------------------------------------------------------------------------

    fun tap(square: Int): TapResult {
        if (!acceptsTaps) return TapResult.Ignored

        val result = resolveTap(legalMoves, selection, square)

        selection = when (result) {
            is TapResult.Selected -> result.selection
            is TapResult.Advanced -> result.selection
            is TapResult.Cleared, is TapResult.Ready -> null
            is TapResult.Ignored -> selection
        }

        return result
    }

    // --- internals -------------------------------------------------------------------------------

    private fun adopt(board: BoardDto, moves: List<MoveDto>) {
        pieces = board.pieces
        fen = board.fen
        sideToMove = board.sideToMove
        legalMoves = moves
        selection = null
    }

    private fun showMove(
        notation: String,
        captures: List<Int>,
        crowned: Boolean,
        side: String,
        before: List<PieceDto>
    ) {
        val taken = captures.mapNotNull { square -> before.firstOrNull { it.square == square } }

        animation = BoardAnimation.of(
            id = ++animationId,
            notation = notation,
            captured = taken,
            crowned = crowned,
            side = side
        )
        lastMove = animation?.let { BoardTrace(it.origin, it.destination) }
    }

    private fun finish(result: String?) {
        winner = result
        selection = null
        legalMoves = emptyList()
        phase = GamePhase.Over
        onMatchIdChanged(null)
    }
}
