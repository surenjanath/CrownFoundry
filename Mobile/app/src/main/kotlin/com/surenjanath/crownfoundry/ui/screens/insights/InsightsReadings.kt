package com.surenjanath.crownfoundry.ui.screens.insights

import com.surenjanath.crownfoundry.api.AnalyticsSummaryDto
import com.surenjanath.crownfoundry.api.CapturePointDto
import com.surenjanath.crownfoundry.api.GameLengthPointDto
import com.surenjanath.crownfoundry.api.MistakePointDto
import com.surenjanath.crownfoundry.api.TrainingPointDto
import com.surenjanath.crownfoundry.api.WinRatePointDto
import com.surenjanath.crownfoundry.ui.components.charts.cumulativeAverage
import com.surenjanath.crownfoundry.ui.components.charts.rollingAverage
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything on the Insights screen that turns a number into a sentence.
 *
 * A rising line is not self-explanatory, and a chart the reader has to interpret unaided is a
 * chart that proves nothing. Each of these says what the shape means - and says "not yet" plainly
 * when the honest answer is that there is not enough data.
 *
 * Pure functions, deliberately: this is the part of the screen worth testing.
 */

/** The AI's score for one finished match: a win is 1, a draw is half, a loss is nothing. */
fun resultValue(result: String?): Float? = when (result?.trim()?.lowercase(Locale.ROOT)) {
    "win", "ai", "ai_win", "white" -> 1f
    "draw" -> 0.5f
    "loss", "lose", "human", "human_win", "black" -> 0f
    else -> null
}

/** The two lines of the win-rate chart: the record so far, and the recent form. */
data class WinRateCurves(
    val cumulative: List<Float>,
    val rolling: List<Float>
)

/**
 * Derives both curves from the per-match results when the backend sends them, and falls back to
 * the numbers the backend already computed when it does not. Deriving locally means the rolling
 * window is the one this screen documents rather than whichever one the backend happened to use.
 */
fun winRateCurves(series: List<WinRatePointDto>, window: Int = 10): WinRateCurves {
    if (series.isEmpty()) return WinRateCurves(emptyList(), emptyList())

    val results = series.map { resultValue(it.result) }

    if (results.any { it == null }) {
        return WinRateCurves(
            cumulative = series.map { it.cumulativeWinRate.toFloat() },
            rolling = series.map { it.rollingWinRate.toFloat() }
        )
    }

    val values = results.filterNotNull()

    return WinRateCurves(
        cumulative = cumulativeAverage(values),
        rolling = rollingAverage(values, window)
    )
}

/** The PRD's headline question: how many games did it take to cross 50%? */
fun gamesToFiftyReading(summary: AnalyticsSummaryDto): String {
    val crossing = summary.gamesTo50Percent

    return when {
        crossing != null && crossing > 0 ->
            "It crossed 50% after $crossing ${matches(crossing)}."

        crossing != null ->
            "It was above 50% from the first match, which says more about the sample than the policy."

        summary.totalMatches == 0 ->
            "No games yet, so there is no curve to cross."

        else ->
            "It has not crossed 50% yet, ${summary.totalMatches} ${matches(summary.totalMatches)} in."
    }
}

/** One line on where the opponent is in its arc, for the Play tab's status card. */
fun aiArcReading(summary: AnalyticsSummaryDto): String {
    val rate = summary.aiWinRate

    return when {
        summary.totalMatches == 0 ->
            "No games yet. The first one you finish is the first thing it learns from."

        summary.aiWins == 0 ->
            "It has not beaten you yet, ${summary.totalMatches} ${matches(summary.totalMatches)} in."

        abs(rate - 0.5) < 0.005 ->
            "It is level with you, winning ${fractionPhrase(rate)}."

        rate < 0.5 ->
            "It is winning ${fractionPhrase(rate)}, and still behind you."

        else ->
            "It is winning ${fractionPhrase(rate)} now, and it is ahead of you."
    }
}

/**
 * A rate as something a person would say out loud. "0.63" is a number; "two games in three" is
 * the thing the number means.
 */
fun fractionPhrase(rate: Double): String {
    val value = if (rate.isNaN()) 0.0 else rate.coerceIn(0.0, 1.0)

    return FRACTIONS.minByOrNull { abs(it.first - value) }?.second ?: "one game in two"
}

private val FRACTIONS = listOf(
    0.0 to "nothing at all",
    0.1 to "one game in ten",
    0.2 to "one game in five",
    0.25 to "one game in four",
    1.0 / 3.0 to "one game in three",
    0.4 to "two games in five",
    0.5 to "one game in two",
    0.6 to "three games in five",
    2.0 / 3.0 to "two games in three",
    0.75 to "three games in four",
    0.8 to "four games in five",
    0.9 to "nine games in ten",
    1.0 to "every game"
)

/** The win/loss/draw split as whole percentages that add up to a hundred. */
data class RecordSplit(
    val winPercent: Int,
    val lossPercent: Int,
    val drawPercent: Int
) {
    val total: Int get() = winPercent + lossPercent + drawPercent
}

