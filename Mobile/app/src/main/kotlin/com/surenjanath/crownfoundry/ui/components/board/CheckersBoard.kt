package com.surenjanath.crownfoundry.ui.components.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.MoveDto
import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

const val CheckersBoardTag = "checkersBoard"

val BoardPieceCount = SemanticsPropertyKey<Int>("BoardPieceCount")
val BoardSelectedSquare = SemanticsPropertyKey<Int>("BoardSelectedSquare")
val BoardHintSquares = SemanticsPropertyKey<List<Int>>("BoardHintSquares")
val BoardSelectableSquares = SemanticsPropertyKey<List<Int>>("BoardSelectableSquares")

private var SemanticsPropertyReceiver.boardPieceCount by BoardPieceCount
private var SemanticsPropertyReceiver.boardSelectedSquare by BoardSelectedSquare
private var SemanticsPropertyReceiver.boardHintSquares by BoardHintSquares
private var SemanticsPropertyReceiver.boardSelectableSquares by BoardSelectableSquares

/** Where the centre of [square] falls on a board of [boardSizePx] pixels a side, already flipped. */
fun squareCenter(square: Int, boardSizePx: Float, fromBlack: Boolean = true): Offset {
    val cell = boardSizePx / Squares.SIDE
    return Offset(
        x = (Squares.renderColOf(square, fromBlack) + 0.5f) * cell,
        y = (Squares.renderRowOf(square, fromBlack) + 0.5f) * cell
    )
}

/** The square under a touch at [offset] on a board of [boardSizePx] a side, or 0 for none. */
fun squareAtOffset(offset: Offset, boardSizePx: Float, fromBlack: Boolean = true): Int {
    if (boardSizePx <= 0f) return 0
    val cell = boardSizePx / Squares.SIDE
    return Squares.squareAtRendered(
        renderRow = floor(offset.y / cell).toInt(),
        renderCol = floor(offset.x / cell).toInt(),
        fromBlack = fromBlack
    )
}

/**
 * The board. Draws a position, the hints for the side to move, and the move that was just played;
 * emits the square under every tap and nothing else - what a tap *means* is [BoardSelection]'s job.
 *
 * [pieces] is always the position as the referee last described it. While [animation] is set the
 * mover is drawn along its path instead of at rest, and the pieces it takes - which the referee has
 * already deleted - are drawn from [BoardAnimation.captured] on their way out.
 */
