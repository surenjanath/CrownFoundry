package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.BoardDto
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.MoveDto
import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.BLACK
import com.surenjanath.crownfoundry.engine.Board
import com.surenjanath.crownfoundry.engine.DRAW_RESULT
import com.surenjanath.crownfoundry.engine.EMPTY
import com.surenjanath.crownfoundry.engine.Move
import com.surenjanath.crownfoundry.engine.SQUARE_COUNT
import com.surenjanath.crownfoundry.engine.VariantRules
import com.surenjanath.crownfoundry.engine.WHITE
import com.surenjanath.crownfoundry.engine.isKing
import com.surenjanath.crownfoundry.engine.sideOfPiece

/**
 * The engine's types in the wire's terms.
 *
 * The offline referee has to be indistinguishable from the server one at the [BoardDto] boundary,
 * because the whole board UI, the animations and the turn machine are written against the server's
 * payloads and none of them are being touched. Every mapping here is checked against a real
 * response in `OfflineApiTest`.
 *
 * The engine speaks ints for sides and the API speaks strings; the two are kept apart rather than
 * unified, so a change to either vocabulary shows up as a compile error in exactly this file.
 */

internal fun sideName(side: Int): String = if (side == BLACK) Side.BLACK else Side.WHITE

internal fun sideCode(name: String?): Int? = when (name?.trim()?.lowercase()) {
    Side.BLACK -> BLACK
    Side.WHITE -> WHITE
    else -> null
}

internal fun winnerName(result: Int?): String? = when (result) {
    null -> null
    DRAW_RESULT -> Side.DRAW
    BLACK -> Side.BLACK
    else -> Side.WHITE
}

internal fun MatchRulesDto?.toEngineRules(): VariantRules =
    if (this == null) VariantRules.DEFAULT
    else VariantRules(
        flyingKings = flyingKings,
        menCaptureBackwards = menCaptureBackwards,
        mandatoryCapture = mandatoryCapture
    )

internal fun VariantRules.toDto() = MatchRulesDto(
    flyingKings = flyingKings,
    menCaptureBackwards = menCaptureBackwards,
    mandatoryCapture = mandatoryCapture
)

internal fun Board.toDto() = BoardDto(
    fen = toFen(),
    sideToMove = sideName(sideToMove),
    pieces = buildList {
        for (square in 1..SQUARE_COUNT) {
            val code = pieceAt(square)
            if (code == EMPTY) continue
            add(PieceDto(square = square, side = sideName(sideOfPiece(code)), king = isKing(code)))
        }
    }
)

internal fun Move.toDto() = MoveDto(
    notation = notation(),
    from = origin,
    to = destination,
    captures = captures.toList(),
    crowned = crowned
)

internal fun List<Move>.toDtos(): List<MoveDto> = map { it.toDto() }
