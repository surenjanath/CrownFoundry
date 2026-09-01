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
 * the identity, the leaderboard UI, and the only defence against a tampered score that a
 * client-computed board can have. What the app never gets is the scores themselves: they are read
 * in Google's own UI, which is why [show] hands off to an activity rather than returning a list.
 *
 * Sign-in is Play Games' own: v2 signs the player in at launch and there is no button for it. A
 * player who declines, or who has no Play Games profile, simply leaves [available] false, and the
 * app hides the entry point rather than nagging.
 */
class PlayGamesLeaderboards(private val context: Context) : LeaderboardService {

    @Volatile
    private var signedIn = false

    override val available: Boolean get() = signedIn

    /**
     * Board ids from the Play Console, keyed by [Leaderboard.key].
     *
     * They are opaque strings of the form `CgkI…`, they differ per application, and they are not
     * secret - they identify a board, they do not authorise writing to it. Set through
     * `crownfoundry.playGamesLeaderboards` as `wins:CgkI…,streak:CgkI…,puzzles:CgkI…`.
     */
    private val boardIds: Map<String, String> = BuildConfig.PLAY_GAMES_LEADERBOARDS
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

    fun start() {
        PlayGamesSdk.initialize(context)
        PlayGames.getGamesSignInClient(context as Activity).isAuthenticated
            .addOnCompleteListener { task ->
                signedIn = task.isSuccessful && task.result.isAuthenticated
            }
    }

    override fun submit(board: Leaderboard, score: Int) {
        if (!signedIn) return
        val id = boardIds[board.key] ?: return
        try {
            PlayGames.getLeaderboardsClient(context as Activity)
                .submitScore(id, score.toLong())
        } catch (failure: Exception) {
            // A leaderboard that will not accept a score is not a reason to interrupt a game.
            Log.w(TAG, "could not submit ${board.key}", failure)
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

    private companion object {
        const val TAG = "CrownFoundry.Games"

        /** Arbitrary; the result is never read, the UI is purely presentational. */
        const val REQUEST_CODE = 9004
    }
}
