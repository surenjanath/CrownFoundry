package com.surenjanath.crownfoundry.ui.components.board

/**
 * The single place where the 1..32 square numbering of English draughts meets an 8x8 grid.
 * ARCHITECTURE.md §2 is the contract:
 *
 * ```
 * row = (n - 1) / 4
 * idx = (n - 1) % 4
 * col = 2*idx + (0 if row is odd else 1)
 * ```
 *
 * Nothing else in the app is allowed to reinvent this.
 */
object Squares {
    const val SIDE = 8
    const val COUNT = 32

    val all = 1..COUNT

    private const val NONE = 0

    fun isSquare(square: Int) = square in all

    /** Only the dark squares are playable, and they are the ones where row + col is odd. */
    fun isDark(row: Int, col: Int) = isOnBoard(row, col) && (row + col) % 2 == 1

    fun isOnBoard(row: Int, col: Int) = row in 0 until SIDE && col in 0 until SIDE

    fun rowOf(square: Int): Int {
        require(isSquare(square)) { "square $square is outside 1..32" }
        return (square - 1) / 4
    }

    fun colOf(square: Int): Int {
        require(isSquare(square)) { "square $square is outside 1..32" }
        val row = (square - 1) / 4
        val idx = (square - 1) % 4
        return 2 * idx + if (row % 2 == 1) 0 else 1
    }

    /** [NONE] (0, never a real square) for light squares and anything off the board. */
    fun squareAt(row: Int, col: Int): Int {
        if (!isDark(row, col)) return NONE
        val idx = if (row % 2 == 1) col / 2 else (col - 1) / 2
        return row * 4 + idx + 1
    }

    // --- rendering ------------------------------------------------------------------------------
    //
    // The human is Black and Black's home rank is row 0, so the board is turned through 180 degrees
    // before it is drawn: the player looks at their own pieces from behind. This flip exists only
    // here - a square number on the wire is never flipped.
    //
    // [fromBlack] is false only in pass-and-play, where the board turns to face whichever of the
    // two people at the phone is to move. The default is the way the app has always drawn it.

    fun renderRowOf(square: Int, fromBlack: Boolean = true) =
        if (fromBlack) SIDE - 1 - rowOf(square) else rowOf(square)

    fun renderColOf(square: Int, fromBlack: Boolean = true) =
        if (fromBlack) SIDE - 1 - colOf(square) else colOf(square)

    fun squareAtRendered(renderRow: Int, renderCol: Int, fromBlack: Boolean = true): Int {
        if (!isOnBoard(renderRow, renderCol)) return NONE
        return if (fromBlack) {
            squareAt(SIDE - 1 - renderRow, SIDE - 1 - renderCol)
        } else {
            squareAt(renderRow, renderCol)
        }
    }
}
