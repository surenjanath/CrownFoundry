package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.Board
import com.surenjanath.crownfoundry.engine.VariantRules
import com.surenjanath.crownfoundry.engine.randomOpening
import java.time.LocalDate
import kotlin.random.Random

/**
 * One position a day, the same for everybody.
 *
 * The whole thing is derived from the date, so it needs no server and no content pipeline: the
 * seed is the day number, [randomOpening] turns it into a position, and two devices on the same
 * calendar day compute the same board without ever talking to each other. That is the same
 * function the training gate uses to vary its openings, which is why it is already known to
 * produce live, playable middlegames rather than decided ones.
 *
 * The difficulty is fixed at `hard`. A daily is a comparison between players, and `adaptive`
 * tunes itself to whoever is playing - so an adaptive daily would be a different opponent for
 * every person and the comparison would mean nothing.
 */
object DailyChallenge {

    /** The setting every daily is played at. Never adaptive; see above. */
    const val DIFFICULTY = "hard"

    /**
     * How far into a game a daily starts.
     *
     * Six plies is enough to be somewhere neither player has memorised and few enough that
     * neither side has been handed a won position to convert.
     */
    private const val OPENING_PLIES = 6

    /** Dailies are always played under the standard rules, or a variant would change the answer. */
    val RULES: MatchRulesDto = MatchRulesDto(
        flyingKings = true,
        menCaptureBackwards = true,
        mandatoryCapture = true
    )

    fun keyFor(date: LocalDate): String = date.toString()

    fun today(): String = keyFor(LocalDate.now())

    /**
     * The position for [key], as a FEN.
     *
     * Derived from the date alone. An unparseable key falls back to the opening rather than
     * throwing, because a stored match carrying a key this build does not understand should still
     * be openable.
     */
    fun positionFor(key: String): Board {
        val day = try {
            LocalDate.parse(key).toEpochDay()
        } catch (failure: Exception) {
            return Board.initial(RULES.toEngineRules())
        }
        return randomOpening(Random(day), OPENING_PLIES, RULES.toEngineRules())
    }

    /** How today's challenge stands, and how the ones before it went. */
    data class Status(
        val key: String,
        /** The attempt at today's, if there is one. */
        val attempt: LocalMatch? = null,
        /** Consecutive days ending today (or yesterday) whose challenge was won. */
        val streak: Int = 0,
        val totalSolved: Int = 0
    ) {
        val isPlayed: Boolean get() = attempt?.isFinished == true
        val isWon: Boolean get() = attempt?.winner == Side.HUMAN
        val isInProgress: Boolean get() = attempt != null && !attempt.isFinished

        val headline: String
            get() = when {
                isInProgress -> "Today's challenge, in progress"
                isWon -> "Today's challenge: solved"
                isPlayed -> "Today's challenge: not this time"
                else -> "Today's challenge"
            }
    }

    /**
     * Read [matches] for how the daily stands on [key].
     *
     * The streak counts backwards from today over *won* challenges, and tolerates today being
     * unplayed - a streak should not be reported as broken at one minute past midnight, before
     * the player has had any chance to keep it.
     */
    fun statusOf(matches: List<LocalMatch>, key: String = today()): Status {
        val byDate = matches.filter { it.isDaily }.associateBy { it.daily }
        val today = try {
            LocalDate.parse(key)
        } catch (failure: Exception) {
            return Status(key = key, attempt = byDate[key])
        }

        var streak = 0
        var day = today
        // Only an *unfinished* today is skipped over. A day that has been played and lost breaks
        // the run like any other loss - stepping back past it would report a streak the player no
        // longer has.
        val attempt = byDate[keyFor(today)]
        if (attempt == null || !attempt.isFinished) day = today.minusDays(1)
        while (byDate[keyFor(day)]?.winner == Side.HUMAN) {
            streak++
            day = day.minusDays(1)
        }

        return Status(
            key = key,
            attempt = byDate[key],
            streak = streak,
            totalSolved = byDate.values.count { it.winner == Side.HUMAN }
        )
    }
}
