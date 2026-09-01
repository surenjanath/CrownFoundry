package com.surenjanath.crownfoundry.leaderboard

import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.EngineHeader
import com.surenjanath.crownfoundry.engine.STARTING_RATING
import com.surenjanath.crownfoundry.offline.LocalMatch
import com.surenjanath.crownfoundry.offline.Puzzle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AchievementsTest {

    private var clock = 0L

    private fun match(
        winner: String?,
        difficulty: String = "adaptive",
        aiCaptures: Int = 3,
        mode: String = LocalMatch.MODE_ENGINE
    ): LocalMatch {
        clock += 1000
        return LocalMatch(
            localId = "m$clock",
            matchId = "m$clock",
            difficulty = difficulty,
            startedAt = clock,
            finishedAt = if (winner == null) null else clock + 1,
            winner = winner,
            aiCaptures = aiCaptures,
            mode = mode
        )
    }

    private fun puzzle(solved: Boolean) = Puzzle(
        id = "p${clock++}", fen = "", best = "", played = "", solved = solved
    )

    private fun state(
        matches: List<LocalMatch> = emptyList(),
        puzzles: List<Puzzle> = emptyList(),
        header: EngineHeader? = null
    ) = achievementsOf(scoresOf(matches, puzzles), header)

    // --- the scores the achievements are built on ------------------------------------------

    @Test
    fun `a fresh install has earned nothing`() {
        val earned = state()
        assertEquals(0, earned.unlockedCount)
        assertFalse(Achievement.FirstWin in earned)
    }

    @Test
    fun `the record counts wins, losses and draws`() {
        val scores = scoresOf(
            listOf(
                match(Side.HUMAN), match(Side.AI), match(Side.DRAW), match(Side.AI), match(null)
            ),
            emptyList()
        )
        assertEquals(4, scores.played)
        assertEquals(1, scores.wins)
        assertEquals(2, scores.losses)
        assertEquals(1, scores.draws)
    }

    @Test
    fun `an unrated player is not posted to the rating board`() {
        val scores = scoresOf(emptyList(), emptyList())
        assertEquals(STARTING_RATING, scores.rating.value)
        // The rating exists, but there is no result behind it, so there is nothing to compare.
        assertEquals(0, scores[Leaderboard.RatingBoard])
        assertFalse(scores.isPostable(Leaderboard.RatingBoard))
    }

    @Test
    fun `a rated player is posted`() {
        val scores = scoresOf(List(3) { match(Side.HUMAN) }, emptyList())
        assertTrue(scores.rating.value > STARTING_RATING)
        assertEquals(scores.rating.value, scores[Leaderboard.RatingBoard])
        assertTrue(scores.isPostable(Leaderboard.RatingBoard))
    }

    @Test
    fun `pass-and-play never affects the rating`() {
        val scores = scoresOf(
            List(5) { match(Side.HUMAN, mode = LocalMatch.MODE_PASS) },
            emptyList()
        )
        assertEquals(0, scores.rating.gamesRated)
        assertEquals(0, scores.played)
    }

    // --- one-off achievements ---------------------------------------------------------------

    @Test
    fun `the first win unlocks first blood`() {
        assertFalse(Achievement.FirstWin in state(listOf(match(Side.AI))))
        assertTrue(Achievement.FirstWin in state(listOf(match(Side.HUMAN))))
    }

    @Test
    fun `a win in which the engine took nothing is flawless`() {
        assertFalse(Achievement.Flawless in state(listOf(match(Side.HUMAN, aiCaptures = 1))))
        assertTrue(Achievement.Flawless in state(listOf(match(Side.HUMAN, aiCaptures = 0))))
    }

    /** A loss where it happened to take nothing is still a loss. */
    @Test
    fun `flawless needs the win, not just the clean sheet`() {
        assertFalse(Achievement.Flawless in state(listOf(match(Side.AI, aiCaptures = 0))))
    }

    @Test
    fun `beating hard unlocks no handicap, and beating easy does not`() {
        assertFalse(Achievement.BeatHard in state(listOf(match(Side.HUMAN, difficulty = "easy"))))
        assertTrue(Achievement.BeatHard in state(listOf(match(Side.HUMAN, difficulty = "hard"))))
    }

    @Test
    fun `a kept practice session unlocks sparring partner`() {
        assertFalse(Achievement.Sparring in state(header = EngineHeader(selfPlaySessions = 0)))
        assertTrue(Achievement.Sparring in state(header = EngineHeader(selfPlaySessions = 1)))
    }

    @Test
    fun `a missing engine unlocks nothing rather than throwing`() {
        assertFalse(Achievement.Sparring in state(header = null))
    }

    // --- counted achievements ---------------------------------------------------------------

    @Test
    fun `a five-game streak unlocks, and four does not`() {
        assertFalse(Achievement.Streak5 in state(List(4) { match(Side.HUMAN) }))
        assertTrue(Achievement.Streak5 in state(List(5) { match(Side.HUMAN) }))
    }

    @Test
    fun `progress is reported for the counted ones`() {
        val earned = state(List(3) { match(Side.HUMAN) }, List(4) { puzzle(solved = true) })
        assertEquals(3, earned.progressOf(Achievement.Streak5))
        assertEquals(4, earned.progressOf(Achievement.Puzzles10))
        assertEquals(3, earned.progressOf(Achievement.Centurion))
    }

    @Test
    fun `ten solved puzzles unlocks, unsolved ones do not count`() {
        assertFalse(
            Achievement.Puzzles10 in
                state(puzzles = List(9) { puzzle(true) } + List(5) { puzzle(false) })
        )
        assertTrue(Achievement.Puzzles10 in state(puzzles = List(10) { puzzle(true) }))
    }

    /** An unrated player is not most of the way to a rating they have not earned. */
    @Test
    fun `rating progress is zero until there is a rating`() {
        assertEquals(0, state().progressOf(Achievement.Rated1400))
        assertTrue(state(List(3) { match(Side.HUMAN) }).progressOf(Achievement.Rated1400) > 0)
    }

    @Test
    fun `reaching 1400 unlocks even if the rating later falls`() {
        val climb = List(14) { match(Side.HUMAN, difficulty = "hard") }
        val slump = List(14) { match(Side.AI, difficulty = "easy") }

        val earned = state(climb + slump)
        assertTrue("peak never reached 1400", Achievement.Rated1400 in earned)
    }

    // --- the keys the console ids are configured under ---------------------------------------

    /**
     * Two boards pointing at one console id would silently post both scores to the same board.
     * The ids live in `gradle.properties` and are parsed by key, so nothing else would notice.
     */
    @Test
    fun `board keys are unique`() {
        assertEquals(
            Leaderboard.entries.size,
            Leaderboard.entries.map { it.key }.toSet().size
        )
    }

    @Test
    fun `every achievement is addressable by its key`() {
        for (achievement in Achievement.entries) {
            assertEquals(achievement, Achievement.of(achievement.key))
        }
        assertEquals(null, Achievement.of("nonsense"))
    }

    /** A counter that starts at 1200 and ends at 1400 is not a progress bar anyone can read. */
    @Test
    fun `the rating achievement is an unlock, not a counter`() {
        assertFalse(Achievement.Rated1400.incremental)
        assertTrue(Achievement.Rated1400.target > 0)

        for (counted in listOf(Achievement.Streak5, Achievement.Puzzles10, Achievement.Centurion)) {
            assertTrue("$counted should be incremental", counted.incremental)
        }
        for (oneOff in listOf(Achievement.FirstWin, Achievement.Flawless, Achievement.BeatHard)) {
            assertFalse("$oneOff should not be incremental", oneOff.incremental)
        }
    }

    @Test
    fun `keys and labels are unique`() {
        assertEquals(
            Achievement.entries.size,
            Achievement.entries.map { it.key }.toSet().size
        )
        assertEquals(
            Achievement.entries.size,
            Achievement.entries.map { it.label }.toSet().size
        )
    }
}
