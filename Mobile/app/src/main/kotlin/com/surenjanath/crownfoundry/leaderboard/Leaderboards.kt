package com.surenjanath.crownfoundry.leaderboard

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Posting scores somewhere other people can see them.
 *
 * The interface exists so the rest of the app never mentions Google Play Games. That is not
 * abstraction for its own sake: the Games SDK installs a startup provider that throws if the
 * manifest has no `APP_ID`, so it cannot be a dependency of a build that has not been given one.
 * It is therefore compiled in only when `crownfoundry.playGamesAppId` is set, from a source
 * directory the build adds at the same time, and registers itself here on start-up. A build
 * without an app id has no Games code in it at all and every call below is a no-op.
 *
 * Everything the boards rank is computed on the device from games the device refereed, so a score
 * is only ever as trustworthy as the phone that sent it. That is the accepted bargain for a
 * casual leaderboard in an offline game, and the reason none of these boards rank anything that
 * would be worth cheating for.
 */
interface LeaderboardService {

    /** Whether scores can currently be posted - configured, signed in, and reachable. */
    val available: Boolean

    /** Post [score] to [board]. Silently does nothing when unavailable. */
    fun submit(board: Leaderboard, score: Int)

    /** Open the platform's own leaderboard UI, which is the only place scores are read. */
    fun show(activity: Activity, board: Leaderboard?)
}

/**
 * The one the app talks to.
 *
 * A registry rather than an injected dependency because the implementation is chosen by the
 * *build*, not by the caller, and there is exactly one of it.
 */
object Leaderboards {

    /**
     * Set by the Play Games initializer when that source set is compiled in.
     *
     * Backed by Compose state so the UI stops hiding the entry point the moment sign-in
     * completes, which happens a second or two after launch rather than before the first frame.
     */
    var service: LeaderboardService? by mutableStateOf(null)
        private set

    fun install(implementation: LeaderboardService) {
        service = implementation
    }

    /**
     * Find the platform implementation, if this build was compiled with one.
     *
     * By name, because the implementation lives in a source directory the build only adds when it
     * has been given a Play Console app id - so `main` cannot refer to the class, and referring to
     * it anyway would stop every build without leaderboards from compiling. One lookup at start-up,
     * and a build with no leaderboards takes the `ClassNotFoundException` once and never again.
     *
     * `proguard-rules.pro` keeps the class and its constructor; a reflective lookup is exactly the
     * kind of reference R8 cannot see.
     */
    fun initialise(activity: Activity) {
        if (service != null) return
        try {
            val type = Class.forName(PLAY_GAMES_CLASS)
            val implementation = type
                .getConstructor(android.content.Context::class.java)
                .newInstance(activity) as LeaderboardService
            type.getMethod("start").invoke(implementation)
            install(implementation)
        } catch (absent: ClassNotFoundException) {
            // A build without leaderboards. Nothing to install and nothing to report.
        } catch (failure: Exception) {
            // Play Games missing from the device, an id that does not resolve, a signed-out
            // player: all of them leave the app playable, which is the only thing that matters.
            android.util.Log.w("CrownFoundry", "leaderboards unavailable", failure)
        }
    }

    private const val PLAY_GAMES_CLASS =
        "com.surenjanath.crownfoundry.leaderboard.PlayGamesLeaderboards"

    val available: Boolean get() = service?.available == true

    /**
     * Post every board whose value has moved.
     *
     * Called after a finished game and after a solved puzzle. Posting all three rather than the
     * one that changed costs nothing - the platform de-duplicates and keeps only a personal best -
     * and it means a player who was signed out for a while catches up on their next result
     * instead of carrying a permanently stale board.
     */
    fun submitAll(scores: LeaderboardScores) {
        val target = service?.takeIf { it.available } ?: return
        for (board in Leaderboard.entries) target.submit(board, scores[board])
    }

    fun show(activity: Activity, board: Leaderboard? = null) {
        service?.takeIf { it.available }?.show(activity, board)
    }
}
