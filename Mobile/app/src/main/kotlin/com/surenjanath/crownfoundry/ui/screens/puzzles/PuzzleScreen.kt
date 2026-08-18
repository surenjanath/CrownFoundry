package com.surenjanath.crownfoundry.ui.screens.puzzles

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.offline.Puzzle
import com.surenjanath.crownfoundry.ui.components.board.CheckersBoard
import com.surenjanath.crownfoundry.ui.components.board.BoardTrace
import com.surenjanath.crownfoundry.ui.components.board.TapResult
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.components.themed.HeaderIconButton
import com.surenjanath.crownfoundry.ui.components.themed.SecondaryTextButton
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.hapticFeedbackKey
import com.surenjanath.crownfoundry.utils.rememberPreference
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

/**
 * One position, one right answer.
 *
 * The board is the same board the game uses, with the same tap resolver behind it, so a jump has
 * to be played out here exactly as it would in a game. What is missing is a referee and an
 * opponent: the move is checked against what the engine would have played and that is the end of
 * the position.
 *
 * The attempt is recorded once, when it finishes. Changing your mind mid-move is not a failure,
 * and being shown the answer is not a solve.
 */
@ExperimentalFoundationApi
@Composable
fun PuzzleScreen(puzzleId: String) {
    val (colorPalette, typography) = LocalAppearance.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val haptics = LocalHapticFeedback.current
    val useHaptics by rememberPreference(hapticFeedbackKey, true)

    var puzzle by remember(puzzleId) { mutableStateOf<Puzzle?>(null) }
    var missing by remember(puzzleId) { mutableStateOf(false) }

    LaunchedEffect(puzzleId) {
        val found = Offline.puzzles?.find(puzzleId)
        puzzle = found
        missing = found == null
    }

    val session = remember(puzzle) { puzzle?.let(PuzzleSession::of) }

    // One write per finished attempt, keyed on the verdict itself: a change of mind mid-move never
    // reaches this, and "try again" passes back through Unanswered, so a solve on the second go is
    // recorded as one.
    LaunchedEffect(session, session?.verdict) {
        val current = session ?: return@LaunchedEffect
        if (!current.verdict.isFinished) return@LaunchedEffect
        Offline.puzzles?.record(current.puzzle.id, current.verdict.isCorrect)
    }

    Column(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                LocalWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues()
            )
    ) {
        Header(title = "Puzzle") {
            HeaderIconButton(
                icon = R.drawable.chevron_back,
                color = colorPalette.text,
                onClick = { backDispatcher?.onBackPressed() }
            )
        }

        if (session == null) {
            BasicText(
                text = if (missing) {
                    "This puzzle is no longer stored on the device."
                } else {
                    "This puzzle could not be set up - its position no longer reads as a legal " +
                        "one, so it has been left out rather than shown with no right answer."
                },
                style = typography.xs.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            return@Column
        }

        val yourMove = session.sideToMove == Side.BLACK

        BasicText(
            text = when {
                session.mustCapture -> "A capture is on. Play it out."
                yourMove -> "Black to play. Find the move you missed."
                else -> "White to play. Find the best move."
            },
            style = typography.xs.secondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        CheckersBoard(
            pieces = session.pieces,
            legalMoves = session.legalMoves,
            selection = session.selection,
            // Once it is over the answer is ringed on the board, which is the fastest way to
            // read "this is the move" - faster than the notation under it.
            lastMove = session.answerSquares
                .takeIf { session.verdict.isFinished && it.size == 2 }
                ?.let { BoardTrace(it[0], it[1]) },
            showHints = true,
            enabled = session.acceptsTaps,
            onSquareTap = { square ->
                val result = session.tap(square)
                if (useHaptics && result !is TapResult.Ignored) {
                    haptics.performHapticFeedback(
                        if (result is TapResult.Ready) {
                            HapticFeedbackType.LongPress
                        } else {
                            HapticFeedbackType.TextHandleMove
                        }
                    )
                }
            },
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(max = 480.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        verdictLine(session)?.let { line ->
            BasicText(
                text = line,
                style = typography.xs.semiBold.color(
                    if (session.verdict.isCorrect) colorPalette.accent else colorPalette.red
                ),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            if (!session.verdict.isCorrect) {
                SecondaryTextButton(
                    text = "Reveal",
                    enabled = session.verdict !is PuzzleVerdict.Revealed,
                    onClick = { session.reveal() }
                )
            }

            if (session.verdict is PuzzleVerdict.Wrong) {
                SecondaryTextButton(
                    text = "Try again",
                    onClick = { session.retry() }
                )
            }

            if (session.verdict.isFinished) {
                SecondaryTextButton(
                    text = "Back to puzzles",
                    onClick = { backDispatcher?.onBackPressed() }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
