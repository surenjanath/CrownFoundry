package com.surenjanath.crownfoundry.ui.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.api.PerformanceDto
import com.surenjanath.crownfoundry.ui.components.ShimmerHost
import com.surenjanath.crownfoundry.ui.components.charts.BarChart
import com.surenjanath.crownfoundry.ui.components.charts.BarGroup
import com.surenjanath.crownfoundry.ui.components.charts.ChartLegend
import com.surenjanath.crownfoundry.ui.components.charts.ChartSeries
import com.surenjanath.crownfoundry.ui.components.charts.LineChart
import com.surenjanath.crownfoundry.ui.components.charts.ReferenceLine
import com.surenjanath.crownfoundry.ui.components.charts.Sparkline
import com.surenjanath.crownfoundry.ui.components.charts.StatTile
import com.surenjanath.crownfoundry.ui.components.charts.ValueRange
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.components.themed.SecondaryTextButton
import com.surenjanath.crownfoundry.ui.components.themed.TextPlaceholder
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.ui.styling.shimmer
import com.surenjanath.crownfoundry.utils.backendUrlKey
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.defaultBackendUrl
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.rememberPreference
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold
import kotlin.math.roundToInt

/** The window the win-rate chart's second line averages over. */
private const val ROLLING_WINDOW = 10

/**
 * The screen that has to prove the claim: that the opponent is actually learning, and not merely
 * being described as learning. Every chart here is paired with the sentence that says what its
 * shape means, because a rising line on its own proves nothing to a reader.
 */
