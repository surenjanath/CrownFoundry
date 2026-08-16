package com.surenjanath.crownfoundry.game

import com.surenjanath.crownfoundry.api.AiTurnDto
import com.surenjanath.crownfoundry.api.AppliedMoveDto
import com.surenjanath.crownfoundry.api.BoardDto
import com.surenjanath.crownfoundry.api.EvaluationDto
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.MoveDto
import com.surenjanath.crownfoundry.api.MoveResultDto
import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.api.ScoredMoveDto
import com.surenjanath.crownfoundry.api.Side

/**
 * The positions and move lists the tests are written against, in exactly the shape the Django
 * referee sends them.
 */
object Fixtures {
    const val INITIAL_FEN =
        "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"

    val initialPieces: List<PieceDto> =
        (1..12).map { PieceDto(square = it, side = Side.BLACK) } +
                (21..32).map { PieceDto(square = it, side = Side.WHITE) }

    val initialBoard = BoardDto(
        fen = INITIAL_FEN,
        sideToMove = Side.BLACK,
        pieces = initialPieces
    )

    /** The seven openings English draughts allows Black. */
    val openingMoves = listOf(
        move("9-13"), move("9-14"),
        move("10-14"), move("10-15"),
        move("11-15"), move("11-16"),
        move("12-16")
    )

    /**
     * A position where 11 has two double jumps that share their first hop, and 10 can take as
     * well - so both the multi-jump narrowing and the compulsory-capture rule have something to
     * bite on. A jump must be played out, so `11x18` is deliberately absent.
     */
    val captureMoves = listOf(
        MoveDto(notation = "11x18x25", from = 11, to = 25, captures = listOf(15, 22)),
        MoveDto(notation = "11x18x27", from = 11, to = 27, captures = listOf(15, 23)),
        MoveDto(notation = "10x17", from = 10, to = 17, captures = listOf(14))
    )

    val initialMatch = MatchDto(
        matchId = MATCH_ID,
        initialBoard = INITIAL_FEN,
        board = initialBoard,
        legalMoves = openingMoves,
        turnNumber = 0
    )

    fun move(notation: String): MoveDto {
        val parts = notation.split('-', 'x').map(String::toInt)
        return MoveDto(notation = notation, from = parts.first(), to = parts.last())
    }

    /** The position after Black plays 11-15, as the referee would describe it. */
    fun afterHumanOpening(notation: String = "11-15"): MoveResultDto {
        val to = notation.split('-', 'x').last().toInt()
        val from = notation.split('-', 'x').first().toInt()

        return MoveResultDto(
            gameOver = false,
            boardState = "W:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,12,$to",
            board = BoardDto(
                fen = "W:W21,...:B...,$to",
                sideToMove = Side.WHITE,
                pieces = initialPieces.map {
                    if (it.square == from) it.copy(square = to) else it
                }
            ),
            legalMoves = listOf(move("22-17"), move("23-18")),
            appliedMove = AppliedMoveDto(notation = notation),
            turnNumber = 1
        )
    }

    fun aiReply(
        notation: String = "23-18",
        gameOver: Boolean = false,
        winner: String? = null,
        source: String = "ollama"
    ) = AiTurnDto(
        aiMove = notation,
        aiReasoning = "Holding the centre so your right flank has nothing to trade into.",
        reasoningSource = source,
        newBoard = INITIAL_FEN,
        board = BoardDto(
            fen = "B:W21,...:B...",
            sideToMove = Side.BLACK,
            pieces = initialPieces
        ),
        legalMoves = openingMoves,
        evaluation = EvaluationDto(
            qValue = 0.41,
            confidence = 0.78,
            considered = listOf(
                ScoredMoveDto("23-18", 0.41),
                ScoredMoveDto("22-17", 0.36)
            )
        ),
        gameOver = gameOver,
        winner = winner,
        turnNumber = 2
    )

    const val MATCH_ID = "6f3a1c2e-0000-4000-8000-000000000001"
}
