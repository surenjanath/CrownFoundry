package com.surenjanath.crownfoundry.ui.screens.training

import androidx.compose.runtime.Immutable
import com.surenjanath.crownfoundry.engine.BLACK
import com.surenjanath.crownfoundry.engine.DRAW_RESULT
import com.surenjanath.crownfoundry.engine.OPPONENT_GREEDY
import com.surenjanath.crownfoundry.engine.OPPONENT_RANDOM
import com.surenjanath.crownfoundry.engine.SelfPlayProgress
import com.surenjanath.crownfoundry.engine.SelfPlayReport

/**
 * What the training screen is showing, kept out of the composable so it can be tested.
 *
 * The running tally is deliberately the *learner's* record rather than Black's. A session
 * alternates seats every game, so a scoreboard kept by colour would swing with the seating and
 * tell the player nothing about whether the policy is doing well.
 */
@Immutable
data class TrainingState(
    val running: Boolean = false,
    val games: Int = DEFAULT_GAMES,
    val played: Int = 0,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val opponent: String = "",
    val report: SelfPlayReport? = null,
    val message: String? = null
) {
    val fraction: Float get() = if (games <= 0) 0f else played.toFloat() / games

    /** Where the curriculum has got to, in the words the settings screen uses. */
    val stage: String
        get() = when (opponent) {
            OPPONENT_RANDOM -> "warming up against random play"
            OPPONENT_GREEDY -> "playing a material grabber"
            else -> "playing itself"
        }

    val tally: String get() = "$wins won · $draws drawn · $losses lost"

    /** Folds one finished game into the tally. */
    fun with(progress: SelfPlayProgress, learnerSide: Int): TrainingState = copy(
        played = progress.gameIndex,
        opponent = progress.opponent,
        wins = wins + if (progress.winner == learnerSide) 1 else 0,
        draws = draws + if (progress.winner == DRAW_RESULT || progress.winner == null) 1 else 0,
        losses = losses + if (
            progress.winner != null &&
            progress.winner != DRAW_RESULT &&
            progress.winner != learnerSide
        ) 1 else 0
    )

    companion object {
        const val DEFAULT_GAMES = 50

        /**
         * Which seat the learner took on game [index], matching [SelfPlayTrainer]'s alternation.
         *
         * Duplicated here rather than reported through [SelfPlayProgress] because it is one
         * expression and threading it through the engine's progress type would put a presentation
         * concern in the training loop.
         */
        fun learnerSideOn(index: Int): Int = if (index % 2 == 1) BLACK else 1 - BLACK
    }
}

/** The sentence shown when a session ends. */
fun describeOutcome(report: SelfPlayReport): String = when {
    report.gamesPlayed == 0 -> "No games were played."
    report.kept -> "Kept. It won ${percent(report.verdict.match)} against the weights it " +
            "replaced, over ${report.gamesPlayed} games of practice."
    else -> "Discarded — ${report.verdict.reason}. The old weights are still installed, and the " +
            "${report.gamesPlayed} games it played are kept for next time."
}

private fun percent(value: Float) = "${Math.round(value * 100)}%"
