package com.surenjanath.crownfoundry.engine

/**
 * Square geometry and the strings CrownFoundry speaks - the Kotlin half of
 * `Backend/game/engine/notation.py`.
 *
 * English draughts numbers the 32 dark squares `1..32`, row-major from the top-left of the board
 * as Black sees it. Everything below is derived from that one mapping and built into lookup tables
 * at class-load time, because the search walks these tables tens of thousands of times per move.
 *
 * This file is a port, not an interpretation. Where the backend and this disagree the backend is
 * right by definition - it referees every game that reaches the server, including the ones played
 * offline, which get replayed through it on sync. `SquaresTest` pins the tables against the values
 * the Python builds.
 */

const val BOARD_SIZE = 8
const val SQUARE_COUNT = 32

/** Direction indices. "North" is row 0, the edge Black is advancing away from. */
const val NW = 0
const val NE = 1
const val SW = 2
const val SE = 3

val KING_DIRS = intArrayOf(NW, NE, SW, SE)

private val DELTAS = arrayOf(
    intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1)
)

/** Black starts on 1..12 and advances toward row 7; White starts on 21..32 and advances to row 0. */
val BLACK_MAN_DIRS = intArrayOf(SW, SE)
val WHITE_MAN_DIRS = intArrayOf(NW, NE)

fun manDirs(side: Int): IntArray = if (side == BLACK) BLACK_MAN_DIRS else WHITE_MAN_DIRS

/** Sides, as the compact ints the board stores. [Side] carries the wire strings. */
const val BLACK = 0
const val WHITE = 1

fun opponent(side: Int) = 1 - side

object Side {
    const val BLACK = "black"
    const val WHITE = "white"
    const val DRAW = "draw"

    fun of(side: Int) = if (side == com.surenjanath.crownfoundry.engine.BLACK) BLACK else WHITE

    /** `null` for anything that is not a side, so callers can tell "draw" from "malformed". */
    fun parse(name: String?): Int? = when (name?.trim()?.lowercase()) {
        BLACK -> com.surenjanath.crownfoundry.engine.BLACK
        WHITE -> com.surenjanath.crownfoundry.engine.WHITE
        else -> null
    }
}

/** `1..32` to `(row shl 3) or col`. Index 0 is unused so squares can index directly. */
val SQUARE_TO_RC: IntArray = IntArray(SQUARE_COUNT + 1).also { table ->
    for (square in 1..SQUARE_COUNT) {
        val row = (square - 1) / 4
        val idx = (square - 1) % 4
        val col = 2 * idx + if (row % 2 == 1) 0 else 1
        table[square] = (row shl 3) or col
    }
}

fun rowOf(square: Int) = SQUARE_TO_RC[square] shr 3
fun colOf(square: Int) = SQUARE_TO_RC[square] and 7

/** `(row, col)` to `1..32`; `0` for anything off the board or on a light square. */
fun rcToSquare(row: Int, col: Int): Int {
    if (row !in 0 until BOARD_SIZE || col !in 0 until BOARD_SIZE) return 0
    if (col % 2 != (if (row % 2 == 1) 0 else 1)) return 0
    return row * 4 + col / 2 + 1
}

/** `STEPS[square][direction]` -> adjacent square, or `0` when the step leaves the board. */
val STEPS: Array<IntArray> = Array(SQUARE_COUNT + 1) { square ->
    if (square == 0) IntArray(4) else IntArray(4) { dir ->
        rcToSquare(rowOf(square) + DELTAS[dir][0], colOf(square) + DELTAS[dir][1])
    }
}

/** `JUMPED[square][dir]` / `LANDING[square][dir]`, both `0` when that jump leaves the board. */
val JUMPED: Array<IntArray> = Array(SQUARE_COUNT + 1) { square ->
    if (square == 0) IntArray(4) else IntArray(4) { dir ->
        val over = rcToSquare(rowOf(square) + DELTAS[dir][0], colOf(square) + DELTAS[dir][1])
        val land = rcToSquare(rowOf(square) + 2 * DELTAS[dir][0], colOf(square) + 2 * DELTAS[dir][1])
        if (over != 0 && land != 0) over else 0
    }
}