@Composable
fun CheckersBoard(
    pieces: List<PieceDto>,
    legalMoves: List<MoveDto>,
    modifier: Modifier = Modifier,
    selection: BoardSelection? = null,
    animation: BoardAnimation? = null,
    lastMove: BoardTrace? = null,
    showHints: Boolean = true,
    enabled: Boolean = true,
    /** False draws the board from White's side, which only pass-and-play ever asks for. */
    fromBlack: Boolean = true,
    onAnimationEnd: () -> Unit = {},
    onSquareTap: (Int) -> Unit = {}
) {
    val (colorPalette) = LocalAppearance.current

    val crown = painterResource(R.drawable.crown)

    val selectable = remember(legalMoves) { MoveTree.selectableSquares(legalMoves) }
    val mustCapture = remember(legalMoves) { MoveTree.capturesPending(legalMoves) }

    val hints = selection?.destinations.orEmpty()
    val threatened = selection?.threatened.orEmpty()

    val slide = remember { Animatable(0f) }
    val flourish = remember { Animatable(0f) }

    val onEnd by rememberUpdatedState(onAnimationEnd)
    val onTap by rememberUpdatedState(onSquareTap)

    LaunchedEffect(animation?.id) {
        val move = animation ?: return@LaunchedEffect

        slide.snapTo(0f)
        flourish.snapTo(0f)
        slide.animateTo(
            targetValue = move.hops.toFloat(),
            animationSpec = tween(
                durationMillis = 200 + 160 * move.hops,
                easing = FastOutSlowInEasing
            )
        )
        if (move.crowned) flourish.animateTo(1f, tween(340, easing = LinearOutSlowInEasing))
        onEnd()
    }

    // Colours are hoisted out of the draw scope so the per-frame work is arithmetic only.
    val human = colorPalette.accent
    val opponent = colorPalette.text
    val humanRing = colorPalette.onAccent.copy(alpha = 0.45f)
    val opponentRing = colorPalette.background0.copy(alpha = 0.55f)
    val humanCrown = remember(colorPalette) { ColorFilter.tint(colorPalette.onAccent) }
    val opponentCrown = remember(colorPalette) { ColorFilter.tint(colorPalette.background0) }
    val shadow = Color.Black.copy(alpha = if (colorPalette.isDark) 0.35f else 0.18f)

    val description = remember(pieces, selection, hints) {
        boardDescription(pieces, selection, hints)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(1f)
                .testTag(CheckersBoardTag)
                .semantics {
                    contentDescription = description
                    boardPieceCount = pieces.size
                    boardSelectedSquare = selection?.square ?: 0
                    boardHintSquares = hints.toList()
                    boardSelectableSquares = selectable.toList()
                }
                // Keyed on the orientation too: a stale gesture handler would keep reading taps
                // against the board as it was drawn before it turned round.
                .pointerInput(enabled, fromBlack) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        val square = squareAtOffset(offset, size.width.toFloat(), fromBlack)
                        if (square != 0) onTap(square)
                    }
                }
        ) {
            val cell = size.width / Squares.SIDE
            val radius = cell * 0.36f
            val ring = Stroke(width = cell * 0.035f)
            val selectionRing = Stroke(width = cell * 0.06f)
            val threatMark = Stroke(width = cell * 0.05f, cap = StrokeCap.Round)

            for (row in 0 until Squares.SIDE) {
                for (col in 0 until Squares.SIDE) {
                    drawRect(
                        color = if ((row + col) % 2 == 1) colorPalette.background2
                        else colorPalette.background1,
                        topLeft = Offset(col * cell, row * cell),
                        size = Size(cell, cell)
                    )
                }
            }

            lastMove?.let { trace ->
                val from = squareCenter(trace.from, size.width, fromBlack)
                val to = squareCenter(trace.to, size.width, fromBlack)

                drawLine(
                    color = colorPalette.accent.copy(alpha = 0.16f),
                    start = from,
                    end = to,
                    strokeWidth = cell * 0.14f,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = colorPalette.accent.copy(alpha = 0.16f),
                    radius = radius * 0.5f,
                    center = from
                )
            }

            if (showHints && selection == null) {
                for (square in selectable) {
                    drawCircle(
                        color = if (mustCapture) colorPalette.accent.copy(alpha = 0.55f)
                        else colorPalette.accent.copy(alpha = 0.22f),
                        radius = radius * 1.16f,
                        center = squareCenter(square, size.width, fromBlack),
                        style = ring
                    )
                }
            }

            selection?.let {
                drawCircle(
                    color = colorPalette.accent,
                    radius = radius * 1.16f,
                    center = squareCenter(it.square, size.width, fromBlack),
                    style = selectionRing
                )
            }

            if (showHints) {
                for (square in hints) {
                    val center = squareCenter(square, size.width, fromBlack)
                    drawCircle(
                        color = colorPalette.accent.copy(alpha = 0.25f),
                        radius = radius * 0.42f,
                        center = center
                    )
                    if (selection?.captureAt(square) != null) {
                        drawCircle(
                            color = colorPalette.accent.copy(alpha = 0.25f),
                            radius = radius * 0.95f,
                            center = center,
                            style = ring
                        )
                    }
                }

                for (square in threatened) {
                    val center = squareCenter(square, size.width, fromBlack)
                    val arm = radius * 0.62f
                    drawLine(
                        color = colorPalette.red.copy(alpha = 0.75f),
                        start = Offset(center.x - arm, center.y - arm),
                        end = Offset(center.x + arm, center.y + arm),
                        strokeWidth = threatMark.width,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = colorPalette.red.copy(alpha = 0.75f),
                        start = Offset(center.x + arm, center.y - arm),
                        end = Offset(center.x - arm, center.y + arm),
                        strokeWidth = threatMark.width,
                        cap = StrokeCap.Round
                    )
                }
            }

            val moverSquare = animation?.destination ?: 0

            for (piece in pieces) {
                if (!Squares.isSquare(piece.square)) continue
                if (piece.square == moverSquare) continue

                drawPiece(
                    center = squareCenter(piece.square, size.width, fromBlack),
                    radius = radius,
                    fill = if (piece.isBlack) human else opponent,
                    ringColor = if (piece.isBlack) humanRing else opponentRing,
                    shadowColor = shadow,
                    ring = ring,
                    king = piece.king,
                    crown = crown,
                    crownTint = if (piece.isBlack) humanCrown else opponentCrown,
                    alpha = 1f,
                    scale = 1f
                )
            }

            animation?.let { move ->
                val progress = slide.value

                move.captured.forEachIndexed { index, piece ->
                    val life = ((index + 1) - progress).coerceIn(0f, 1f)
                    if (life <= 0f) return@forEachIndexed

                    drawPiece(
                        center = squareCenter(piece.square, size.width, fromBlack),
                        radius = radius,
                        fill = if (piece.isBlack) human else opponent,
                        ringColor = if (piece.isBlack) humanRing else opponentRing,
                        shadowColor = shadow,
                        ring = ring,
                        king = piece.king,
                        crown = crown,
                        crownTint = if (piece.isBlack) humanCrown else opponentCrown,
                        alpha = life,
                        scale = 0.55f + 0.45f * life
                    )
                }

                val hop = floor(progress).toInt().coerceIn(0, move.hops - 1)
                val t = (progress - hop).coerceIn(0f, 1f)
                val from = squareCenter(move.from(hop), size.width, fromBlack)
                val to = squareCenter(move.to(hop), size.width, fromBlack)
                val lift = if (move.captured.isNotEmpty()) sin(t * PI).toFloat() * cell * 0.18f else 0f

                val resting = pieces.firstOrNull { it.square == move.destination }
                val isHuman = (resting?.side ?: move.side) == Side.HUMAN

                // The crown only appears once the flourish starts, so the promotion reads as an
                // event rather than as a piece that was always a king.
                val wearsCrown = (resting?.king ?: false) && (!move.crowned || flourish.value > 0.35f)
                val pop = if (move.crowned) 1f + 0.18f * sin(flourish.value * PI).toFloat() else 1f

                drawPiece(
                    center = Offset(
                        x = from.x + (to.x - from.x) * t,
                        y = from.y + (to.y - from.y) * t - lift
                    ),
                    radius = radius,
                    fill = if (isHuman) human else opponent,
                    ringColor = if (isHuman) humanRing else opponentRing,
                    shadowColor = shadow,
                    ring = ring,
                    king = wearsCrown,
                    crown = crown,
                    crownTint = if (isHuman) humanCrown else opponentCrown,
                    alpha = 1f,
                    scale = pop
                )
            }
        }
    }
}

