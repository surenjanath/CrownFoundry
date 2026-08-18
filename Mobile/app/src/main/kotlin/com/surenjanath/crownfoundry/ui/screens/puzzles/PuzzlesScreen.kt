package com.surenjanath.crownfoundry.ui.screens.puzzles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.offline.Puzzle
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

/**
 * The mistakes you have made, waiting to be made right.
 *
 * Puzzles are collected when a game is reviewed, not when it ends: scoring every ply is a few
 * seconds of search, and doing it behind the "game over" dialog would make finishing a game feel
 * slow for a feature the player has not asked for yet. Opening a match in Review does ask.
 */
@Composable
fun PuzzlesScreen(onOpenPuzzle: (String) -> Unit) {
    val (colorPalette, typography) = LocalAppearance.current

    val holder = remember { PuzzleListHolder(Offline.puzzles) }

    // Reloaded on every entry: a game reviewed since the last visit will have added to this.
    LaunchedEffect(Unit) { holder.load() }

    val puzzles = holder.puzzles

    Box(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
    ) {
        LazyColumn(
            contentPadding = LocalWindowInsets.current
                .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                .asPaddingValues(),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "header") {
                Header(title = "Puzzles") {
                    if (puzzles.isNotEmpty()) {
                        BasicText(
                            text = "${holder.solved} of ${puzzles.size} solved",
                            style = typography.s.secondary
                        )
                    }
                }
            }

            if (puzzles.isEmpty() && !holder.isLoading) {
                item(key = "empty") {
                    BasicText(
                        text = "Nothing to practise yet. Open a finished game in Matches and the " +
                            "engine scores every move you played; the ones it calls a mistake, " +
                            "where there was a clearly better move, land here as puzzles.",
                        style = typography.xs.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            items(items = puzzles, key = { it.id }) { puzzle ->
                PuzzleRow(puzzle = puzzle, onClick = { onOpenPuzzle(puzzle.id) })
            }

            item(key = "footer") {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PuzzleRow(puzzle: Puzzle, onClick: () -> Unit) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colorPalette.background1)
        ) {
            Image(
                painter = painterResource(
                    if (puzzle.solved) R.drawable.checkmark else R.drawable.shapes
                ),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    if (puzzle.solved) colorPalette.accent else colorPalette.textSecondary
                ),
                modifier = Modifier.size(16.dp)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(
                text = "Find the move",
                style = if (puzzle.solved) {
                    typography.xs.semiBold.color(colorPalette.textSecondary)
                } else {
                    typography.xs.semiBold
                }
            )

            BasicText(text = puzzleSubtitle(puzzle), style = typography.xxs.secondary)
        }
    }
}
