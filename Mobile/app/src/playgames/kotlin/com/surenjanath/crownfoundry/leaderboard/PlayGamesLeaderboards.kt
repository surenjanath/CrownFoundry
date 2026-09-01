package com.surenjanath.crownfoundry.leaderboard

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.games.PlayGames
import com.google.android.gms.games.PlayGamesSdk
import com.surenjanath.crownfoundry.BuildConfig

/**
 * Google Play Games, behind [LeaderboardService].
 *
 * This file is compiled in only when `crownfoundry.playGamesAppId` is set - see the source set the
 * build adds beside it. Everything here therefore assumes the SDK is present and the manifest
 * carries the app id, because a build where that is not true does not contain this file.
 *
 * Play Games is the right home for this in an app that has no accounts and no server. It supplies
 * the identity, the leaderboard and achievement UI, and the only defence against a tampered score
 * that a client-computed board can have. What the app never gets is other people's scores: those
 * are read in Google's own UI, which is why [show] hands off to an activity rather than returning
 * a list.
 *
 * Sign-in is Play Games' own: v2 signs the player in at launch and there is no button for it. A
 * player who declines, or who has no Play Games profile, simply leaves [available] false, and the
 * app hides the entry points rather than nagging.
 */
class PlayGamesLeaderboards(private val context: Context) : LeaderboardService {

    @Volatile
    private var signedIn = false

    override val available: Boolean get() = signedIn

    /**
     * Console ids, keyed by [Leaderboard.key] and [Achievement.key].
     *
     * Opaque `CgkI…` strings that differ per application. They are not secret - they identify a
     * board or an achievement, they do not authorise writing to one - which is why they can sit in
     * `gradle.properties` and ship inside the APK.
     */
    private val boardIds = parseIds(BuildConfig.PLAY_GAMES_LEADERBOARDS)
    private val achievementIds = parseIds(BuildConfig.PLAY_GAMES_ACHIEVEMENTS)

    fun start() {
        PlayGamesSdk.initialize(context)
        PlayGames.getGamesSignInClient(context as Activity).isAuthenticated
            .addOnCompleteListener { task ->
                signedIn = task.isSuccessful && task.result.isAuthenticated
            }
    }

    override fun submit(board: Leaderboard, score: Int) {
        val id = boardIds[board.key] ?: return
        guard("submit ${board.key}") {
            PlayGames.getLeaderboardsClient(context as Activity).submitScore(id, score.toLong())
        }
    }

    override fun award(achievement: Achievement, unlocked: Boolean, progress: Int) {
        val id = achievementIds[achievement.key] ?: return
        guard("award ${achievement.key}") {
            val client = PlayGames.getAchievementsClient(context as Activity)
            when {
                unlocked -> client.unlock(id)
                // `setSteps` is absolute rather than incremental, which is what a recount over the
                // whole corpus needs: `increment` would double-count every time the app recounted.
                achievement.incremental && progress > 0 ->
                    client.setSteps(id, progress.coerceAtMost(achievement.target))
            }
        }
    }

    override fun show(activity: Activity, board: Leaderboard?) {
        if (!signedIn) return
        val client = PlayGames.getLeaderboardsClient(activity)
        val intent = board?.let { boardIds[it.key] }
            ?.let { client.getLeaderboardIntent(it) }
            ?: client.allLeaderboardsIntent

        intent.addOnSuccessListener { activity.startActivityForResult(it, REQUEST_CODE) }
    }

    override fun showAchievements(activity: Activity) {
        if (!signedIn) return
        PlayGames.getAchievementsClient(activity).achievementsIntent
            .addOnSuccessListener { activity.startActivityForResult(it, REQUEST_CODE) }
    }

    /** Nothing posted to Play Games is worth interrupting a game over. */
    private inline fun guard(what: String, block: () -> Unit) {
        if (!signedIn) return
        try {
            block()
        } catch (failure: Exception) {
            Log.w(TAG, "could not $what", failure)
        }
    }

    private fun parseIds(configured: String): Map<String, String> = configured
        .split(',')
        .mapNotNull { entry ->
            val parts = entry.split(':', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                parts[0].trim() to parts[1].trim()
            } else {
                null
            }
        }
        .toMap()

    private companion object {
        const val TAG = "CrownFoundry.Games"

        /** Arbitrary; the result is never read, the UI is purely presentational. */
        const val REQUEST_CODE = 9004
    }
}