private fun DrawScope.drawPiece(
    center: Offset,
    radius: Float,
    fill: Color,
    ringColor: Color,
    shadowColor: Color,
    ring: Stroke,
    king: Boolean,
    crown: Painter,
    crownTint: ColorFilter,
    alpha: Float,
    scale: Float
) {
    val r = radius * scale

    drawCircle(
        color = shadowColor.copy(alpha = shadowColor.alpha * alpha),
        radius = r,
        center = Offset(center.x, center.y + r * 0.10f)
    )
    drawCircle(color = fill.copy(alpha = alpha), radius = r, center = center)
    drawCircle(
        color = ringColor.copy(alpha = ringColor.alpha * alpha),
        radius = r * 0.66f,
        center = center,
        style = ring
    )

    if (!king) return

    val glyph = r * 1.0f
    translate(left = center.x - glyph / 2f, top = center.y - glyph / 2f) {
        with(crown) {
            draw(size = Size(glyph, glyph), alpha = alpha, colorFilter = crownTint)
        }
    }
}

private fun boardDescription(
    pieces: List<PieceDto>,
    selection: BoardSelection?,
    hints: Set<Int>
): String {
    val yours = pieces.count { it.isBlack }
    val theirs = pieces.size - yours

    return buildString {
        append("Checkers board, $yours of your pieces against $theirs")
        selection?.let { append(", square ${it.square} selected") }
        if (hints.isNotEmpty()) append(", can move to ${hints.joinToString(", ")}")
    }
}
