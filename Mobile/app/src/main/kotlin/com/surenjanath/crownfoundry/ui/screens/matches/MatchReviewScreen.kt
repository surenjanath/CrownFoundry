package com.surenjanath.crownfoundry.ui.screens.matches

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.AnalysedMove
import com.surenjanath.crownfoundry.engine.MoveQuality
import com.surenjanath.crownfoundry.ui.components.ShimmerHost
import com.surenjanath.crownfoundry.ui.components.charts.ChartSeries
import com.surenjanath.crownfoundry.ui.components.charts.LineChart
import com.surenjanath.crownfoundry.ui.components.charts.ReferenceLine
import com.surenjanath.crownfoundry.ui.components.charts.StatTile
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.components.themed.HeaderIconButton
import com.surenjanath.crownfoundry.ui.components.themed.IconButton
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

/**
 * A finished match, replayed. The board is rebuilt from the FEN stored against each ply, and the
 * opponent's own account of why it moved is shown as you land on its moves - which is the only
 * place in the app where you can read what it was thinking after you know how it turned out.
 */
@Composable
fun MatchReviewScreen(matchId: String) {
    val (colorPalette, typography) = LocalAppearance.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val context = LocalContext.current

    val backendUrl by rememberPreference(backendUrlKey, defaultBackendUrl)
    var attempt by remember { mutableIntStateOf(0) }
    val holder = remember(matchId) { ReviewStateHolder(Offline.api, matchId) }

    LaunchedEffect(matchId, backendUrl, attempt) {
        CrownFoundryClient.baseUrl = backendUrl
        holder.load()
    }

    val state = holder.state
    val analysis = holder.analysis

    // Scoring starts as soon as there is a game to score, and stops the moment this screen leaves
    // composition - a player who backed out is not charged for the rest of the search.
    LaunchedEffect(state.match?.matchId, state.match?.history?.size) {
        if (state.match != null) holder.analyse()
    }

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
        Header(title = "Review") {
            HeaderIconButton(
                icon = R.drawable.share_social,
                color = if (PdnExport.isExportable(state.match)) {
                    colorPalette.text
                } else {
                    colorPalette.textDisabled
                },
                enabled = PdnExport.isExportable(state.match),
                onClick = { state.match?.let { PdnExport.share(context, it) } }
            )

            HeaderIconButton(
                icon = R.drawable.chevron_back,
                color = colorPalette.text,
                onClick = { backDispatcher?.onBackPressed() }
            )
        }

        when {
            state.error != null -> ReviewError(
                error = state.error,
                url = backendUrl,
                onRetry = { attempt += 1 }
            )

            state.isLoading -> ShimmerHost {
                Spacer(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .background(colorPalette.shimmer)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    TextPlaceholder()
                    TextPlaceholder()
                }
            }

            else -> {
                val ply = state.current

                state.match?.let {
                    BasicText(
                        text = reviewHeadline(it),
                        style = typography.xs.secondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                ReviewBoard(
                    position = ply?.position ?: Fen.parseOrEmpty(Fen.OPENING),
                    highlightedSquares = ply?.highlightedSquares.orEmpty(),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .widthIn(max = 480.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Scrubber(
                    state = state,
                    onStepBack = holder::stepBack,
                    onStepForward = holder::stepForward,
                    onSeekFraction = { fraction ->
                        holder.seek((fraction * state.plies.lastIndex).roundToInt())
                    }
                )

                Spacer(modifier = Modifier.height(20.dp))

                PlyDetail(
                    ply = ply,
                    scored = analysis.moveAt(state.plyIndex),
                    passAndPlay = isPassAndPlay(state.match?.difficulty)
                )

                Spacer(modifier = Modifier.height(20.dp))

                AnalysisSection(
                    analysis = analysis,
                    plyIndex = state.plyIndex,
                    passAndPlay = isPassAndPlay(state.match?.difficulty),
                    onSeekPly = holder::seek
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Scrubber(
    state: ReviewState,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onSeekFraction: (Float) -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                icon = R.drawable.chevron_back,
                color = if (state.canStepBack) colorPalette.text else colorPalette.textDisabled,
                enabled = state.canStepBack,
                onClick = onStepBack,
                modifier = Modifier.size(20.dp)
            )

            BasicText(
                text = state.scrubberLabel,
                style = typography.xxs.medium.secondary,
                maxLines = 1
            )

            IconButton(
                icon = R.drawable.arrow_forward,
                color = if (state.canStepForward) colorPalette.text else colorPalette.textDisabled,
                enabled = state.canStepForward,
                onClick = onStepForward,
                modifier = Modifier.size(20.dp)
            )
        }

        val plyCount = state.plies.size

        Canvas(
            modifier = Modifier
                .height(24.dp)
                .fillMaxWidth()
                .pointerInput(plyCount) {
                    if (plyCount <= 1) return@pointerInput

                    // Tap and drag are the same gesture here: wherever the finger is, that is the
                    // ply. Nothing else on this screen wants the pointer, so it is safe to take.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val width = size.width.toFloat()
                        if (width <= 0f) return@awaitEachGesture

                        onSeekFraction(down.position.x / width)
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break

                            onSeekFraction(change.position.x / width)
                            change.consume()
                        }
                    }
                }
        ) {
            val y = size.height / 2f
            val progress = if (state.plies.size <= 1) {
                1f
            } else {
                state.plyIndex.toFloat() / state.plies.lastIndex
            }

            drawLine(
                color = colorPalette.background2,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 3.dp.toPx()
            )

            drawLine(
                color = colorPalette.accent,
                start = Offset(0f, y),
                end = Offset(size.width * progress, y),
                strokeWidth = 3.dp.toPx()
            )

            drawCircle(
                color = colorPalette.accent,
                radius = 7.dp.toPx(),
                center = Offset(
                    x = (size.width * progress).coerceIn(7.dp.toPx(), size.width - 7.dp.toPx()),
                    y = y
                )
            )
        }
    }
}

/**
 * The engine's opinion of one move, coloured by how much it cost.
 *
 * The label carries the judgement and the colour only reinforces it, so the screen still reads
 * correctly to someone who cannot tell the accent from the red.
 */
@Composable
private fun QualityLine(scored: AnalysedMove) {
    val (colorPalette, typography) = LocalAppearance.current

    val color = when {
        scored.wasBest || scored.quality == MoveQuality.Good -> colorPalette.accent
        scored.quality == MoveQuality.Inaccuracy -> colorPalette.textSecondary
        else -> colorPalette.red
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(
            text = verdictOf(scored),
            style = typography.xs.semiBold.color(color)
        )

        costOf(scored)?.let {
            BasicText(text = it, style = typography.xxs.secondary)
        }
    }
}

@Composable
private fun PlyDetail(ply: ReviewPly?, scored: AnalysedMove?, passAndPlay: Boolean) {
    val (colorPalette, typography) = LocalAppearance.current

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        if (ply == null || ply.move == null) {
            BasicText(
                text = "The opening position. Black moves first, and Black is you.",
                style = typography.xs.secondary
            )
            return@Column
        }

        val mover = when {
            ply.side == Side.HUMAN -> if (passAndPlay) "Black played" else "You played"
            ply.side == Side.AI -> if (passAndPlay) "White played" else "It played"
            else -> "Played"
        }

        BasicText(
            text = "$mover ${ply.move}",
            style = typography.s.semiBold
        )

        // Only when the scored move is the one on screen. A mismatched ply would attach the
        // engine's verdict to the wrong move, which is worse than showing none.
        scored?.takeIf { it.notation == ply.move }?.let { QualityLine(it) }

        if (ply.isAi) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .background(
                        color = colorPalette.background1,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(14.dp)
                    .fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(R.drawable.chatbubble),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorPalette.accent),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(14.dp)
                )

                BasicText(
                    text = ply.reasoning
                        ?: "It did not record a reason for this one - the move came straight " +
                        "from the policy, with no sentence attached.",
                    style = if (ply.reasoning != null) {
                        typography.xs.medium
                    } else {
                        typography.xs.secondary
                    }
                )
            }
        }
    }
}

