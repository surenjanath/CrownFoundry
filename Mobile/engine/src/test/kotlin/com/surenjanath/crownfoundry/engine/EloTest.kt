package com.surenjanath.crownfoundry.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EloTest {

    private fun wins(n: Int, against: Int) = List(n) { RatedGame(against, 1.0) }
    private fun losses(n: Int, against: Int) = List(n) { RatedGame(against, 0.0) }

    // --- the arithmetic -------------------------------------------------------------------

    @Test
    fun `an even matchup expects half a point`() {
        assertEquals(0.5, expectedScore(1200, 1200), 1e-9)
    }

    @Test
    fun `four hundred points is roughly ten to one`() {
        assertEquals(0.909, expectedScore(1600, 1200), 0.001)
        assertEquals(0.091, expectedScore(1200, 1600), 0.001)
    }

    @Test
    fun `expectations of the two sides sum to one`() {
        for (gap in listOf(0, 50, 200, 400, 800)) {
            assertEquals(
                1.0,
                expectedScore(1200, 1200 + gap) + expectedScore(1200 + gap, 1200),
                1e-9
            )
        }
    }

    // --- the K-factor ---------------------------------------------------------------------

    @Test
    fun `ratings move fastest while provisional`() {
        assertEquals(40, kFactorFor(0, 1200))
        assertEquals(40, kFactorFor(PROVISIONAL_GAMES - 1, 1200))
        assertEquals(20, kFactorFor(PROVISIONAL_GAMES, 1200))
    }

    @Test
    fun `a strong rating moves slowest`() {
        assertEquals(10, kFactorFor(200, 2100))
    }

    // --- replaying a corpus ---------------------------------------------------------------

    @Test
    fun `no games leaves an unrated player at the starting rating`() {
        val rating = ratingFrom(emptyList())
        assertEquals(STARTING_RATING, rating.value)
        assertEquals(0, rating.gamesRated)
        assertEquals("unrated", rating.label)
        assertTrue(rating.isProvisional)
    }

    @Test
    fun `beating a stronger opponent gains more than beating a weaker one`() {
        val overHard = ratingFrom(wins(1, ratingOf("hard"))).value - STARTING_RATING
        val overEasy = ratingFrom(wins(1, ratingOf("easy"))).value - STARTING_RATING

        assertTrue("hard win gained $overHard, easy win gained $overEasy", overHard > overEasy)
        assertTrue("beating easy should still gain something", overEasy > 0)
    }

    @Test
    fun `losing to a weaker opponent costs more than losing to a stronger one`() {
        val toEasy = STARTING_RATING - ratingFrom(losses(1, ratingOf("easy"))).value
        val toHard = STARTING_RATING - ratingFrom(losses(1, ratingOf("hard"))).value

        assertTrue("lost $toEasy to easy, $toHard to hard", toEasy > toHard)
    }

    @Test
    fun `a draw against an even opponent barely moves anything`() {
        val rating = ratingFrom(listOf(RatedGame(STARTING_RATING, 0.5)))
        assertEquals(STARTING_RATING, rating.value)
    }

    @Test
    fun `a win and a loss against the same opponent roughly cancel`() {
        val rating = ratingFrom(
            listOf(RatedGame(STARTING_RATING, 1.0), RatedGame(STARTING_RATING, 0.0))
        )
        assertTrue(
            "ended at ${rating.value}, expected within 2 of $STARTING_RATING",
            kotlin.math.abs(rating.value - STARTING_RATING) <= 2
        )
    }

    @Test
    fun `a rating stops being provisional after ten games`() {
        assertTrue(ratingFrom(wins(9, 1200)).isProvisional)
        assertFalse(ratingFrom(wins(10, 1200)).isProvisional)
        assertTrue(ratingFrom(wins(10, 1200)).label.none { it == '?' })
    }

    @Test
    fun `the label marks a provisional rating`() {
        assertTrue(ratingFrom(wins(3, 1200)).label.endsWith("?"))
    }

    /** A rating earned and then lost was still earned, which is what unlocks the achievement. */
    @Test
    fun `peak remembers the best it ever was`() {
        val rating = ratingFrom(wins(12, ratingOf("hard")) + losses(12, ratingOf("easy")))
        assertTrue("peak ${rating.peak} was not above the final ${rating.value}",
            rating.peak > rating.value)
    }

    @Test
    fun `the last change is reported for the most recent game only`() {
        val rating = ratingFrom(wins(4, 1200) + losses(1, 1200))
        assertTrue("last change ${rating.lastChange} should be a loss", rating.lastChange < 0)
    }

    /** Order changes the K-factor, so the caller has to hand games over oldest first. */
    @Test
    fun `order matters`() {
        val hardFirst = ratingFrom(wins(1, ratingOf("hard")) + wins(14, ratingOf("easy")))
        val hardLast = ratingFrom(wins(14, ratingOf("easy")) + wins(1, ratingOf("hard")))
        assertTrue(hardFirst.value != hardLast.value)
    }

    // --- what each difficulty is worth -----------------------------------------------------

    @Test
    fun `the difficulties are ordered, and anything unknown reads as adaptive`() {
        assertTrue(ratingOf("easy") < ratingOf("normal"))
        assertTrue(ratingOf("normal") < ratingOf("adaptive"))
        assertTrue(ratingOf("adaptive") < ratingOf("hard"))

        assertEquals(ratingOf("adaptive"), ratingOf(null))
        assertEquals(ratingOf("adaptive"), ratingOf("something else"))
        assertEquals(ratingOf("hard"), ratingOf("  HARD "))
    }

    /** A long run against one setting should settle near that setting's rating. */
    @Test
    fun `an even record against one difficulty converges on its rating`() {
        val target = ratingOf("normal")
        val games = ArrayList<RatedGame>()
        repeat(60) { games.add(RatedGame(target, if (it % 2 == 0) 1.0 else 0.0)) }

        val rating = ratingFrom(games)
        assertTrue(
            "settled at ${rating.value}, expected near $target",
            kotlin.math.abs(rating.value - target) < 120
        )
    }
}
