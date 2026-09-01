package com.surenjanath.crownfoundry.ui.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.MainActivity
import com.surenjanath.crownfoundry.leaderboard.Achievement
import com.surenjanath.crownfoundry.leaderboard.AchievementState
import com.surenjanath.crownfoundry.leaderboard.Leaderboard
import com.surenjanath.crownfoundry.leaderboard.LeaderboardScores
import com.surenjanath.crownfoundry.leaderboard.Leaderboards
import com.surenjanath.crownfoundry.leaderboard.achievementsOf
import com.surenjanath.crownfoundry.leaderboard.scoresOf
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.ui.components.charts.StatTile
import com.surenjanath.crownfoundry.ui.components.themed.SecondaryTextButton
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold

/**
 * Your own record, and the way through to where it is ranked.
 *
 * The numbers are always shown; the button to compare them is not. Nothing here needs Play Games
 * to be worth reading - "eleven wins, best streak of four" is a fact about the player whether or
 * not anyone else can see it - and an app that hid its own statistics behind a sign-in the player
 * declined would be punishing them for declining.
 *
 * There is no table of other people's scores here on purpose. Play Games owns that screen: it is
 * the only party that knows who the player's friends are, and the only one that can show a score
 * without the app having to hold a stranger's name.
 */
@Composable
fun LeaderboardSection() {
    val (_, typography) = LocalAppearance.current
    val context = LocalContext.current

    var scores by remember { mutableStateOf(LeaderboardScores()) }
    var achievements by remember { mutableStateOf(AchievementState()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scores = scoresOf(
            Offline.matches?.all().orEmpty(),
            Offline.puzzles?.all().orEmpty()
        )
        achievements = achievementsOf(scores, Offline.engine.state.header)
        loaded = true
        // Opening this screen is a good moment to catch up a board that fell behind while the
        // player was signed out.
        Offline.publishScores()
    }

    SectionHeading(
        title = "YOUR RECORD",
        subtitle = "What you have done against it, which is the part of this app that is about " +
                "you rather than about the opponent."
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        StatTile(
            label = "Your rating",
            value = scores.rating.label,
            accented = true,
            detail = when {
                scores.rating.gamesRated == 0 -> "play a game to be rated"
                scores.rating.isProvisional ->
                    "provisional, ${scores.rating.gamesRated} of 10 games"

                scores.rating.lastChange > 0 -> "+${scores.rating.lastChange} last game"
                scores.rating.lastChange < 0 -> "${scores.rating.lastChange} last game"
                else -> "peak ${scores.rating.peak}"
            }
        )

        StatTile(
            label = "Games won",
            value = scores.wins.toString(),
            detail = if (scores.played == 0) "none yet"
            else "of ${scores.played} · ${scores.losses} lost, ${scores.draws} drawn"
        )

        StatTile(
            label = "Best streak",
            value = scores.streak.toString(),
            detail = if (scores.streak <= 1) "wins in a row" else "wins without losing one"
        )

        StatTile(
            label = "Puzzles",
            value = scores.puzzles.toString(),
            detail = "solved"
        )
    }

    SectionHeading(
        title = "ACHIEVEMENTS",
        subtitle = "${achievements.unlockedCount} of ${Achievement.entries.size} earned."
    )

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        for (achievement in Achievement.entries) {
            AchievementRow(
                achievement = achievement,
                unlocked = achievement in achievements,
                progress = achievements.progressOf(achievement)
            )
        }
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (Leaderboards.available) {
            SecondaryTextButton(
                text = "Compare on Play Games",
                onClick = {
                    (context as? MainActivity)?.let {
                        Leaderboards.show(it, Leaderboard.RatingBoard)
                    }
                }
            )
            SecondaryTextButton(
                text = "See achievements",
                onClick = {
                    (context as? MainActivity)?.let { Leaderboards.showAchievements(it) }
                }
            )
        } else if (loaded) {
            BasicText(
                text = "Sign in to Play Games on this phone to see these ranked against other " +
                        "players. Nothing is posted until you do.",
                style = typography.xxs.secondary
            )
        }
    }
}

/**
 * One achievement, earned or not.
 *
 * Locked ones are shown rather than hidden, and with their real description: an achievement you
 * cannot see is not something to aim at, and a list of question marks tells a player nothing
 * about how to play better. The counted ones carry their progress, so "three of five" reads as
 * halfway rather than as a failure.
 */
@Composable
private fun AchievementRow(
    achievement: Achievement,
    unlocked: Boolean,
    progress: Int
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (unlocked) colorPalette.accent
                    else colorPalette.background2
                )
        ) {
            BasicText(
                text = if (unlocked) "✦" else "·",
                style = typography.xs.copy(
                    color = if (unlocked) colorPalette.onAccent else colorPalette.textDisabled
                )
            )
        }

        Column(modifier = Modifier.padding(start = 12.dp)) {
            BasicText(
                text = achievement.label,
                style = if (unlocked) typography.xs.semiBold else typography.xs.secondary
            )
            BasicText(
                text = if (!unlocked && achievement.target > 0 && progress > 0) {
                    "${achievement.description}  ($progress / ${achievement.target})"
                } else {
                    achievement.description
                },
                style = typography.xxs.secondary
            )
        }
    }
}