@Composable
private fun ReviewError(
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
                is ApiError.Unreachable -> "Nothing answered at ${error.url}, so this match " +
                        "cannot be replayed."

                is ApiError.Timeout -> "The backend at $url took longer than ${error.seconds} " +
                        "seconds to send this match."

                else -> error.message
            },
            style = typography.xs.medium.color(colorPalette.red)
        )

        BasicText(
            text = "The moves are stored on the backend, not on the phone.",
            style = typography.xxs.secondary
        )

        SecondaryTextButton(
            text = "Try again",
            onClick = onRetry
        )
    }
}

/**
 * The whole-game verdict: how the evaluation moved, how accurately each side played, and the one
 * move that decided it.
 *
 * Nothing here is load-bearing for the replay. A player with no engine installed still gets the
 * board, the moves and the opponent's reasoning - they just get told, once, why there are no
 * numbers, instead of being shown a spinner that never finishes.
 */
@Composable
private fun AnalysisSection(
    analysis: ReviewAnalysis,
    plyIndex: Int,
    passAndPlay: Boolean,
    onSeekPly: (Int) -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    analysisNotice(analysis)?.let { notice ->
        BasicText(
            text = notice,
            style = if (analysis.isRunning) typography.xs.secondary else typography.xxs.secondary,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        return
    }

    val ready = analysis as? ReviewAnalysis.Ready ?: return
    val summary = summaryOf(ready.analysis, passAndPlay) ?: return

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
    ) {
        BasicText(text = "How it went", style = typography.s.semiBold)

        BasicText(text = summary.headline, style = typography.xs.secondary)

        // Positive is Black, and Black is the player - so above the line is you winning.
        LineChart(
            series = listOf(
                ChartSeries(
                    values = ready.analysis.evaluationSeries,
                    color = colorPalette.accent,
                    filled = true
                )
            ),
            referenceLine = ReferenceLine(value = 0f, label = "even"),
            tickDecimals = 1,
            startLabel = "opening",
            endLabel = "end",
            marker = plyIndex,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "${summary.blackLabel} accuracy",
                value = "${summary.accuracy}%",
                accented = true,
                modifier = Modifier.weight(1f)
            )

            StatTile(
                label = "${summary.whiteLabel} accuracy",
                value = "${summary.opponentAccuracy}%",
                modifier = Modifier.weight(1f)
            )

            StatTile(
                label = "${summary.blackLabel} errors",
                value = "${summary.mistakes + summary.blunders}",
                detail = if (summary.blunders > 0) "${summary.blunders} blunder" else null,
                modifier = Modifier.weight(1f)
            )
        }

        BasicText(text = summary.detail, style = typography.xxs.secondary)

        summary.turningPoint?.let { move ->
            SecondaryTextButton(
                text = "Go to the turning point",
                onClick = { onSeekPly(move.ply) }
            )

            summary.turningPointLine?.let {
                BasicText(text = it, style = typography.xxs.secondary)
            }
        }
    }
}