fun recordSplit(summary: AnalyticsSummaryDto): RecordSplit {
    val counts = listOf(summary.aiWins, summary.humanWins, summary.draws)
    val total = counts.sum()
    if (total <= 0) return RecordSplit(0, 0, 0)

    val exact = counts.map { it * 100.0 / total }
    val floors = exact.map { it.toInt() }.toMutableList()

    // Largest remainder, so three percentages of a real split always read as a hundred.
    var remaining = 100 - floors.sum()
    val order = exact.indices.sortedByDescending { exact[it] - floors[it] }
    var cursor = 0
    while (remaining > 0 && order.isNotEmpty()) {
        floors[order[cursor % order.size]] += 1
        cursor += 1
        remaining -= 1
    }

    return RecordSplit(floors[0], floors[1], floors[2])
}

/** Whether the penalty system is visibly working, in one line. */
fun mistakeReading(series: List<MistakePointDto>): String {
    if (series.isEmpty()) {
        return "Nothing to compare yet. This line only means something once it has had a chance " +
                "to repeat itself."
    }

    val first = series.first().rate
    val last = series.last().rate

    return when {
        last <= 0.0005 ->
            "It is no longer repeating moves it has been punished for. A line falling to zero is " +
                    "the penalty doing its job."

        last < first - 0.0005 ->
            "Down from ${percent(first)} to ${percent(last)}. A falling line is the penalty " +
                    "taking: the moves that cost it a game are not coming back."

        last > first + 0.0005 ->
            "Up from ${percent(first)} to ${percent(last)}. It is still walking into moves it has " +
                    "already been punished for."

        else ->
            "Flat at ${percent(last)}. The penalty has not yet changed how often it repeats a " +
                    "punished move."
    }
}

/** The PRD's predicted arc for match length: longer as it defends, then shorter as it finishes. */
fun gameLengthReading(series: List<GameLengthPointDto>): String {
    if (series.isEmpty()) return "No finished matches yet."

    val turns = series.map { it.turns.toDouble() }

    if (turns.size < 4) {
        return "Too few matches to read a trend. The average so far is ${round(turns.average())} turns."
    }

    if (turns.size >= 6) {
        val third = turns.size / 3
        val early = turns.take(third).average()
        val middle = turns.drop(third).dropLast(third).average()
        val late = turns.takeLast(third).average()

        if (middle > early + 1 && late < middle - 1) {
            return "Up from ${round(early)} turns to ${round(middle)}, then back down to " +
                    "${round(late)} - defence first, then the ability to finish. That is the " +
                    "shape this was supposed to make."
        }
    }

    val half = turns.size / 2
    val early = turns.take(half).average()
    val late = turns.drop(half).average()

    return when {
        late > early + 1 ->
            "Getting longer: ${round(early)} turns then, ${round(late)} now. It is surviving " +
                    "positions it used to lose."

        late < early - 1 ->
            "Getting shorter: ${round(early)} turns then, ${round(late)} now. It is closing games " +
                    "out instead of hanging on."

        else ->
            "Holding around ${round(late)} turns a match."
    }
}

/** Who is taking pieces off whom. */
fun captureReading(series: List<CapturePointDto>, summary: AnalyticsSummaryDto): String {
    if (series.isEmpty()) return "No captures recorded yet."

    val ai = series.sumOf { it.aiCaptures }
    val human = series.sumOf { it.humanCaptures }

    return when {
        human == 0 && ai == 0 -> "Nobody has taken a piece yet."
        human == 0 -> "It has taken $ai of your men and lost none of its own."
        else -> "It has taken $ai of your men to your $human of its - " +
                "${ratio(summary.captureRatio.takeIf { it > 0.0 } ?: (ai.toDouble() / human))} " +
                "pieces for every one it gives up."
    }
}

/** Where the policy is, and which way its loss is going. */
fun trainingReading(training: List<TrainingPointDto>): String {
    if (training.isEmpty()) return "No training runs recorded yet."

    val last = training.last()
    val first = training.first()

    val direction = when {
        training.size < 2 -> "the first run on record"
        last.loss < first.loss - 1e-6 -> "down from ${ratio(first.loss)}"
        last.loss > first.loss + 1e-6 -> "up from ${ratio(first.loss)}"
        else -> "unchanged from ${ratio(first.loss)}"
    }

    return "Policy v${last.policyVersion}, trained on ${last.gamesTrained} " +
            "${games(last.gamesTrained)}. Last loss ${ratio(last.loss)}, $direction."
}

// --- small formatting helpers ------------------------------------------------------------------

fun percent(rate: Double): String = "${(rate * 100).roundToInt()}%"

fun elo(value: Int): String = value.toString()

private fun ratio(value: Double): String =
    if (!value.isFinite()) "-" else String.format(Locale.ROOT, "%.2f", value)

private fun round(value: Double): Int = if (value.isFinite()) value.roundToInt() else 0

private fun matches(count: Int) = if (count == 1) "match" else "matches"

private fun games(count: Int) = if (count == 1) "game" else "games"
