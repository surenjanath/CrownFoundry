package com.surenjanath.crownfoundry.ui.screens

import android.annotation.SuppressLint
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import it.vfsfitvnm.compose.routing.Route0
import it.vfsfitvnm.compose.routing.Route1
import it.vfsfitvnm.compose.routing.RouteHandlerScope
import com.surenjanath.crownfoundry.ui.screens.game.GameScreen

/** The live board. Carries a match id, or null to start a fresh one. */
val gameRoute = Route1<String?>("gameRoute")

/** A finished match, replayed ply by ply. */
val reviewRoute = Route1<String>("reviewRoute")

val settingsRoute = Route0("settingsRoute")

@SuppressLint("ComposableNaming")
@Suppress("NOTHING_TO_INLINE")
@ExperimentalAnimationApi
@ExperimentalFoundationApi
@Composable
inline fun RouteHandlerScope.globalRoutes() {
    gameRoute { matchId ->
        GameScreen(matchId = matchId)
    }
}
