package com.surenjanath.crownfoundry.ui.screens.matches

import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.api.Side

/**
 * The PDN-style position string the referee stores against every ply, read back into pieces.
 *
 * `B:W21,K22,23:B1,2,3` - side to move, then White's squares, then Black's, kings prefixed `K`.
 * The review screen has only these strings to work from: history entries carry a FEN but not the
 * `pieces` array a live board response carries, so the board is reconstructed here.
 *
 * Nothing in this file throws. A string the referee never meant to send reads as `null` and the
 * screen says so, rather than taking the app down mid-replay.
 */
data class Position(
    val sideToMove: String,
    val pieces: List<PieceDto>
)

object Fen {

    /** Black to move, twelve men each, the way every match starts. */
    const val OPENING =
        "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"

    const val SQUARES = 32
    const val SIZE = 8

    /**
     * Squares are numbered row-major from the top left **as Black sees it**: row 0 is `1,2,3,4`,
     * row 7 is `29,30,31,32`. Only the dark squares are playable, and which columns those are
     * alternates by row.
     */
    fun rowOf(square: Int): Int = (square - 1) / 4

    fun colOf(square: Int): Int {
        val row = rowOf(square)
        val index = (square - 1) % 4
        return 2 * index + if (row % 2 == 1) 0 else 1
    }

    /** The inverse of [rowOf]/[colOf]; `null` for the light squares, which hold nothing. */
    fun squareAt(row: Int, col: Int): Int? {
        if (row !in 0 until SIZE || col !in 0 until SIZE) return null

        val expectedParity = if (row % 2 == 1) 0 else 1
        if (col % 2 != expectedParity) return null

        return row * 4 + (col - expectedParity) / 2 + 1
    }

    /**
     * The squares a move touches, read out of its notation - `11-15`, `11x18`, `11x18x25`. Used
     * to ring the move on the review board; anything unreadable simply rings nothing.
     */
    fun squaresOfMove(notation: String?): List<Int> {
        val text = notation?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()

        return text
            .split('-', 'x', 'X')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..SQUARES }
    }

    fun parse(fen: String?): Position? {
        val text = fen?.trim().orEmpty()
        if (text.isEmpty()) return null

        val parts = text.split(':')
        if (parts.size != 3) return null

        val sideToMove = when (parts[0].trim().uppercase()) {
            "B" -> Side.BLACK
            "W" -> Side.WHITE
            else -> return null
        }

        // The contract writes White first, but accepting either order costs one branch and makes
        // the parser survive a backend that reorders them.
        val sections = parts.drop(1).map { it.trim() }
        val whiteSection = sections.firstOrNull { it.startsWith("W", ignoreCase = true) }
        val blackSection = sections.firstOrNull { it.startsWith("B", ignoreCase = true) }

        if (whiteSection == null || blackSection == null || whiteSection === blackSection) {
            return null
        }

        val white = parseSection(whiteSection, Side.WHITE) ?: return null
        val black = parseSection(blackSection, Side.BLACK) ?: return null

        val pieces = (white + black).sortedBy { it.square }
        if (pieces.map { it.square }.toSet().size != pieces.size) return null

        return Position(sideToMove = sideToMove, pieces = pieces)
    }

    /** [parse], but an unreadable string gives an empty board instead of a null to unwrap. */
    fun parseOrEmpty(fen: String?): Position =
        parse(fen) ?: Position(sideToMove = Side.BLACK, pieces = emptyList())

    fun render(position: Position): String {
        val side = if (position.sideToMove == Side.WHITE) "W" else "B"

        return buildString {
            append(side)
            append(":W")
            append(renderSection(position.pieces.filter { it.isWhite }))
            append(":B")
            append(renderSection(position.pieces.filter { it.isBlack }))
        }
    }

    private fun renderSection(pieces: List<PieceDto>) = pieces
        .sortedBy { it.square }
        .joinToString(",") { (if (it.king) "K" else "") + it.square }

    private fun parseSection(section: String, side: String): List<PieceDto>? {
        val body = section.substring(1).trim()
        if (body.isEmpty()) return emptyList()

        val pieces = mutableListOf<PieceDto>()

        for (raw in body.split(',')) {
            val token = raw.trim()
            if (token.isEmpty()) return null

            val king = token[0] == 'K' || token[0] == 'k'
            val digits = if (king) token.substring(1) else token
            if (digits.isEmpty() || digits.any { !it.isDigit() }) return null

            val square = digits.toIntOrNull() ?: return null
            if (square !in 1..SQUARES) return null

            pieces += PieceDto(square = square, side = side, king = king)
        }

        return pieces
    }
}
