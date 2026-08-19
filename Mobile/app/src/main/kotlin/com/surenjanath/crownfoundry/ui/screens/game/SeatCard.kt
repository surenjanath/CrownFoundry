package com.surenjanath.crownfoundry.ui.screens.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

const val AiPanelTag = "aiPanel"

/**
 * Keep a slot's space whether or not it has anything in it.
 *
 * The card holds its height by drawing empty rows rather than by adding rows when there is news,
 * which is what stops it shoving the board about. A row faded to nothing is still a row a screen
 * reader will happily read out, though, so an invisible slot is taken out of the semantics tree
 * as well - otherwise both seats would announce "to move" and every game would start with twelve
 * pieces taken.
 */
private fun Modifier.holdingSpace(visible: Boolean): Modifier =
    alpha(if (visible) 1f else 0f)
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})

/**
 * One side of the board.
 *
 * The two chairs used to be two composables that had drifted into near-copies of each other - the
 * same avatar, the same name, the same captured row, the same piece pill, the same highlight,
 * written twice. They are one component now, told apart only by the [SeatView] handed to them,
 * because everything that differs between them is an optional fact rather than a different shape.
 *
 * Every slot is laid out from the first frame and optional content is faded rather than added, so
 * the card is the same height on move one as on move sixty. That is not decoration: this card sits
 * directly above the board, and a card that grows is a board that jumps.
 */
@Composable
fun SeatCard(
    seat: SeatView,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    // The seat's own men, and - for the tally of what it has taken - the other side's.
    val own = if (seat.isBlackSide) colorPalette.accent else colorPalette.text
    val theirs = if (seat.isBlackSide) colorPalette.text else colorPalette.accent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (seat.isToMove) colorPalette.background1 else colorPalette.background0)
            .border(
                width = 1.dp,
                color = if (seat.isToMove) {
                    colorPalette.accent.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                },
                shape = RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (seat.isBlackSide) {
                            colorPalette.accent.copy(alpha = 0.15f)
                        } else {
                            colorPalette.background2
                        }
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (seat.isToMove) colorPalette.accent else Color.Transparent,
                        shape = CircleShape
                    )
            ) {
                Image(
                    painter = painterResource(
                        if (seat.avatar == SeatAvatar.Engine) R.drawable.brain else R.drawable.person
                    ),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(
                        if (seat.isToMove) colorPalette.accent else colorPalette.textSecondary
                    ),
                    modifier = Modifier.size(16.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    BasicText(text = seat.name, style = typography.xs.semiBold, maxLines = 1)

                    // The turn indicator keeps its width whether or not it is this seat's move,
                    // so neither card resizes when the move changes hands.
                    BasicText(
                        text = "to move",
                        style = typography.xxs.semiBold.copy(color = colorPalette.accent),
                        maxLines = 1,
                        modifier = Modifier.holdingSpace(seat.isToMove)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    seat.tags.forEach { tag ->
                        BasicText(
                            text = tag.text,
                            style = if (tag.accented) {
                                typography.xxs.semiBold.copy(color = colorPalette.accent)
                            } else {
                                typography.xxs.medium.secondary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Drawn once there is something to report, laid out from the start.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.holdingSpace(seat.captured > 0)
                    ) {
                        BasicText(text = "Taken:", style = typography.xxs.medium.secondary)
                        Spacer(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(theirs)
                        )
                        BasicText(text = "${seat.captured}", style = typography.xxs.semiBold)
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // One trailing readout per seat: what the engine thought, or how long you have taken.
            (seat.evaluation ?: seat.clock)?.let { trailing ->
                BasicText(
                    text = trailing,
                    style = if (seat.evaluation != null) {
                        typography.xxs.semiBold.copy(color = colorPalette.accent)
                    } else {
                        typography.xxs.semiBold.secondary
                    },
                    maxLines = 1
                )
            }

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
                        .background(own)
                )

                BasicText(text = "${seat.pieces}", style = typography.xs.semiBold)

                // The crown holds its place so a promotion does not widen the pill mid-game.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.holdingSpace(seat.kings > 0)
                ) {
                    Image(
                        painter = painterResource(R.drawable.crown),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                        modifier = Modifier.size(10.dp)
                    )
                    BasicText(text = "${seat.kings}", style = typography.xxs.semiBold.secondary)
                }
            }
        }
    }
}
