package com.surenjanath.crownfoundry.ui.screens.matches

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance

/**
 * The board as it stood, drawn from a parsed FEN and nothing else - no taps, no legal moves, no
 * knowledge of the live game. The playing board lives in another package and this screen must not
 * reach into it, so the review has its own small renderer.
 *
 * The human plays Black and Black is drawn at the bottom, which is a half-turn of the board:
 * both the row and the column are mirrored. Mirroring only the rows would land every piece on a
 * light square.
 */
@Composable
fun ReviewBoard(
    position: Position,
    modifier: Modifier = Modifier,
    highlightedSquares: List<Int> = emptyList()
) {
    val (colorPalette) = LocalAppearance.current

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val side = size.minDimension / Fen.SIZE

        for (row in 0 until Fen.SIZE) {
            for (col in 0 until Fen.SIZE) {
                val playable = (row + col) % 2 == 1
                val (x, y) = flipped(row, col, side)

                drawRect(
                    color = if (playable) colorPalette.background2 else colorPalette.background1,
                    topLeft = Offset(x, y),
                    size = Size(side, side)
                )
            }
        }

        highlightedSquares.forEach { square ->
            if (square !in 1..Fen.SQUARES) return@forEach

            val (x, y) = flipped(Fen.rowOf(square), Fen.colOf(square), side)

            drawRect(
                color = colorPalette.accent.copy(alpha = 0.25f),
                topLeft = Offset(x, y),
                size = Size(side, side)
            )
        }

        position.pieces.forEach { piece ->
            if (piece.square !in 1..Fen.SQUARES) return@forEach

            val (x, y) = flipped(Fen.rowOf(piece.square), Fen.colOf(piece.square), side)

            drawPiece(
                piece = piece,
                centre = Offset(x + side / 2f, y + side / 2f),
                squareSize = side,
                // The AI is White and takes the text colour; the human is Black and takes the
                // accent - the same division the live board uses.
                color = if (piece.isWhite) colorPalette.text else colorPalette.accent,
                crownColor = colorPalette.background0
            )
        }
    }
}

/** Board coordinates to screen pixels, through the half-turn that puts Black at the bottom. */
private fun flipped(row: Int, col: Int, side: Float): Pair<Float, Float> =
    (Fen.SIZE - 1 - col) * side to (Fen.SIZE - 1 - row) * side

private fun DrawScope.drawPiece(
    piece: PieceDto,
    centre: Offset,
    squareSize: Float,
    color: Color,
    crownColor: Color
) {
    val radius = squareSize * 0.36f

    drawCircle(color = color, radius = radius, center = centre)

    if (piece.king) {
        // A king is the same piece with a ring cut out of it - readable at thumbnail size, which
        // a drawn crown would not be.
        drawCircle(
            color = crownColor,
            radius = radius * 0.45f,
            center = centre,
            style = Stroke(width = (squareSize * 0.07f).coerceAtLeast(1.dp.toPx()))
        )
    }
}