@Composable
fun InsightsScreen() {
    val (colorPalette, typography) = LocalAppearance.current

    val backendUrl by rememberPreference(backendUrlKey, defaultBackendUrl)
    var attempt by remember { mutableIntStateOf(0) }
    val holder = remember { InsightsStateHolder(Offline.api) }

    LaunchedEffect(backendUrl, attempt) {
        CrownFoundryClient.baseUrl = backendUrl
        holder.load()
    }

    val state = holder.state

    Column(
        modifier = Modifier
            .background(colorPalette.background0)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                LocalWindowInsets.current
                    .only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                    .asPaddingValues()
            )
    ) {
        Header(title = "Insights") {
            state.performance?.let {
                BasicText(
                    text = "policy v${it.summary.policyVersion}",
                    style = typography.s.secondary
                )
            }
        }

        when {
            state.error != null -> InsightsError(
                error = state.error,
                url = backendUrl,
                onRetry = { attempt += 1 }
            )

            state.isLoading -> ShimmerHost {
                repeat(3) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        TextPlaceholder()
                        Spacer(
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .background(colorPalette.shimmer)
                                .fillMaxWidth()
                                .height(120.dp)
                        )
                        TextPlaceholder()
                    }
                }
            }

            state.isEmpty -> BasicText(
                text = "The opponent has not finished a game yet, so there is no curve to draw. " +
                        "Play one - it learns from the first match as much as from the fortieth.",
                style = typography.xs.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            state.performance != null -> Performance(performance = state.performance)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun Performance(performance: PerformanceDto) {
    val (colorPalette, typography) = LocalAppearance.current

    val summary = performance.summary
    val split = recordSplit(summary)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        StatTile(
            label = "Matches",
            value = summary.totalMatches.toString(),
            detail = "${(summary.avgTurns).roundToInt()} turns on average"
        )

        StatTile(
            label = "Its record",
            value = "${summary.aiWins}–${summary.humanWins}–${summary.draws}",
            detail = "${split.winPercent}% / ${split.lossPercent}% / ${split.drawPercent}%"
        )

        StatTile(
            label = "Elo",
            value = elo(summary.elo),
            accented = true,
            detail = "wins ${percent(summary.aiWinRate)} of them"
        )

        StatTile(
            label = "Policy",
            value = "v${summary.policyVersion}",
            detail = "${performance.training.size} training runs"
        )
    }

    // --- the win/loss delta -------------------------------------------------------------------

    SectionHeading(
        title = "THE WIN/LOSS DELTA",
        subtitle = "Its record against you, match by match, and its form over the last " +
                "$ROLLING_WINDOW."
    )

    val curves = winRateCurves(performance.winRateSeries, ROLLING_WINDOW)

    if (curves.cumulative.isEmpty()) {
        Note("No finished matches yet.")
    } else {
        LineChart(
            series = listOf(
                ChartSeries(
                    values = curves.cumulative,
                    color = colorPalette.accent,
                    filled = true
                ),
                ChartSeries(
                    values = curves.rolling,
                    color = colorPalette.textSecondary,
                    strokeWidth = 1.5.dp,
                    dashed = true
                )
            ),
            range = ValueRange(0f, 1f),
            referenceLine = ReferenceLine(value = 0.5f, label = "50%"),
            tickFormatter = { "${(it * 100).roundToInt()}%" },
            startLabel = "match 1",
            endLabel = "match ${performance.winRateSeries.size}",
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        ChartLegend(
            entries = listOf(
                "all matches" to colorPalette.accent,
                "last $ROLLING_WINDOW" to colorPalette.textSecondary
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }

    Callout(text = gamesToFiftyReading(summary))

    // --- mistake repetition -------------------------------------------------------------------

    SectionHeading(
        title = "MISTAKE REPETITION",
        subtitle = "How often it plays a move that already cost it reward. This is the penalty " +
                "system, measured."
    )

    if (performance.mistakeSeries.isEmpty()) {
        Note("Nothing recorded yet.")
    } else {
        LineChart(
            series = listOf(
                ChartSeries(
                    values = performance.mistakeSeries.map { it.rate.toFloat() },
                    color = colorPalette.accent,
                    filled = true
                )
            ),
            range = ValueRange(0f, 1f).let { unit ->
                val peak = performance.mistakeSeries.maxOf { it.rate }.toFloat()
                // A rate that never exceeds a fifth would be a flat line against a 0-to-1 axis.
                if (peak < 0.2f) ValueRange(0f, 0.2f) else unit
            },
            tickFormatter = { "${(it * 100).roundToInt()}%" },
            startLabel = "match 1",
            endLabel = "match ${performance.mistakeSeries.size}",
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    Callout(text = mistakeReading(performance.mistakeSeries))

    // --- game length --------------------------------------------------------------------------

    SectionHeading(
        title = "GAME LENGTH",
        subtitle = "Turns per match. Longer as it learns to defend, then shorter as it learns to " +
                "finish."
    )

    if (performance.gameLengthSeries.isEmpty()) {
        Note("No finished matches yet.")
    } else {
        LineChart(
            series = listOf(
                ChartSeries(
                    values = performance.gameLengthSeries.map { it.turns.toFloat() },
                    color = colorPalette.accent,
                    filled = true
                )
            ),
            startLabel = "match 1",
            endLabel = "match ${performance.gameLengthSeries.size}",
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    Callout(text = gameLengthReading(performance.gameLengthSeries))

    // --- captures -----------------------------------------------------------------------------

    SectionHeading(
        title = "CAPTURES",
        subtitle = "Pieces taken each match, its side against yours."
    )

    if (performance.captureSeries.isEmpty()) {
        Note("No captures recorded yet.")
    } else {
        BarChart(
            groups = performance.captureSeries.map {
                BarGroup(
                    primary = it.aiCaptures.toFloat(),
                    secondary = it.humanCaptures.toFloat()
                )
            },
            primaryColor = colorPalette.accent,
            secondaryColor = colorPalette.textSecondary,
            startLabel = "match 1",
            endLabel = "match ${performance.captureSeries.size}",
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        ChartLegend(
            entries = listOf(
                "it took" to colorPalette.accent,
                "you took" to colorPalette.textSecondary
            ),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }

    Callout(text = captureReading(performance.captureSeries, summary))

    // --- training -----------------------------------------------------------------------------

    SectionHeading(
        title = "TRAINING",
        subtitle = "The loss of each policy update. Down is the network fitting what it has seen."
    )

    if (performance.training.isEmpty()) {
        Note("No training runs recorded yet.")
    } else {
        Sparkline(
            values = performance.training.map { it.loss.toFloat() },
            color = colorPalette.accent,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }

    Callout(text = trainingReading(performance.training))
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 12.dp)
    ) {
        BasicText(
            text = title,
            style = typography.xxs.semiBold.color(colorPalette.accent)
        )

        BasicText(
            text = subtitle,
            style = typography.xxs.secondary
        )
    }
}

/** The sentence under a chart: what the shape of it means. */
@Composable
private fun Callout(text: String) {
    val (_, typography) = LocalAppearance.current

    BasicText(
        text = text,
        style = typography.xs.medium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun Note(text: String) {
    val (_, typography) = LocalAppearance.current

    BasicText(
        text = text,
        style = typography.xs.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    )
}

@Composable
private fun InsightsError(
    error: ApiError,
    url: String,
    onRetry: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
    ) {
        BasicText(
            text = when (error) {
                is ApiError.Unreachable -> "Nothing answered at ${error.url}, so there are no " +
                        "numbers to draw."

                is ApiError.Timeout -> "The backend at $url took longer than ${error.seconds} " +
                        "seconds to work these out."

                else -> error.message
            },
            style = typography.xs.medium.color(colorPalette.red)
        )

        BasicText(
            text = "The learning curve is computed by the backend from every match it has " +
                    "stored. Start it, or check the address in Settings → Backend.",
            style = typography.xxs.secondary
        )

        SecondaryTextButton(
            text = "Try again",
            onClick = onRetry
        )
    }
}
