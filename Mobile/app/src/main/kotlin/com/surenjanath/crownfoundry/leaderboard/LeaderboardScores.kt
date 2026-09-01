package com.surenjanath.crownfoundry.leaderboard

import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.RatedGame
import com.surenjanath.crownfoundry.engine.Rating
import com.surenjanath.crownfoundry.engine.ratingFrom
import com.surenjanath.crownfoundry.engine.ratingOf
import com.surenjanath.crownfoundry.offline.LocalMatch
import com.surenjanath.crownfoundry.offline.Puzzle

/**
 * What there is to rank in a game that is played alone.
 *
 * Choosing these is most of the design work. A leaderboard for a single-player game against an
 * opponent that changes strength under you cannot rank "who is best" - the opponent one player
 * beat is not the opponent another played - so it ranks *what you did*, and each of these is
 * something a player can point at and set out to beat.
 *
 * Pass-and-play games are excluded from every board. They are two people at one phone and neither
 * of them is the account the score would be posted under.
 */
enum class Leaderboard(
    val key: String,
    val label: String,
    val description: String
) {
    Wins(
        key = "wins",
        label = "Games won",
        description = "Every game you have won against the engine."
    ),
    Streak(
        key = "streak",
        label = "Best win streak",
        description = "The longest run of wins you have put together without losing one."
    ),
    Puzzles(
        key = "puzzles",
        label = "Puzzles solved",
        description = "Positions you got wrong once and got right afterwards."
    ),
    RatingBoard(
        key = "rating",
        label = "Rating",
        description = "Your rating, from every game you have played against the engine."
    );

    companion object {
        fun of(key: String): Leaderboard? = entries.firstOrNull { it.key == key }
    }
}

/** Every board's current value for this device. */
data class LeaderboardScores(
    val wins: Int = 0,
    val streak: Int = 0,
    val puzzles: Int = 0,
    val rating: Rating = Rating(),
    /** Won, drawn and lost against the engine, for the achievements and the Insights tab. */
    val played: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    /** Wins in which the engine never took a piece. */
    val flawlessWins: Int = 0,
    /** Wins against the `hard` setting, which gets no handicap and never explores. */
    val hardWins: Int = 0
) {
    /**
     * An unrated player posts nothing to the rating board.
     *
     * A leaderboard is a comparison, and 1200 is not a result - it is the absence of one. Posting
     * it would seed the board with everyone who ever opened the app, all tied, above anyone who
     * has actually played and lost.
     */
    operator fun get(board: Leaderboard): Int = when (board) {
        Leaderboard.Wins -> wins
        Leaderboard.Streak -> streak
        Leaderboard.Puzzles -> puzzles
        Leaderboard.RatingBoard -> if (rating.gamesRated == 0) 0 else rating.value
    }

    /** Whether [board] has anything worth posting. */
    fun isPostable(board: Leaderboard): Boolean = this[board] > 0
}

/**
 * Score the local corpus.
 *
 * A draw breaks a streak without being a loss, which is the reading a player expects: "five in a
 * row" means five wins, not five games that were not defeats. An unfinished game is not a result
 * and is skipped entirely rather than treated as either.
 */
fun scoresOf(matches: List<LocalMatch>, puzzles: List<Puzzle>): LeaderboardScores {
    val played = matches
        .filter { it.isFinished && !it.isPassAndPlay }
        .sortedBy { it.finishedAt ?: it.startedAt }

    var wins = 0
    var losses = 0
    var draws = 0
    var streak = 0
    var best = 0
    var flawless = 0
    var hardWins = 0
    val rated = ArrayList<RatedGame>(played.size)

    for (match in played) {
        val score = when (match.winner) {
            Side.HUMAN -> 1.0
            Side.DRAW -> 0.5
            else -> 0.0
        }
        rated.add(RatedGame(ratingOf(match.difficulty), score))

        when (match.winner) {
            Side.HUMAN -> {
                wins++
                streak++
                best = maxOf(best, streak)
                // The engine never took a piece. `aiCaptures` counts what it captured, so zero is
                // a game it was never allowed into.
                if (match.aiCaptures == 0) flawless++
                if (match.difficulty.equals("hard", ignoreCase = true)) hardWins++
            }

            Side.DRAW -> {
                draws++
                streak = 0
            }

            else -> {
                losses++
                streak = 0
            }
        }
    }

    return LeaderboardScores(
        wins = wins,
        streak = best,
        puzzles = puzzles.count { it.solved },
        rating = ratingFrom(rated),
        played = played.size,
        losses = losses,
        draws = draws,
        flawlessWins = flawless,
        hardWins = hardWins
    )
}
