package com.surenjanath.crownfoundry.game

import com.surenjanath.crownfoundry.api.MoveDto
import com.surenjanath.crownfoundry.ui.components.board.MoveTree
import com.surenjanath.crownfoundry.ui.components.board.SelectionStep
import com.surenjanath.crownfoundry.ui.components.board.landingSquares
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tap-to-move: what a finger on a square reduces a list of legal moves down to. */
class MoveSelectionTest {

    @Test
    fun `notation carries every landing square in order`() {
        assertEquals(listOf(15), Fixtures.move("11-15").landingSquares())
        assertEquals(listOf(18), MoveDto("11x18", 11, 18, listOf(15)).landingSquares())
        assertEquals(
            listOf(18, 25),
            MoveDto("11x18x25", 11, 25, listOf(15, 22)).landingSquares()
        )
    }

    @Test
    fun `a move with no usable notation still names its destination`() {
        assertEquals(listOf(19), MoveDto(notation = "", from = 24, to = 19).landingSquares())
    }

    @Test
    fun `selecting a piece yields exactly its destinations`() {
        val selection = MoveTree.begin(Fixtures.openingMoves, 11)

        assertEquals(11, selection?.origin)
        assertEquals(setOf(15, 16), selection?.destinations)
        assertTrue(selection?.threatened.orEmpty().isEmpty())
    }

    @Test
    fun `tapping a piece with no legal move selects nothing`() {
        assertNull(MoveTree.begin(Fixtures.openingMoves, 1))
        assertNull(MoveTree.begin(Fixtures.openingMoves, 24))
        assertNull(MoveTree.begin(emptyList(), 11))
    }

    @Test
    fun `a simple move completes on the first tap`() {
        val selection = MoveTree.begin(Fixtures.openingMoves, 11)!!
        val step = selection.advance(15)

        assertTrue(step is SelectionStep.Completed)
        assertEquals("11-15", (step as SelectionStep.Completed).move.notation)
    }

    @Test
    fun `tapping anywhere else is rejected`() {
        val selection = MoveTree.begin(Fixtures.openingMoves, 11)!!

        assertSame(SelectionStep.Rejected, selection.advance(19))
        assertSame(SelectionStep.Rejected, selection.advance(11))
    }

    @Test
    fun `with captures pending only capturing pieces are selectable`() {
        val moves = Fixtures.captureMoves + Fixtures.openingMoves

        assertTrue(MoveTree.capturesPending(moves))
        assertEquals(setOf(11, 10), MoveTree.selectableSquares(moves))
        assertNull("12 can only shuffle, so it is not offered", MoveTree.begin(moves, 12))
        assertEquals(setOf(25, 27, 17), Fixtures.captureMoves.map { it.to }.toSet())
    }

    @Test
    fun `without captures every piece that can move is selectable`() {
        val moves = Fixtures.openingMoves

        assertTrue(!MoveTree.capturesPending(moves))
        assertEquals(setOf(9, 10, 11, 12), MoveTree.selectableSquares(moves))
    }

    @Test
    fun `a multi jump narrows hop by hop and sends one notation string`() {
        val selection = MoveTree.begin(Fixtures.captureMoves, 11)!!

        // First tap: every jump 11 can start with is offered, and the piece it takes is marked.
        assertEquals(setOf(18), selection.destinations)
        assertEquals(setOf(15), selection.threatened)
        assertEquals(15, selection.captureAt(18))

        val afterFirstHop = selection.advance(18)
        assertTrue(afterFirstHop is SelectionStep.Continued)

        val midJump = (afterFirstHop as SelectionStep.Continued).selection
        assertTrue(midJump.isMidJump)
        assertEquals(18, midJump.square)
        assertEquals(listOf(15), midJump.capturedSoFar)

        // Second tap: only the continuations of *this* jump remain.
        assertEquals(setOf(25, 27), midJump.destinations)
        assertEquals(setOf(22, 23), midJump.threatened)
        assertEquals(22, midJump.captureAt(25))

        val finished = midJump.advance(25)
        assertTrue(finished is SelectionStep.Completed)
        assertEquals("11x18x25", (finished as SelectionStep.Completed).move.notation)
    }

    @Test
    fun `a triple jump takes three taps`() {
        val triple = MoveDto("11x18x25x32", 11, 32, captures = listOf(15, 22, 29))
        val double = MoveDto("11x18x27", 11, 27, captures = listOf(15, 23))

        val start = MoveTree.begin(listOf(triple, double), 11)!!
        assertEquals(setOf(18), start.destinations)

        val first = (start.advance(18) as SelectionStep.Continued).selection
        assertEquals(setOf(25, 27), first.destinations)

        val second = (first.advance(25) as SelectionStep.Continued).selection
        assertEquals(setOf(32), second.destinations)
        assertEquals(listOf(15, 22), second.capturedSoFar)

        val third = second.advance(32)
        assertEquals("11x18x25x32", (third as SelectionStep.Completed).move.notation)
    }

    @Test
    fun `mid jump nothing but a continuation is accepted`() {
        val midJump = (MoveTree.begin(Fixtures.captureMoves, 11)!!.advance(18)
                as SelectionStep.Continued).selection

        assertSame(SelectionStep.Rejected, midJump.advance(11))
        assertSame(SelectionStep.Rejected, midJump.advance(15))
        assertSame(SelectionStep.Rejected, midJump.advance(17))
    }

    @Test
    fun `a jump that ends where a longer one passes through is never ambiguous`() {
        val short = MoveDto("11x18", 11, 18, captures = listOf(15))
        val long = MoveDto("11x18x25", 11, 25, captures = listOf(15, 22))

        // The short jump is offered only when the long one is not on the board.
        val onlyShort = MoveTree.begin(listOf(short), 11)!!
        assertTrue(onlyShort.advance(18) is SelectionStep.Completed)

        val both = MoveTree.begin(listOf(short, long), 11)!!
        assertTrue(
            "the first complete candidate wins, and English draughts never offers both",
            both.advance(18) is SelectionStep.Completed
        )
    }
}
