package com.surenjanath.crownfoundry.ui.screens.home

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import it.vfsfitvnm.compose.persist.PersistMapCleanup
import it.vfsfitvnm.compose.routing.RouteHandler
import it.vfsfitvnm.compose.routing.defaultStacking
import it.vfsfitvnm.compose.routing.defaultStill
import it.vfsfitvnm.compose.routing.defaultUnstacking
import it.vfsfitvnm.compose.routing.isStacking
import it.vfsfitvnm.compose.routing.isUnknown
import it.vfsfitvnm.compose.routing.isUnstacking
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.ui.components.themed.Scaffold
import com.surenjanath.crownfoundry.ui.screens.gameRoute
import com.surenjanath.crownfoundry.ui.screens.globalRoutes
import com.surenjanath.crownfoundry.ui.screens.insights.InsightsScreen
import com.surenjanath.crownfoundry.ui.screens.matches.MatchReviewScreen
import com.surenjanath.crownfoundry.ui.screens.matches.MatchesScreen
import com.surenjanath.crownfoundry.ui.screens.puzzleRoute
import com.surenjanath.crownfoundry.ui.screens.puzzles.PuzzleScreen
import com.surenjanath.crownfoundry.ui.screens.puzzles.PuzzlesScreen
import com.surenjanath.crownfoundry.ui.screens.reviewRoute
import com.surenjanath.crownfoundry.ui.screens.settings.SettingsScreen
import com.surenjanath.crownfoundry.ui.screens.settingsRoute
import com.surenjanath.crownfoundry.utils.homeScreenTabIndexKey
import com.surenjanath.crownfoundry.utils.rememberPreference

@ExperimentalFoundationApi
@ExperimentalAnimationApi
@Composable
fun HomeScreen() {
    val saveableStateHolder = rememberSaveableStateHolder()

    PersistMapCleanup("home/")

    RouteHandler(
        listenToGlobalEmitter = true,
        transitionSpec = {
            when {
                isStacking -> defaultStacking
                isUnstacking -> defaultUnstacking
                isUnknown -> defaultStill
                else -> defaultStill
            }
        }
    ) {
        globalRoutes()

        settingsRoute {
            SettingsScreen()
        }

        reviewRoute { matchId ->
            MatchReviewScreen(matchId = matchId)
        }

        puzzleRoute { puzzleId ->
            PuzzleScreen(puzzleId = puzzleId)
        }

        host {
            val (tabIndex, onTabChanged) = rememberPreference(
                homeScreenTabIndexKey,
                defaultValue = 0
            )

            Scaffold(
                topIconButtonId = R.drawable.equalizer,
                onTopIconButtonClick = { settingsRoute.global() },
                tabIndex = tabIndex,
                onTabChanged = onTabChanged,
                tabColumnContent = { Tab ->
                    Tab(0, "Play", R.drawable.sparkles)
                    Tab(1, "Matches", R.drawable.time)
                    Tab(2, "Puzzles", R.drawable.shapes)
                    Tab(3, "Insights", R.drawable.trending)
                }
            ) { currentTabIndex ->
                saveableStateHolder.SaveableStateProvider(key = currentTabIndex) {
                    when (currentTabIndex) {
                        0 -> PlayScreen(
                            onPlay = { matchId -> gameRoute.global(matchId, false) },
                            onPassAndPlay = { gameRoute.global(null, true) },
                            onResume = { matchId, passAndPlay ->
                                gameRoute.global(matchId, passAndPlay)
                            },
                            onSeeInsights = { onTabChanged(3) }
                        )

                        1 -> MatchesScreen(
                            onReviewMatch = { matchId -> reviewRoute.global(matchId) },
                            onResumeMatch = { matchId, passAndPlay ->
                                gameRoute.global(matchId, passAndPlay)
                            }
                        )

                        2 -> PuzzlesScreen(
                            onOpenPuzzle = { puzzleId -> puzzleRoute.global(puzzleId) }
                        )

                        3 -> InsightsScreen()
                    }
                }
            }
        }
    }
}
