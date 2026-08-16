package com.surenjanath.crownfoundry.game

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.ui.components.board.BoardHintSquares
import com.surenjanath.crownfoundry.ui.components.board.BoardPieceCount
import com.surenjanath.crownfoundry.ui.components.board.BoardSelectableSquares
import com.surenjanath.crownfoundry.ui.components.board.BoardSelectedSquare
import com.surenjanath.crownfoundry.ui.components.board.BoardSelection
import com.surenjanath.crownfoundry.ui.components.board.CheckersBoard
import com.surenjanath.crownfoundry.ui.components.board.CheckersBoardTag
import com.surenjanath.crownfoundry.ui.components.board.MoveTree
import com.surenjanath.crownfoundry.ui.components.board.SelectionStep
import com.surenjanath.crownfoundry.ui.components.board.squareCenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CheckersBoardTest {

    @get:Rule
    val rule = createComposeRule()

    private fun boardWith(onMove: (String) -> Unit = {}) = rule.setContent {
        Themed {
            var selection by remember { mutableStateOf<BoardSelection?>(null) }

            Box(modifier = Modifier.size(360.dp)) {
                CheckersBoard(
                    pieces = UiFixtures.initialPieces,
                    legalMoves = UiFixtures.openingMoves,
                    selection = selection,
                    modifier = Modifier.size(360.dp),
                    onSquareTap = { square ->
                        selection = reduce(selection, square, onMove)
                    }
                )
            }
        }
    }

    private fun reduce(
        current: BoardSelection?,
        square: Int,
        onMove: (String) -> Unit
    ): BoardSelection? {
        if (current != null) {
            when (val step = current.advance(square)) {
                is SelectionStep.Continued -> return step.selection

                is SelectionStep.Completed -> {
                    onMove(step.move.notation)
                    return null
                }

                SelectionStep.Rejected -> Unit
            }
        }

        return MoveTree.begin(UiFixtures.openingMoves, square)
    }

    private fun tapSquare(square: Int) {
        rule.onNodeWithTag(CheckersBoardTag).performTouchInput {
            click(squareCenter(square, width.toFloat()))
        }
        rule.waitForIdle()
    }

    @Test
    fun the_initial_position_renders_twenty_four_pieces() {
        boardWith()

        rule.onNodeWithTag(CheckersBoardTag)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(BoardPieceCount, 24))
    }

    @Test
    fun only_the_pieces_with_a_legal_move_are_offered() {
        boardWith()

        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardSelectableSquares, listOf(9, 10, 11, 12)))
    }

    @Test
    fun tapping_a_piece_shows_its_hints() {
        boardWith()

        tapSquare(11)

        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardSelectedSquare, 11))
            .assert(SemanticsMatcher.expectValue(BoardHintSquares, listOf(15, 16)))
    }

    @Test
    fun tapping_a_hint_completes_the_move() {
        val played = mutableListOf<String>()
        boardWith(onMove = { played += it })

        tapSquare(11)
        tapSquare(15)

        assertEquals(listOf("11-15"), played)
        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardHintSquares, emptyList<Int>()))
    }

    @Test
    fun tapping_a_piece_that_cannot_move_selects_nothing() {
        boardWith()

        tapSquare(1)

        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardSelectedSquare, 0))
            .assert(SemanticsMatcher.expectValue(BoardHintSquares, emptyList<Int>()))
    }

    @Test
    fun the_board_is_drawn_flipped_so_the_human_is_at_the_bottom() {
        boardWith()

        // Black's men - 1..12 - belong on the near half of an 800px board, White's on the far half.
        for (square in 1..12) {
            assertTrue("square $square", squareCenter(square, 800f).y > 400f)
        }
        for (square in 21..32) {
            assertTrue("square $square", squareCenter(square, 800f).y < 400f)
        }

        // And the tap mapping is the exact inverse: touching where 11 is drawn selects 11.
        tapSquare(11)

        rule.onNodeWithTag(CheckersBoardTag)
            .assert(SemanticsMatcher.expectValue(BoardSelectedSquare, 11))
    }
}
