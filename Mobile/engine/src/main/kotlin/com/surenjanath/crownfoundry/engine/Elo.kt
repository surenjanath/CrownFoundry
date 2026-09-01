package com.surenjanath.crownfoundry.engine

import kotlin.math.pow

/**
 * A rating for the player.
 *
 * The app has always shown the *engine's* Elo and never the human's, which is the wrong way round
 * for the person holding the phone: the opponent had a progress bar and they did not. This is the
 * missing half - the same arithmetic every rating system uses, run over the games already stored
 * on the device.
 *
 * It is recomputed from the whole corpus rather than carried forward as a number, for the same
 * reason [com.surenjanath.crownfoundry.engine.buildTransitions] recomputes returns: a stored
 * counter drifts the first time a game is written that the counter did not see, and there is no
 * way to notice afterwards. Recomputing is a few hundred floating-point operations over a corpus
 * capped at a hundred matches.
 */

/** The rating everyone starts at, and what an unrated player is assumed to be worth. */
const val STARTING_RATING = 1200

/**
 * Games before a rating stops being provisional.
 *
 * Ratings move fast at first so a new player reaches roughly the right place in a handful of
 * games rather than grinding there, and the UI says "provisional" until the fast phase is over -
 * a number that is still swinging by forty points a game should not be presented as if it were
 * settled.
 */
const val PROVISIONAL_GAMES = 10

/**
 * What each difficulty is worth, as a fixed rating.
 *
 * Fixed, rather than derived from the engine's own Elo in the artifact header. The header's number
 * comes from the server's own bookkeeping, it moves whenever a new policy is published, and it has
 * been wrong before - v42 claimed 1140 while beating both fixed baselines every game. Deriving the
 * player's rating from it would make everyone's rating shift under them whenever the opponent was
 * republished, through no play of their own.
 *
 * `adaptive` sits below `hard` on purpose. It searches at least as deep, but it spends part of its
 * strength modelling the player rather than maximising, and it is the default - so it is the
 * setting most results come from and the one worth pitching at the middle of the range.
 */
fun ratingOf(difficulty: String?): Int = when (difficulty?.trim()?.lowercase()) {
    "easy" -> 800
    "normal" -> 1100
    "hard" -> 1450
    else -> 1300
}

/** The expected score for a player rated [rating] against an opponent rated [opponent]. */
fun expectedScore(rating: Int, opponent: Int): Double =
    1.0 / (1.0 + 10.0.pow((opponent - rating) / 400.0))

/**
 * How far one result may move a rating.
 *
 * Large while provisional so the first games do most of the work, then small enough that a single
 * unlucky evening does not undo a month.
 */
fun kFactorFor(gamesPlayed: Int, rating: Int): Int = when {
    gamesPlayed < PROVISIONAL_GAMES -> 40
    rating >= 2100 -> 10
    else -> 20
}

/** One rated result: what the opponent was worth, and what the player scored against it. */
class RatedGame(@JvmField val opponentRating: Int, @JvmField val score: Double)

data class Rating(
    val value: Int = STARTING_RATING,
    val gamesRated: Int = 0,
    /** How the last rated game moved it, for the UI to show as `+12`. */
    val lastChange: Int = 0,
    val peak: Int = STARTING_RATING
) {
    /** Still in the fast phase, and should be presented as unsettled. */
    val isProvisional: Boolean get() = gamesRated < PROVISIONAL_GAMES

    val label: String get() = if (gamesRated == 0) "unrated" else "$value${if (isProvisional) "?" else ""}"
}

/**
 * Replay [games] in order and return the rating they produce.
 *
 * Order matters - the K-factor depends on how many games came before - so the caller has to hand
 * these over oldest first.
 */
fun ratingFrom(games: List<RatedGame>): Rating {
    var value = STARTING_RATING.toDouble()
    var peak = STARTING_RATING
    var lastChange = 0

    for ((index, game) in games.withIndex()) {
        val k = kFactorFor(index, value.toInt())
        val expected = expectedScore(value.toInt(), game.opponentRating)
        val delta = k * (game.score - expected)
        val before = value.toInt()
        value += delta
        lastChange = value.toInt() - before
        peak = maxOf(peak, value.toInt())
    }

    return Rating(
        value = value.toInt(),
        gamesRated = games.size,
        lastChange = lastChange,
        peak = peak
    )
}
