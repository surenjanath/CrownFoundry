package com.surenjanath.crownfoundry.leaderboard

import com.surenjanath.crownfoundry.api.Side
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
    );

    companion object {
        fun of(key: String): Leaderboard? = entries.firstOrNull { it.key == key }
    }
}

/** Every board's current value for this device. */
data class LeaderboardScores(
    val wins: Int = 0,
    val streak: Int = 0,
    val puzzles: Int = 0
) {
    operator fun get(board: Leaderboard): Int = when (board) {
        Leaderboard.Wins -> wins
        Leaderboard.Streak -> streak
        Leaderboard.Puzzles -> puzzles
    }
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
    var streak = 0
    var best = 0
    for (match in played) {
        if (match.winner == Side.HUMAN) {
            wins++
            streak++
            best = maxOf(best, streak)
        } else {
            streak = 0
        }
    }

    return LeaderboardScores(
        wins = wins,
        streak = best,
        puzzles = puzzles.count { it.solved }
    )
}
