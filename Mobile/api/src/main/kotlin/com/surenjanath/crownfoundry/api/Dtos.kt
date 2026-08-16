package com.surenjanath.crownfoundry.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire format of the Django referee, one class per payload in ARCHITECTURE.md §5.
 *
 * Every field the backend may omit is nullable or defaulted, so a backend running slightly ahead
 * of - or behind - the app degrades instead of throwing.
 */

@Serializable
data class PieceDto(
    val square: Int,
    val side: String,
    val king: Boolean = false
) {
    val isBlack get() = side == Side.BLACK
    val isWhite get() = side == Side.WHITE
}

object Side {
    const val BLACK = "black"
    const val WHITE = "white"
    const val DRAW = "draw"

    /** The human always plays Black, the AI always plays White. */
    const val HUMAN = BLACK
    const val AI = WHITE
}

@Serializable
data class BoardDto(
    val fen: String = "",
    @SerialName("side_to_move") val sideToMove: String = Side.BLACK,
    val pieces: List<PieceDto> = emptyList()
)

@Serializable
data class MoveDto(
    val notation: String,
    val from: Int,
    val to: Int,
    val captures: List<Int> = emptyList(),
    val crowned: Boolean = false
) {
    val isJump get() = captures.isNotEmpty()
}

@Serializable
data class AppliedMoveDto(
    val notation: String = "",
    val captures: List<Int> = emptyList(),
    val crowned: Boolean = false
)

@Serializable
data class AiStatusDto(
    @SerialName("policy_version") val policyVersion: Int = 0,
    @SerialName("games_trained") val gamesTrained: Int = 0,
    @SerialName("win_rate") val winRate: Double = 0.0,
    val elo: Int = 1200
)

@Serializable
data class ScoredMoveDto(
    val notation: String,
    val q: Double
)

@Serializable
data class EvaluationDto(
    @SerialName("q_value") val qValue: Double = 0.0,
    val confidence: Double = 0.0,
    val considered: List<ScoredMoveDto> = emptyList()
)

@Serializable
data class HistoryEntryDto(
    val turn: Int,
    val side: String,
    val move: String,
    val fen: String = "",
    val reasoning: String? = null
)

@Serializable
data class OllamaStatusDto(
    val available: Boolean = false,
    val model: String = ""
)

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    val version: String = "",
    val ollama: OllamaStatusDto = OllamaStatusDto(),
    @SerialName("policy_version") val policyVersion: Int = 0
)

@Serializable
data class MatchRulesDto(
    @SerialName("flying_kings") val flyingKings: Boolean = true,
    @SerialName("men_capture_backwards") val menCaptureBackwards: Boolean = true,
    @SerialName("mandatory_capture") val mandatoryCapture: Boolean = true
)

@Serializable
data class MatchDto(
    val ok: Boolean = true,
    @SerialName("match_id") val matchId: String = "",
    @SerialName("initial_board") val initialBoard: String? = null,
    val board: BoardDto = BoardDto(),
    @SerialName("legal_moves") val legalMoves: List<MoveDto> = emptyList(),
    @SerialName("turn_number") val turnNumber: Int = 0,
    val status: String = "active",
    val winner: String? = null,
    val difficulty: String = "adaptive",
    val rules: MatchRulesDto? = null,
    val history: List<HistoryEntryDto> = emptyList(),
    val ai: AiStatusDto = AiStatusDto()
) {
    val isFinished get() = status != "active"
}

@Serializable
data class MoveResultDto(
    val ok: Boolean = true,
    val valid: Boolean = true,
    @SerialName("game_over") val gameOver: Boolean = false,
    val winner: String? = null,
    @SerialName("board_state") val boardState: String = "",
    val board: BoardDto = BoardDto(),
    @SerialName("legal_moves") val legalMoves: List<MoveDto> = emptyList(),
    @SerialName("applied_move") val appliedMove: AppliedMoveDto = AppliedMoveDto(),
    @SerialName("turn_number") val turnNumber: Int = 0
)