val LANDING: Array<IntArray> = Array(SQUARE_COUNT + 1) { square ->
    if (square == 0) IntArray(4) else IntArray(4) { dir ->
        val over = rcToSquare(rowOf(square) + DELTAS[dir][0], colOf(square) + DELTAS[dir][1])
        val land = rcToSquare(rowOf(square) + 2 * DELTAS[dir][0], colOf(square) + 2 * DELTAS[dir][1])
        if (over != 0 && land != 0) land else 0
    }
}

/** `RAYS[square][direction]` -> squares along the diagonal, nearest first, out to the edge. */
val RAYS: Array<Array<IntArray>> = Array(SQUARE_COUNT + 1) { square ->
    if (square == 0) Array(4) { IntArray(0) } else Array(4) { dir ->
        val ray = ArrayList<Int>(7)
        var r = rowOf(square) + DELTAS[dir][0]
        var c = colOf(square) + DELTAS[dir][1]
        while (r in 0 until BOARD_SIZE && c in 0 until BOARD_SIZE) {
            val sq = rcToSquare(r, c)
            if (sq != 0) ray.add(sq)
            r += DELTAS[dir][0]
            c += DELTAS[dir][1]
        }
        ray.toIntArray()
    }
}

val BLACK_PROMOTION = intArrayOf(29, 30, 31, 32)
val WHITE_PROMOTION = intArrayOf(1, 2, 3, 4)

private val PROMOTION_MASK: Array<BooleanArray> = arrayOf(
    BooleanArray(SQUARE_COUNT + 1).also { for (s in BLACK_PROMOTION) it[s] = true },
    BooleanArray(SQUARE_COUNT + 1).also { for (s in WHITE_PROMOTION) it[s] = true }
)

fun isPromotionSquare(square: Int, side: Int) = PROMOTION_MASK[side][square]

// --- move strings -------------------------------------------------------------------------------

class MalformedMove(message: String) : IllegalArgumentException(message)

/**
 * `"11x18x25"` -> `[11, 18, 25]`.
 *
 * Separators are interchangeable: the sequence of squares is what identifies a move, so `11-18`
 * and `11x18` read the same way and the engine decides which one exists.
 */
fun parseMoveString(text: String?): IntArray {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) throw MalformedMove("empty move string")

    val squares = ArrayList<Int>(4)
    var digits = 0
    var value = 0
    var expectSquare = true

    for (ch in trimmed) {
        when {
            ch.isDigit() -> {
                if (!expectSquare) throw MalformedMove("malformed move string: $trimmed")
                digits++
                if (digits > 2) throw MalformedMove("square out of range in $trimmed")
                value = value * 10 + (ch - '0')
            }

            ch == '-' || ch == 'x' || ch == 'X' || ch == ':' -> {
                if (digits == 0) throw MalformedMove("malformed move string: $trimmed")
                squares.add(value)
                digits = 0
                value = 0
                expectSquare = true
            }

            ch == ' ' -> Unit

            else -> throw MalformedMove("malformed move string: $trimmed")
        }
    }
    if (digits == 0) throw MalformedMove("malformed move string: $trimmed")
    squares.add(value)

    if (squares.size < 2) throw MalformedMove("a move needs at least two squares: $trimmed")
    for (square in squares) {
        if (square !in 1..SQUARE_COUNT) throw MalformedMove("square out of range in $trimmed: $square")
    }
    return squares.toIntArray()
}

fun formatMoveString(origin: Int, path: IntArray, isJump: Boolean): String = when {
    isJump -> buildString {
        append(origin)
        for (square in path) {
            append('x')
            append(square)
        }
    }

    else -> "$origin-${path[path.size - 1]}"
}
