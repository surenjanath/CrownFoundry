package com.surenjanath.crownfoundry.insights

import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.screens.matches.Fen
import com.surenjanath.crownfoundry.ui.screens.matches.Position
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The review screen rebuilds every position from one of these strings, so a parser that is
 * merely nearly right would put pieces on the wrong squares for a whole match without ever
 * failing loudly.
 */
class FenTest {

    @Test
    fun `opening position has twelve men a side on the documented squares`() {
        val position = Fen.parse(Fen.OPENING)
        assertNotNull(position)
        position!!

        assertEquals(Side.BLACK, position.sideToMove)
        assertEquals(24, position.pieces.size)

        val black = position.pieces.filter { it.isBlack }
        val white = position.pieces.filter { it.isWhite }

        assertEquals((1..12).toList(), black.map { it.square }.sorted())
        assertEquals((21..32).toList(), white.map { it.square }.sorted())
        assertTrue(position.pieces.none { it.king })
    }

    @Test
    fun `black moves first and white can move too`() {
        assertEquals(Side.BLACK, Fen.parse("B:W21:B1")?.sideToMove)
        assertEquals(Side.WHITE, Fen.parse("W:W21:B1")?.sideToMove)
        assertEquals(Side.BLACK, Fen.parse("b:W21:B1")?.sideToMove)
        assertEquals(Side.WHITE, Fen.parse("w:W21:B1")?.sideToMove)
    }

    @Test
    fun `a K prefix crowns the piece it is attached to and nothing else`() {
        val position = Fen.parse("B:WK22,23:BK1,2")
        assertNotNull(position)

        val bySquare = position!!.pieces.associateBy { it.square }

        assertEquals(true, bySquare[22]?.king)
        assertEquals(false, bySquare[23]?.king)
        assertEquals(true, bySquare[1]?.king)
        assertEquals(false, bySquare[2]?.king)

        assertEquals(Side.WHITE, bySquare[22]?.side)
        assertEquals(Side.BLACK, bySquare[1]?.side)
    }

    @Test
    fun `a side with no pieces left parses as an empty list, not a failure`() {
        val whiteWipedOut = Fen.parse("B:W:B1,2")
        assertNotNull(whiteWipedOut)
        assertEquals(listOf(1, 2), whiteWipedOut!!.pieces.map { it.square })
        assertTrue(whiteWipedOut.pieces.all { it.isBlack })

        val blackWipedOut = Fen.parse("W:W21,22:B")
        assertNotNull(blackWipedOut)
        assertEquals(listOf(21, 22), blackWipedOut!!.pieces.map { it.square })

        val emptyBoard = Fen.parse("B:W:B")
        assertNotNull(emptyBoard)
        assertEquals(0, emptyBoard!!.pieces.size)
    }

    @Test
    fun `the sections may arrive in either order`() {
        val standard = Fen.parse("B:W21,22:B1,2")
        val reversed = Fen.parse("B:B1,2:W21,22")

        assertEquals(standard, reversed)
    }

    @Test
    fun `malformed input returns null instead of throwing`() {
        val rubbish = listOf(
            "",
            "   ",
            "B",
            "B:W21",
            "B:W21:B1:extra",
            "X:W21:B1",
            "B:W21:W22",
            "B:B1:B2",
            "B:Wabc:B1",
            "B:W21:B0",
            "B:W33:B1",
            "B:W-4:B1",
            "B:W21,:B1",
            "B:W21,,22:B1",
            "B:WK:B1",
            "B:W21:B21",
            "B:W21,21:B1",
            "B:W2147483648:B1",
            ":::",
            "::"
        )

        rubbish.forEach { fen ->
            assertNull("expected null for \"$fen\"", Fen.parse(fen))
        }

        assertNull(Fen.parse(null))
    }

    @Test
    fun `an unreadable string still yields an empty board through parseOrEmpty`() {
        val position = Fen.parseOrEmpty("nonsense")

        assertEquals(Side.BLACK, position.sideToMove)
        assertEquals(0, position.pieces.size)
    }

