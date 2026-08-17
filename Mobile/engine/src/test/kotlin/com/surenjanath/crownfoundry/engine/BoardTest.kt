package com.surenjanath.crownfoundry.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules port, against the backend.
 *
 * Every expected value in this file was produced by `Backend/game/engine`, not by reading this
 * implementation back to itself. The perft counts in particular are the load-bearing test: a move
 * generator can be wrong in a way that looks right for twenty positions and diverges on the
 * twenty-first, and a tree count catches that where spot checks do not.
 */
class BoardTest {

    private fun perft(board: Board, depth: Int): Int =
        if (depth == 0) 1 else board.legalMoves().sumOf { perft(board.apply(it), depth - 1) }

    @Test
    fun `perft matches the backend under the default rules`() {
        val board = Board.initial()
        assertEquals(listOf(7, 49, 302, 1469, 7482), (1..5).map { perft(board, it) })
    }

    @Test
    fun `perft matches the backend under strict english rules`() {
        val board = Board.initial(VariantRules.ENGLISH)
        assertEquals(listOf(7, 49, 302, 1469, 7361), (1..5).map { perft(board, it) })
    }

    @Test
    fun `the opening position is the one the backend serves`() {
        assertEquals(
            "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12",
            Board.initial().toFen()
        )
        assertEquals(
            listOf("10-14", "10-15", "11-15", "11-16", "12-16", "9-13", "9-14"),
            Board.initial().legalMoves().map { it.notation() }.sorted()
        )
    }

    @Test
    fun `fen round trips through the board`() {
        for (fen in FENS) {
            assertEquals(fen, Board.fromFen(fen).toFen())
        }
    }

    @Test
    fun `a flying king finds every jump path the backend finds`() {
        val board = Board.fromFen("B:W18,26,27:B11,K31")
        assertEquals(
            listOf("31x13", "31x17", "31x20", "31x22x15", "31x24"),
            board.legalMoves().map { it.notation() }.sorted()
        )
    }

    @Test
    fun `a mandatory capture hides the quiet moves`() {
        val board = Board.fromFen("W:WK15,23:B10,K1")
        assertEquals(listOf("15x6"), board.legalMoves().map { it.notation() })
    }

    @Test
    fun `without mandatory capture the quiet moves come back`() {
        val rules = VariantRules(mandatoryCapture = false)
        val board = Board.fromFen("W:WK15,23:B10,K1", rules = rules)
        val moves = board.legalMoves().map { it.notation() }
        assertTrue("15x6" in moves)
        assertTrue(moves.size > 1)
    }

    @Test
    fun `a man crowning mid-jump ends the sequence there`() {
        // Black man on 21 jumps 25 and lands on 30, its crowning row; it may not carry on even
        // though a king standing on 30 would have another jump available.
        val board = Board.fromFen("B:W25,26:B21")
        val moves = board.legalMoves()
        assertEquals(listOf("21x30"), moves.map { it.notation() })
        assertTrue(moves[0].crowned)
        assertEquals(1, moves[0].captures.size)
    }

    @Test
    fun `men only capture backwards when the variant says so`() {
        val fen = "B:W6:B10"
        assertTrue(Board.fromFen(fen).legalMoves().any { it.isJump })
        assertFalse(Board.fromFen(fen, rules = VariantRules.ENGLISH).legalMoves().any { it.isJump })
    }

    @Test
    fun `applying a move flips the side and clears the captured squares`() {
        val board = Board.fromFen("W:WK15,23:B10,K1")
        val after = board.apply(board.parseMove("15x6"))

        assertEquals(BLACK, after.sideToMove)
        assertEquals(EMPTY, after.pieceAt(10))
        assertEquals(EMPTY, after.pieceAt(15))
        assertEquals(WHITE_KING, after.pieceAt(6))
        assertEquals(0, after.pliesSinceProgress)
    }

    @Test
    fun `a quiet move advances the no-progress counter and a capture resets it`() {
        var board = Board.initial()
        board = board.apply(board.parseMove("11-15"))
        assertEquals(1, board.pliesSinceProgress)
        board = board.apply(board.parseMove("22-18"))
        assertEquals(2, board.pliesSinceProgress)
        board = board.apply(board.parseMove("15x22"))
        assertEquals(0, board.pliesSinceProgress)
    }

