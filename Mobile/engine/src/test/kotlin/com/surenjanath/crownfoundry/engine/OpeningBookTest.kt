package com.surenjanath.crownfoundry.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningBookTest {

    @Test
    fun `after eleven fifteen white has a book reply`() {
        val opening = Board.initial()
        val board = opening.apply(opening.parseMove("11-15"))
        val notation = OpeningBook.lookup(listOf("11-15"), board)
        assertNotNull(notation)
        val legal = board.legalMoves().map { it.notation() }.toSet()
        assertTrue("$notation should be legal", notation in legal)
        val replies = setOf("23-19", "22-18", "23-18", "21-17", "24-20", "24-19")
        assertTrue("$notation should be a book reply", notation in replies)
    }

    @Test
    fun `an unknown line leaves the book`() {
        val board = Board.initial()
        assertNull(OpeningBook.lookup(listOf("99-98"), board))
    }
}
