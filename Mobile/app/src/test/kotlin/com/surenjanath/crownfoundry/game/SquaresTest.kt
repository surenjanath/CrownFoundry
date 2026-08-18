package com.surenjanath.crownfoundry.game

import com.surenjanath.crownfoundry.ui.components.board.Squares
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ARCHITECTURE.md §2, checked square by square. */
class SquaresTest {

    @Test
    fun `every square round-trips through row and column`() {
        for (square in Squares.all) {
            val row = Squares.rowOf(square)
            val col = Squares.colOf(square)

            assertEquals(
                "square $square -> ($row, $col) -> back",
                square,
                Squares.squareAt(row, col)
            )
        }
    }

    @Test
    fun `the mapping is the formula the contract states`() {
        for (square in Squares.all) {
            val row = (square - 1) / 4
            val idx = (square - 1) % 4
            val col = 2 * idx + if (row % 2 == 1) 0 else 1

            assertEquals("row of $square", row, Squares.rowOf(square))
            assertEquals("col of $square", col, Squares.colOf(square))
        }
    }

    @Test
    fun `row zero holds one to four and row seven holds twenty-nine to thirty-two`() {
        assertEquals(listOf(1, 2, 3, 4), (1..4).filter { Squares.rowOf(it) == 0 })
        assertEquals(listOf(29, 30, 31, 32), (29..32).filter { Squares.rowOf(it) == 7 })

        for (square in 1..4) assertEquals(0, Squares.rowOf(square))
        for (square in 29..32) assertEquals(7, Squares.rowOf(square))
    }

    @Test
    fun `even rows sit on columns 1 3 5 7 and odd rows on 0 2 4 6`() {
        for (square in Squares.all) {
            val row = Squares.rowOf(square)
            val col = Squares.colOf(square)

            if (row % 2 == 0) assertTrue("square $square", col in listOf(1, 3, 5, 7))
            else assertTrue("square $square", col in listOf(0, 2, 4, 6))
        }
    }

    @Test
    fun `every dark square is hit exactly once and no light square is ever produced`() {
        val seen = mutableSetOf<Pair<Int, Int>>()

        for (square in Squares.all) {
            val cell = Squares.rowOf(square) to Squares.colOf(square)

            assertTrue("square $square repeats cell $cell", seen.add(cell))
            assertEquals(
                "square $square landed on a light cell $cell",
                1,
                (cell.first + cell.second) % 2
            )
        }

        assertEquals(32, seen.size)
    }

    @Test
    fun `the whole grid maps back to thirty-two squares and thirty-two blanks`() {
        val produced = mutableListOf<Int>()

        for (row in 0 until 8) {
            for (col in 0 until 8) {
                val square = Squares.squareAt(row, col)

                if ((row + col) % 2 == 1) {
                    assertTrue("($row, $col) -> $square", Squares.isSquare(square))
                    produced += square
                } else {
                    assertEquals("light cell ($row, $col) must be nothing", 0, square)
                }
            }
        }

        assertEquals(Squares.all.toList(), produced.sorted())
    }

    @Test
    fun `off-board coordinates are nothing`() {
        assertEquals(0, Squares.squareAt(-1, 0))
        assertEquals(0, Squares.squareAt(0, 8))
        assertEquals(0, Squares.squareAt(8, 1))
        assertFalse(Squares.isDark(8, 1))
    }

    @Test
    fun `the flipped rendering mapping round-trips for all thirty-two squares`() {
        for (square in Squares.all) {
            val renderRow = Squares.renderRowOf(square)
            val renderCol = Squares.renderColOf(square)

            assertTrue(Squares.isOnBoard(renderRow, renderCol))
            assertEquals(
                "square $square renders at ($renderRow, $renderCol)",
                square,
                Squares.squareAtRendered(renderRow, renderCol)
            )
        }
    }

    @Test
    fun `the flip keeps dark squares dark and puts Black at the bottom`() {
        for (square in Squares.all) {
            val renderRow = Squares.renderRowOf(square)
            val renderCol = Squares.renderColOf(square)

            assertEquals(1, (renderRow + renderCol) % 2)
        }

        // Black starts on 1..12 and must be drawn on the near side: the bottom three ranks.
        for (square in 1..12) assertTrue(Squares.renderRowOf(square) >= 5)

        // White starts on 21..32 and must be drawn away from the player.
        for (square in 21..32) assertTrue(Squares.renderRowOf(square) <= 2)

        assertNotEquals(Squares.rowOf(1), Squares.renderRowOf(1))
    }

    @Test
    fun `a flipped tap outside the board is nothing`() {
        assertEquals(0, Squares.squareAtRendered(-1, 4))
        assertEquals(0, Squares.squareAtRendered(3, 9))
    }

    // --- White's side of the table, which only pass-and-play asks for ------------------------

    @Test
    fun `the unflipped rendering mapping round-trips too`() {
        for (square in Squares.all) {
            val renderRow = Squares.renderRowOf(square, fromBlack = false)
            val renderCol = Squares.renderColOf(square, fromBlack = false)

            assertTrue(Squares.isOnBoard(renderRow, renderCol))
            assertEquals(
                "square $square renders at ($renderRow, $renderCol)",
                square,
                Squares.squareAtRendered(renderRow, renderCol, fromBlack = false)
            )
        }
    }

    @Test
    fun `turning the board puts White at the bottom and keeps the dark squares dark`() {
        for (square in Squares.all) {
            val renderRow = Squares.renderRowOf(square, fromBlack = false)
            val renderCol = Squares.renderColOf(square, fromBlack = false)

            assertEquals(1, (renderRow + renderCol) % 2)
        }

        // The mirror image of the default: White's home rank is now the near side.
        for (square in 21..32) assertTrue(Squares.renderRowOf(square, fromBlack = false) >= 5)
        for (square in 1..12) assertTrue(Squares.renderRowOf(square, fromBlack = false) <= 2)
    }

    @Test
    fun `the two orientations are exact opposites`() {
        for (square in Squares.all) {
            assertEquals(
                Squares.SIDE - 1 - Squares.renderRowOf(square),
                Squares.renderRowOf(square, fromBlack = false)
            )
            assertEquals(
                Squares.SIDE - 1 - Squares.renderColOf(square),
                Squares.renderColOf(square, fromBlack = false)
            )
        }
    }
}