    @Test
    fun `every one of the thirty-two squares maps to a dark square and back`() {
        val seen = mutableSetOf<Pair<Int, Int>>()

        for (square in 1..Fen.SQUARES) {
            val row = Fen.rowOf(square)
            val col = Fen.colOf(square)

            assertTrue("square $square row $row out of range", row in 0 until Fen.SIZE)
            assertTrue("square $square col $col out of range", col in 0 until Fen.SIZE)
            assertEquals("square $square is not on a dark square", 1, (row + col) % 2)

            assertTrue("square $square collides", seen.add(row to col))
            assertEquals("round trip failed for $square", square, Fen.squareAt(row, col))
        }

        assertEquals(32, seen.size)
    }

    @Test
    fun `the documented row anchors hold`() {
        assertEquals(listOf(0, 0, 0, 0), listOf(1, 2, 3, 4).map(Fen::rowOf))
        assertEquals(listOf(7, 7, 7, 7), listOf(29, 30, 31, 32).map(Fen::rowOf))
        assertEquals(listOf(1, 3, 5, 7), listOf(1, 2, 3, 4).map(Fen::colOf))
        assertEquals(listOf(0, 2, 4, 6), listOf(5, 6, 7, 8).map(Fen::colOf))
    }

    @Test
    fun `light squares hold nothing`() {
        for (row in 0 until Fen.SIZE) {
            for (col in 0 until Fen.SIZE) {
                if ((row + col) % 2 == 0) {
                    assertNull(Fen.squareAt(row, col))
                }
            }
        }

        assertNull(Fen.squareAt(-1, 1))
        assertNull(Fen.squareAt(8, 1))
        assertNull(Fen.squareAt(0, 9))
    }

    @Test
    fun `every single-piece position survives a round trip`() {
        for (square in 1..Fen.SQUARES) {
            for (side in listOf(Side.BLACK, Side.WHITE)) {
                for (king in listOf(false, true)) {
                    for (toMove in listOf(Side.BLACK, Side.WHITE)) {
                        val position = Position(
                            sideToMove = toMove,
                            pieces = listOf(PieceDto(square = square, side = side, king = king))
                        )

                        val rendered = Fen.render(position)
                        assertEquals("round trip failed for $rendered", position, Fen.parse(rendered))
                    }
                }
            }
        }
    }

    @Test
    fun `randomly generated positions survive a round trip`() {
        val random = Random(20260816)

        repeat(400) {
            val squares = (1..Fen.SQUARES).shuffled(random).take(random.nextInt(0, 25))

            val pieces = squares
                .map {
                    PieceDto(
                        square = it,
                        side = if (random.nextBoolean()) Side.BLACK else Side.WHITE,
                        king = random.nextBoolean()
                    )
                }
                .sortedBy { it.square }

            val position = Position(
                sideToMove = if (random.nextBoolean()) Side.BLACK else Side.WHITE,
                pieces = pieces
            )

            val rendered = Fen.render(position)
            assertEquals("round trip failed for $rendered", position, Fen.parse(rendered))
        }
    }

    @Test
    fun `the opening position renders back to the string it came from`() {
        assertEquals(Fen.OPENING, Fen.render(Fen.parse(Fen.OPENING)!!))
    }

    @Test
    fun `move notation gives up its squares`() {
        assertEquals(listOf(11, 15), Fen.squaresOfMove("11-15"))
        assertEquals(listOf(11, 18), Fen.squaresOfMove("11x18"))
        assertEquals(listOf(11, 18, 25), Fen.squaresOfMove("11x18x25"))
        assertEquals(listOf(11, 18), Fen.squaresOfMove("11X18"))
        assertEquals(emptyList<Int>(), Fen.squaresOfMove(null))
        assertEquals(emptyList<Int>(), Fen.squaresOfMove(""))
        assertEquals(emptyList<Int>(), Fen.squaresOfMove("nonsense"))
        assertEquals(emptyList<Int>(), Fen.squaresOfMove("0-33"))
    }
}
