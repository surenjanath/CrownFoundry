package com.surenjanath.crownfoundry.ui.screens.matches

import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.AnalysedMove
import com.surenjanath.crownfoundry.engine.BLACK
import com.surenjanath.crownfoundry.engine.Board
import com.surenjanath.crownfoundry.engine.GameAnalyser
import com.surenjanath.crownfoundry.engine.GameAnalysis
import com.surenjanath.crownfoundry.engine.MoveQuality
import com.surenjanath.crownfoundry.engine.Ply
import com.surenjanath.crownfoundry.engine.WHITE
import com.surenjanath.crownfoundry.engine.replayMoves
import com.surenjanath.crownfoundry.offline.EngineStore
import com.surenjanath.crownfoundry.offline.toEngineRules

/**
 * The review screen's side of post-game analysis.
 *
 * The engine scores the game; this file decides what the player is told about it. The split
 * matters because the two answer different questions: `GameAnalyser` says a move lost 3.1, and
 * this says that was the game's turning point and it was yours.
 *
 * Analysis runs on the phone against the downloaded policy, so a player with no engine installed
 * gets an explanation rather than a spinner that never resolves - the review itself still works,
 * because the moves came from the backend and were never the engine's to provide.
 */
sealed interface ReviewAnalysis {

    /** Nothing asked for yet. */
    data object Idle : ReviewAnalysis

    /** There is an answer, and it is "not for this game". [reason] is shown as written. */
    data class Unavailable(val reason: String) : ReviewAnalysis

    data class Running(val done: Int, val total: Int) : ReviewAnalysis {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    data class Ready(val analysis: GameAnalysis) : ReviewAnalysis

    data class Failed(val message: String) : ReviewAnalysis

    val isRunning get() = this is Running

    /** The scored move at [plyIndex], where 0 is the opening and 1 is the first move played. */
    fun moveAt(plyIndex: Int): AnalysedMove? =
        (this as? Ready)?.analysis?.moves?.getOrNull(plyIndex - 1)
}

/**
 * Runs the search over a finished game. An interface so the review can be tested without an
 * engine artifact on disk, which no unit test has.
 */
fun interface MatchAnalyser {
    /** `null` when there is no on-device engine, which is not a failure - just an absence. */
    suspend fun analyse(plies: List<Ply>, onProgress: (done: Int, total: Int) -> Unit): GameAnalysis?
}

/**
 * The real one: the installed policy, under the same lock the game itself uses.
 *
 * Holding that lock for the length of an analysis is deliberate. It costs nothing here - a match
 * being reviewed is a match already over - and it guarantees every ply is scored by one set of
 * weights, rather than by two models either side of a background training run.
 */
object EngineMatchAnalyser : MatchAnalyser {
    override suspend fun analyse(
        plies: List<Ply>,
        onProgress: (done: Int, total: Int) -> Unit
    ): GameAnalysis? = EngineStore.withNetwork { net ->
        GameAnalyser(net).analyse(plies, onProgress)
    }
}

/** The engine's side constant for a wire side string. The human is Black, the AI is White. */
fun engineSideOf(side: String?): Int = if (side == Side.AI) WHITE else BLACK

/**
 * Replay a stored match through the rules engine.
 *
 * Empty when the game cannot be replayed - an unreadable opening FEN, or a move the rules reject.
 * `replayMoves` stops at the first move it cannot parse, so a partly-corrupt history is analysed
 * as far as it goes rather than thrown away.
 */
fun replayOf(match: MatchDto): List<Ply> {
    val rules = match.rules.toEngineRules()
    val opening = match.initialBoard
        ?.let { fen -> runCatching { Board.fromFen(fen, rules = rules) }.getOrNull() }
        ?: Board.initial(rules)

    return replayMoves(match.history.map { it.move }, rules, opening)
}

/** What the review says while it has no analysis to show. `null` once there is one. */
fun analysisNotice(state: ReviewAnalysis): String? = when (state) {
    is ReviewAnalysis.Idle -> null
    is ReviewAnalysis.Ready -> null
    is ReviewAnalysis.Running -> "Scoring move ${state.done} of ${state.total}…"
    is ReviewAnalysis.Unavailable -> state.reason
    is ReviewAnalysis.Failed -> state.message
}

/** "Blunder · best was 11-15" - the one line under a move that was not the best one. */
fun verdictOf(move: AnalysedMove): String = when {
    move.wasBest -> "Best move"
    move.quality == MoveQuality.Good -> "Good · engine liked ${move.best}"
    else -> "${move.quality.label} · best was ${move.best}"
}

/** How much a move gave up, in men, for the line under [verdictOf]. */
fun costOf(move: AnalysedMove): String? {
    if (move.wasBest || move.loss < 0.05f) return null
    val men = if (move.loss >= 1f) "%.1f".format(move.loss) else "%.2f".format(move.loss)
    return "Gave up $men against the engine's choice."
}

/**
 * The two-line summary at the top of an analysed game.
 *
 * Accuracy is the share of moves the engine rated best or good, which is the number every other
 * review screen shows and the one people already know how to read.
 */
data class AnalysisSummary(
    val accuracy: Int,
    val opponentAccuracy: Int,
    val mistakes: Int,
    val blunders: Int,
    val turningPoint: AnalysedMove?,
    val turningPointWasYours: Boolean,
    /** Two people played this, so neither side can be addressed as "you". */
    val passAndPlay: Boolean = false
) {
    val blackLabel get() = if (passAndPlay) "Black" else "you"
    val whiteLabel get() = if (passAndPlay) "White" else "it"

    val headline: String
        get() = if (passAndPlay) {
            "Black played $accuracy% accurately, White played $opponentAccuracy%."
        } else {
            "You played $accuracy% accurately, it played $opponentAccuracy%."
        }

    val detail: String
        get() {
            val faults = buildList {
                if (blunders > 0) add("$blunders ${plural(blunders, "blunder")}")
                if (mistakes > 0) add("$mistakes ${plural(mistakes, "mistake")}")
            }
            if (faults.isEmpty()) return "Nothing the engine would call a mistake. Well played."
            val whose = if (passAndPlay) "Black's" else "your"
            return "${faults.joinToString(" and ")} in $whose moves."
        }

    /** The single sentence worth reading if the player reads nothing else. */
    val turningPointLine: String?
        get() {
            val move = turningPoint ?: return null
            val who = when {
                passAndPlay && turningPointWasYours -> "Black's"
                passAndPlay -> "White's"
                turningPointWasYours -> "Your"
                else -> "Its"
            }
            return "$who move ${move.ply}, ${move.notation}, cost the most - ${move.best} " +
                "was there instead."
        }
}

fun summaryOf(analysis: GameAnalysis, passAndPlay: Boolean = false): AnalysisSummary? {
    if (analysis.isEmpty) return null

    val turningPoint = analysis.turningPoint()

    return AnalysisSummary(
        accuracy = percent(analysis.accuracy(BLACK)),
        opponentAccuracy = percent(analysis.accuracy(WHITE)),
        mistakes = analysis.count(BLACK, MoveQuality.Mistake),
        blunders = analysis.count(BLACK, MoveQuality.Blunder),
        turningPoint = turningPoint,
        turningPointWasYours = turningPoint?.side == BLACK,
        passAndPlay = passAndPlay
    )
}

private fun percent(fraction: Float) = Math.round(fraction * 100f)

private fun plural(count: Int, noun: String) = if (count == 1) noun else "${noun}s"
