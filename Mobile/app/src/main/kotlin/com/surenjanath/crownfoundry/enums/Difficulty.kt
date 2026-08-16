package com.surenjanath.crownfoundry.enums

/**
 * How hard the opponent tries - and, for [Adaptive], who it is trying against.
 *
 * [wire] is the value the referee expects in `POST /api/match/start/`.
 */
enum class Difficulty(
    val wire: String,
    val label: String,
    val description: String
) {
    Easy(
        wire = "easy",
        label = "Easy",
        description = "It takes the first reasonable move it finds and lets your mistakes stand."
    ),
    Normal(
        wire = "normal",
        label = "Normal",
        description = "It looks a couple of moves ahead and punishes anything you leave hanging."
    ),
    Hard(
        wire = "hard",
        label = "Hard",
        description = "Full search depth and no experiments: it plays the best move it currently knows."
    ),
    Adaptive(
        wire = "adaptive",
        label = "Adaptive",
        description = "It tunes itself to you. Every match you play becomes training data, the " +
                "moves that lost it a game are penalised, and it keeps changing until it is the " +
                "player that beats you specifically."
    );

    companion object {
        /** Anything the backend sends that this build does not recognise reads as [Adaptive]. */
        fun fromWire(wire: String?): Difficulty =
            entries.firstOrNull { it.wire.equals(wire, ignoreCase = true) } ?: Adaptive
    }
}
