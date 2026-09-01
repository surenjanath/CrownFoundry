package com.surenjanath.crownfoundry.leaderboard

import com.surenjanath.crownfoundry.engine.EngineHeader

/**
 * Things worth doing, and whether you have done them.
 *
 * Every one is computed from the corpus already on the device rather than watched for as it
 * happens. That is what makes them survive a reinstall-and-restore, and it is why a player who
 * signs in to Play Games long after unlocking something still gets it: the next recount posts
 * everything true, not just what changed since the app last looked.
 *
 * They are deliberately about *play* rather than about persistence. "Open the app five days
 * running" is a thing an app wants; "beat it on Hard" is a thing a player wants.
 */
enum class Achievement(
    val key: String,
    val label: String,
    val description: String,
    /** `0` for a one-off; otherwise what the counter has to reach. */
    val target: Int = 0,
    /**
     * Whether Play Games should draw this as a partially filled ring.
     *
     * Not simply `target > 0`. A rating starts at 1200 and has to reach 1400, so as a 1400-step
     * counter it would read as 85% complete on a fresh install, before a single game - which is
     * worse than no progress bar at all. The app's own list still shows the number, where the
     * starting point is stated alongside it and cannot mislead.
     */
    val incremental: Boolean = target > 0
) {
    FirstWin(
        key = "first_win",
        label = "First blood",
        description = "Win a game against the engine."
    ),
    Flawless(
        key = "flawless",
        label = "Untouched",
        description = "Win without letting it take a single piece."
    ),
    Streak5(
        key = "streak_5",
        label = "On a run",
        description = "Win five games in a row.",
        target = 5
    ),
    BeatHard(
        key = "beat_hard",
        label = "No handicap",
        description = "Beat it on Hard, where it never throws a move away."
    ),
    Puzzles10(
        key = "puzzles_10",
        label = "Student of the game",
        description = "Solve ten puzzles.",
        target = 10
    ),
    Rated1400(
        key = "rated_1400",
        label = "Rated",
        description = "Reach a rating of 1400.",
        target = 1400,
        incremental = false
    ),
    Sparring(
        key = "sparring",
        label = "Sparring partner",
        description = "Run a practice session whose result was good enough to keep."
    ),
    Centurion(
        key = "centurion",
        label = "Centurion",
        description = "Finish a hundred games against the engine.",
        target = 100
    );

    companion object {
        fun of(key: String): Achievement? = entries.firstOrNull { it.key == key }
    }
}

/**
 * What has been unlocked, and how far along the counted ones are.
 *
 * [progress] is reported for every achievement with a [Achievement.target], so the UI can draw
 * "3 / 5" and Play Games can show a partially filled ring rather than a locked box.
 */
data class AchievementState(
    val unlocked: Set<Achievement> = emptySet(),
    val progress: Map<Achievement, Int> = emptyMap()
) {
    operator fun contains(achievement: Achievement) = achievement in unlocked

    fun progressOf(achievement: Achievement): Int = progress[achievement] ?: 0

    val unlockedCount: Int get() = unlocked.size
}

/**
 * Work out what [scores] and the installed engine have earned.
 *
 * [header] is the engine artifact's header, which is where the count of accepted practice
 * sessions lives; `null` when no engine is installed, which unlocks nothing rather than throwing.
 */
fun achievementsOf(scores: LeaderboardScores, header: EngineHeader?): AchievementState {
    val progress = mapOf(
        Achievement.Streak5 to scores.streak,
        Achievement.Puzzles10 to scores.puzzles,
        // Reported as zero until there is a real rating, so an unrated player is not shown as
        // already 1200/1400 of the way to a rating they have not earned.
        Achievement.Rated1400 to if (scores.rating.gamesRated == 0) 0 else scores.rating.peak,
        Achievement.Centurion to scores.played
    )

    val unlocked = buildSet {
        if (scores.wins > 0) add(Achievement.FirstWin)
        if (scores.flawlessWins > 0) add(Achievement.Flawless)
        if (scores.streak >= Achievement.Streak5.target) add(Achievement.Streak5)
        if (scores.hardWins > 0) add(Achievement.BeatHard)
        if (scores.puzzles >= Achievement.Puzzles10.target) add(Achievement.Puzzles10)
        // The peak, not the current value: a rating earned and then lost was still earned.
        if (scores.rating.gamesRated > 0 && scores.rating.peak >= Achievement.Rated1400.target) {
            add(Achievement.Rated1400)
        }
        if ((header?.selfPlaySessions ?: 0) > 0) add(Achievement.Sparring)
        if (scores.played >= Achievement.Centurion.target) add(Achievement.Centurion)
    }

    return AchievementState(unlocked = unlocked, progress = progress)
}
