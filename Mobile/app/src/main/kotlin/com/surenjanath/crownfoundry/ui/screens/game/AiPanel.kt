package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.EvaluationDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.components.ShimmerHost
import com.surenjanath.crownfoundry.ui.components.themed.HeaderIconButton
import com.surenjanath.crownfoundry.ui.components.themed.TextPlaceholder
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.color
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold
import kotlin.math.roundToInt

const val AiPanelTag = "aiPanel"
const val AiThinkingTag = "aiThinking"

@Composable
fun AiPanel(
    state: GameState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    showReasoning: Boolean = true,
    showEvaluation: Boolean = true
) {
    val (colorPalette, typography) = LocalAppearance.current
    val counts = state.counts
    val isAiTurn = !state.isOver && state.sideToMove == Side.AI
    val thinking = state.phase == GamePhase.Thinking
    val capturedBlack = (12 - counts.black).coerceAtLeast(0)
    val evaluation = state.evaluation

    val cardBackground = if (isAiTurn) colorPalette.background1 else colorPalette.background0
    val cardBorder = if (isAiTurn) colorPalette.accent.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cardBackground)
            .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(AiPanelTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Left: Back button + AI Avatar + Opponent name + Elo + Difficulty
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeaderIconButton(
                    icon = R.drawable.chevron_back,
                    color = colorPalette.textSecondary,
                    onClick = onBack
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(colorPalette.background2)
                        .border(1.5.dp, if (isAiTurn) colorPalette.accent else androidx.compose.ui.graphics.Color.Transparent, CircleShape)
                ) {
                    Image(
                        painter = painterResource(R.drawable.brain),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(
                            if (thinking || isAiTurn) colorPalette.accent else colorPalette.textSecondary
                        ),
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        BasicText(
                            text = "Opponent",
                            style = typography.xs.semiBold
                        )

                        if (state.aiStatus.elo > 0) {
                            BasicText(
                                text = "${state.aiStatus.elo} Elo",
                                style = typography.xxs.medium.secondary
                            )
                        }

                        // Difficulty tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colorPalette.background2)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            BasicText(
                                text = state.difficulty.replaceFirstChar { it.uppercase() },
                                style = typography.xxs.semiBold.copy(color = colorPalette.accent)
                            )
                        }
                    }

                    if (capturedBlack > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            BasicText(text = "Taken:", style = typography.xxs.medium.secondary)
                            Spacer(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colorPalette.accent)
                            )
                            BasicText(text = "$capturedBlack", style = typography.xxs.semiBold)
                        }
                    }
                }
            }

            // Right: Q-Evaluation + Remaining White Pieces
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!thinking && evaluation != null && showEvaluation) {
                    BasicText(
                        text = "Q ${formatSigned(evaluation.qValue)}",
                        style = typography.xxs.semiBold.copy(color = colorPalette.accent)
                    )
                }

                // Piece count pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colorPalette.background2)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Spacer(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colorPalette.text)
                    )

                    BasicText(
                        text = "${counts.white}",
                        style = typography.xs.semiBold
                    )

                    if (counts.whiteKings > 0) {
                        Image(
                            painter = painterResource(R.drawable.crown),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                            modifier = Modifier.size(10.dp)
                        )
                        BasicText(
                            text = "${counts.whiteKings}",
                            style = typography.xxs.semiBold.secondary
                        )
                    }
                }
            }
        }

        val reasoning = state.reasoning
        if (thinking) {
            ShimmerHost(
                modifier = Modifier
                    .testTag(AiThinkingTag)
                    .fillMaxWidth()
            ) {
                BasicText(
                    text = "Analyzing next move…",
                    style = typography.xxs.medium.copy(color = colorPalette.accent)
                )
            }
        } else if (showReasoning && reasoning != null) {
            BasicText(
                text = reasoning,
                style = typography.xxs.medium.secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SourceBadge(spokeThroughOllama: Boolean) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colorPalette.background2)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Spacer(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (spokeThroughOllama) colorPalette.accent else colorPalette.textDisabled)
        )

        BasicText(
            text = if (spokeThroughOllama) "Ollama" else "Narrator",
            style = typography.xxs.medium.secondary
        )
    }
}

@Composable
private fun Evaluation(evaluation: EvaluationDto, chosen: String?) {
    val (colorPalette, typography) = LocalAppearance.current

    val scores = evaluation.considered
    val highest = scores.maxOf { it.q }
    val lowest = scores.minOf { it.q }
    val span = (highest - lowest).takeIf { it > 1e-6 } ?: 1.0

    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        scores.take(2).forEach { candidate ->
            val isChosen = candidate.notation == chosen ||
                    (chosen == null && candidate.notation == scores.first().notation)

            val fraction by animateFloatAsState(
                targetValue = (0.1 + 0.9 * ((candidate.q - lowest) / span)).toFloat(),
                label = "candidate"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicText(
                    text = candidate.notation,
                    style = if (isChosen) typography.xxs.semiBold
                    else typography.xxs.medium.secondary,
                    modifier = Modifier.width(54.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colorPalette.background2)
                ) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isChosen) colorPalette.accent else colorPalette.textDisabled
                            )
                    )
                }

                BasicText(
                    text = formatSigned(candidate.q),
                    style = typography.xxs.medium.secondary,
                    modifier = Modifier.width(42.dp)
                )
            }
        }
    }
}

private fun formatSigned(value: Double): String {
    val rounded = (value * 100).roundToInt() / 100.0
    val text = if (rounded < 0) "-" else "+"
    val magnitude = kotlin.math.abs(rounded)
    val whole = magnitude.toInt()
    val hundredths = ((magnitude - whole) * 100).roundToInt()
    return "$text$whole.${hundredths.toString().padStart(2, '0')}"
}