@Serializable
data class AiTurnDto(
    val ok: Boolean = true,
    @SerialName("ai_move") val aiMove: String = "",
    @SerialName("ai_reasoning") val aiReasoning: String = "",
    @SerialName("reasoning_source") val reasoningSource: String = "heuristic",
    @SerialName("new_board") val newBoard: String = "",
    val board: BoardDto = BoardDto(),
    @SerialName("legal_moves") val legalMoves: List<MoveDto> = emptyList(),
    val evaluation: EvaluationDto = EvaluationDto(),
    @SerialName("game_over") val gameOver: Boolean = false,
    val winner: String? = null,
    @SerialName("turn_number") val turnNumber: Int = 0,
    val captures: List<Int> = emptyList(),
    val crowned: Boolean = false
) {
    val spokeThroughOllama get() = reasoningSource == "ollama"
}

@Serializable
data class ResignDto(
    val ok: Boolean = true,
    @SerialName("game_over") val gameOver: Boolean = true,
    val winner: String? = null
)

@Serializable
data class MatchSummaryDto(
    @SerialName("match_id") val matchId: String,
    @SerialName("start_time") val startTime: String = "",
    @SerialName("end_time") val endTime: String? = null,
    val status: String = "active",
    val winner: String? = null,
    @SerialName("total_turns") val totalTurns: Int = 0,
    val difficulty: String = "adaptive",
    @SerialName("ai_captures") val aiCaptures: Int = 0,
    @SerialName("human_captures") val humanCaptures: Int = 0
)

@Serializable
data class MatchListDto(
    val ok: Boolean = true,
    val matches: List<MatchSummaryDto> = emptyList()
)

// --- analytics ------------------------------------------------------------------------------

@Serializable
data class AnalyticsSummaryDto(
    @SerialName("total_matches") val totalMatches: Int = 0,
    @SerialName("ai_wins") val aiWins: Int = 0,
    @SerialName("human_wins") val humanWins: Int = 0,
    val draws: Int = 0,
    @SerialName("ai_win_rate") val aiWinRate: Double = 0.0,
    val elo: Int = 1200,
    @SerialName("policy_version") val policyVersion: Int = 0,
    @SerialName("games_to_50_percent") val gamesTo50Percent: Int? = null,
    @SerialName("avg_turns") val avgTurns: Double = 0.0,
    @SerialName("mistake_repetition_rate") val mistakeRepetitionRate: Double = 0.0,
    @SerialName("capture_ratio") val captureRatio: Double = 0.0
)

@Serializable
data class WinRatePointDto(
    @SerialName("match_index") val matchIndex: Int,
    @SerialName("cumulative_win_rate") val cumulativeWinRate: Double = 0.0,
    @SerialName("rolling_win_rate") val rollingWinRate: Double = 0.0,
    val result: String = ""
)

@Serializable
data class GameLengthPointDto(
    @SerialName("match_index") val matchIndex: Int,
    val turns: Int = 0
)

@Serializable
data class MistakePointDto(
    @SerialName("match_index") val matchIndex: Int,
    @SerialName("repeated_mistakes") val repeatedMistakes: Int = 0,
    val rate: Double = 0.0
)

@Serializable
data class CapturePointDto(
    @SerialName("match_index") val matchIndex: Int,
    @SerialName("ai_captures") val aiCaptures: Int = 0,
    @SerialName("human_captures") val humanCaptures: Int = 0
)

@Serializable
data class TrainingPointDto(
    @SerialName("policy_version") val policyVersion: Int = 0,
    val loss: Double = 0.0,
    @SerialName("games_trained") val gamesTrained: Int = 0,
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
data class PerformanceDto(
    val ok: Boolean = true,
    val summary: AnalyticsSummaryDto = AnalyticsSummaryDto(),
    @SerialName("win_rate_series") val winRateSeries: List<WinRatePointDto> = emptyList(),
    @SerialName("game_length_series") val gameLengthSeries: List<GameLengthPointDto> = emptyList(),
    @SerialName("mistake_series") val mistakeSeries: List<MistakePointDto> = emptyList(),
    @SerialName("capture_series") val captureSeries: List<CapturePointDto> = emptyList(),
    val training: List<TrainingPointDto> = emptyList()
)

@Serializable
data class ErrorDto(
    val ok: Boolean = false,
    val error: String = "unknown",
    val detail: String = "",
    @SerialName("legal_moves") val legalMoves: List<MoveDto> = emptyList()
)
