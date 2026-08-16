package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import com.surenjanath.crownfoundry.ui.components.ShimmerHost
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
    thinking: Boolean,
    reasoning: String?,
    spokeThroughOllama: Boolean,
    evaluation: EvaluationDto?,
    chosen: String?,
    modifier: Modifier = Modifier,
    showReasoning: Boolean = true,
    showEvaluation: Boolean = true
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .testTag(AiPanelTag)
            .height(58.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colorPalette.background1)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colorPalette.background2)
        ) {
            Image(
                painter = painterResource(R.drawable.brain),
                contentDescription = null,
                colorFilter = ColorFilter.tint(
                    if (thinking) colorPalette.accent else colorPalette.textSecondary
                ),
                modifier = Modifier.size(18.dp)
            )
        }

        Column(
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicText(
                    text = "Opponent",
                    style = typography.xs.semiBold
                )

                if (!thinking && reasoning != null) {
                    SourceBadge(spokeThroughOllama)
                }

                if (!thinking && evaluation != null && showEvaluation) {
                    BasicText(
                        text = "Q ${formatSigned(evaluation.qValue)} (${(evaluation.confidence * 100).roundToInt()}%)",
                        style = typography.xxs.medium.secondary
                    )
                }
            }

            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (thinking) {
                    ShimmerHost(
                        modifier = Modifier
                            .testTag(AiThinkingTag)
                            .fillMaxWidth()
                    ) {
                        BasicText(
                            text = "Thinking…",
                            style = typography.xs.medium.color(colorPalette.accent)
                        )
                    }
                } else if (showReasoning) {
                    BasicText(
                        text = reasoning ?: "Waiting for your move.",
                        style = if (reasoning == null) typography.xs.medium.secondary
                        else typography.xs.medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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
