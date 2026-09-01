package com.surenjanath.crownfoundry.leaderboard

import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.offline.LocalMatch
import com.surenjanath.crownfoundry.offline.Puzzle
import org.junit.Assert.assertEquals
import org.junit.Test

class LeaderboardScoresTest {

    private var clock = 0L

    private fun match(
        winner: String?,
        mode: String = LocalMatch.MODE_ENGINE
    ): LocalMatch {
        clock += 1000
        return LocalMatch(
            localId = "m$clock",
            matchId = "m$clock",
            startedAt = clock,
            finishedAt = if (winner == null) null else clock + 1,
            winner = winner,
            mode = mode
        )
    }

    private fun puzzle(solved: Boolean) = Puzzle(
        id = "p${clock++}",
        fen = "",
        best = "",
        played = "",
        solved = solved
    )

    @Test
    fun `an empty corpus scores nothing`() {
        val scores = scoresOf(emptyList(), emptyList())
        assertEquals(0, scores.wins)
        assertEquals(0, scores.streak)
        assertEquals(0, scores.puzzles)
    }

    @Test
    fun `wins count only the games the human won`() {
        val scores = scoresOf(
            listOf(
                match(Side.HUMAN),
                match(Side.AI),
                match(Side.HUMAN),
                match(Side.DRAW)
            ),
            emptyList()
        )
        assertEquals(2, scores.wins)
    }

    @Test
    fun `an unfinished game is not a result`() {
        val scores = scoresOf(listOf(match(Side.HUMAN), match(null)), emptyList())
        assertEquals(1, scores.wins)
        assertEquals(1, scores.streak)
    }

    /** Two people at one phone are not the account a score would be posted under. */
    @Test
    fun `pass-and-play games are excluded`() {
        val scores = scoresOf(
            listOf(
                match(Side.HUMAN, mode = LocalMatch.MODE_PASS),
                match(Side.HUMAN, mode = LocalMatch.MODE_PASS),
                match(Side.HUMAN)
            ),
            emptyList()
        )
        assertEquals(1, scores.wins)
        assertEquals(1, scores.streak)
    }

    @Test
    fun `the streak is the longest run, not the current one`() {
        val scores = scoresOf(
            listOf(
                match(Side.HUMAN),
                match(Side.HUMAN),
                match(Side.HUMAN),
                match(Side.AI),
                match(Side.HUMAN)
            ),
            emptyList()
        )
        assertEquals(3, scores.streak)
        assertEquals(4, scores.wins)
    }

    /** "Five in a row" means five wins. A draw is not a win, so it ends the run. */
    @Test
    fun `a draw breaks a streak without being a loss`() {
        val scores = scoresOf(
            listOf(match(Side.HUMAN), match(Side.DRAW), match(Side.HUMAN)),
            emptyList()
        )
        assertEquals(1, scores.streak)
        assertEquals(2, scores.wins)
    }

    /** The corpus is not stored in order, and a streak read out of order is a different number. */
    @Test
    fun `the streak is computed in the order the games finished`() {
        val first = match(Side.HUMAN)
        val second = match(Side.AI)
        val third = match(Side.HUMAN)
        val fourth = match(Side.HUMAN)

        val shuffled = listOf(third, first, fourth, second)
        assertEquals(2, scoresOf(shuffled, emptyList()).streak)
    }

    @Test
    fun `puzzles count the solved ones`() {
        val scores = scoresOf(
            emptyList(),
            listOf(puzzle(solved = true), puzzle(solved = false), puzzle(solved = true))
        )
        assertEquals(2, scores.puzzles)
    }

    @Test
    fun `every board can be read off the scores`() {
        val scores = LeaderboardScores(wins = 4, streak = 2, puzzles = 7)
        assertEquals(4, scores[Leaderboard.Wins])
        assertEquals(2, scores[Leaderboard.Streak])
        assertEquals(7, scores[Leaderboard.Puzzles])
    }

    @Test
    fun `boards are addressable by the key their console id is configured under`() {
        for (board in Leaderboard.entries) {
            assertEquals(board, Leaderboard.of(board.key))
        }
        assertEquals(null, Leaderboard.of("nonsense"))
    }
}