    @Test
    fun `annihilation decides the game`() {
        assertEquals(WHITE, Board.fromFen("B:W21,22:B").winner())
        assertEquals(Side.WHITE, Board.fromFen("B:W21,22:B").winnerName())
    }

    @Test
    fun `immobilisation decides the game too`() {
        // Black to move with one man on 1: both steps are blocked, and the only jump it could
        // make would land on 10, which is occupied.
        val boxed = Board.fromFen("B:W5,6,10:B1")
        assertTrue(boxed.legalMoves().isEmpty())
        assertEquals(WHITE, boxed.winner())
    }

    @Test
    fun `a draw is reported as a draw and not as a side`() {
        val fen = "B:W32:BK5,K6"
        val stalled = Board.fromFen(fen, pliesSinceProgress = NO_PROGRESS_PLIES)
        assertEquals(DRAW_RESULT, stalled.winner())
        assertEquals(Side.DRAW, stalled.winnerName())
    }

    @Test
    fun `a live position has no winner`() {
        assertNull(Board.initial().winner())
        assertFalse(Board.initial().isTerminal())
    }

    @Test
    fun `parse rejects a move that is not legal here`() {
        val board = Board.initial()
        assertThrows(IllegalMove::class.java) { board.parseMove("11-18") }
        assertThrows(IllegalMove::class.java) { board.parseMove("nonsense") }
    }

    @Test
    fun `parse accepts either separator for the same move`() {
        val board = Board.fromFen("W:WK15,23:B10,K1")
        assertEquals(board.parseMove("15x6"), board.parseMove("15-6"))
    }

    @Test
    fun `resolve reports an ambiguous jump rather than guessing`() {
        // A king on 4 reaches 25 by two different capture paths: 4x15x25 and 4x18x25. Picking one
        // silently would take a piece the player did not choose to take.
        val board = Board.fromFen("W:WK4:B11,13,22,31")
        assertEquals(
            listOf("4x15x25", "4x18x25"),
            board.legalMoves().filter { it.destination == 25 }.map { it.notation() }.sorted()
        )
        assertThrows(AmbiguousMove::class.java) { board.resolve(4, 25) }
        // Naming the full path is unambiguous, which is why the app sends notations, not pairs.
        assertEquals("4x15x25", board.parseMove("4x15x25").notation())
    }

    @Test
    fun `repetition is counted only back to the last irreversible move`() {
        var board = Board.initial()
        for (notation in listOf("11-15", "22-18", "15x22")) {
            board = board.apply(board.parseMove(notation))
        }
        // The capture wiped the window, so the position it produced is the only entry in it.
        assertEquals(1, board.history.size)
        assertEquals(1, board.repetitionCount())
    }

    @Test
    fun `hasJump agrees with the generated move list`() {
        // hasJump is the shortcut the risk heuristic and the narrator lean on; if it ever
        // disagreed with the generator the AI would be evaluating a position it cannot reach.
        var board = Board.initial()
        val random = kotlin.random.Random(17)
        repeat(200) {
            val moves = board.legalMoves()
            if (moves.isEmpty()) return@repeat
            assertEquals(board.toFen(), moves.any { it.isJump }, board.hasJump())
            board = board.apply(moves[random.nextInt(moves.size)])
        }
        for (fen in FENS) {
            val position = Board.fromFen(fen)
            assertEquals(fen, position.legalMoves().any { it.isJump }, position.hasJump())
        }
    }

    @Test
    fun `a malformed fen is rejected rather than silently half-read`() {
        for (bad in listOf("", "B:W1", "X:W1:B2", "B:W1:B1", "B:W99:B1", "B:W1:W2")) {
            assertThrows(IllegalMove::class.java) { Board.fromFen(bad) }
        }
    }

    companion object {
        val FENS = listOf(
            "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12",
            "W:WK15,23:BK1,10",
            "B:W18,26,27:B11,K31",
            "W:W32:BK5,K6",
            "B:W21,25,K29:B4,K12,14"
        )
    }
}
