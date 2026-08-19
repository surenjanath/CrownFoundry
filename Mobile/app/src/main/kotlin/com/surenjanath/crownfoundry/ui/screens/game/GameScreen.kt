package com.surenjanath.crownfoundry.ui.screens.game

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.LocalWindowInsets
import com.surenjanath.crownfoundry.R
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.enums.Difficulty
import com.surenjanath.crownfoundry.ui.components.LocalMenuState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.surenjanath.crownfoundry.ui.components.board.CheckersBoard
import com.surenjanath.crownfoundry.ui.screens.matches.Fen
import com.surenjanath.crownfoundry.ui.components.board.BoardTrace
import com.surenjanath.crownfoundry.ui.components.board.TapResult
import com.surenjanath.crownfoundry.ui.components.themed.ConfirmationDialog
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.components.themed.HeaderIconButton
import com.surenjanath.crownfoundry.ui.components.themed.Menu
import com.surenjanath.crownfoundry.ui.components.themed.MenuEntry
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.ui.styling.primaryButton
import com.surenjanath.crownfoundry.utils.activeMatchIdKey
import com.surenjanath.crownfoundry.utils.activeMatchPassAndPlayKey
import com.surenjanath.crownfoundry.utils.copyToClipboard
import com.surenjanath.crownfoundry.utils.difficultyKey
import com.surenjanath.crownfoundry.utils.hapticFeedbackKey
import com.surenjanath.crownfoundry.utils.isLandscape
import com.surenjanath.crownfoundry.utils.medium
import com.surenjanath.crownfoundry.utils.playerIdKey
import com.surenjanath.crownfoundry.utils.rememberPreference
import com.surenjanath.crownfoundry.utils.secondary
import com.surenjanath.crownfoundry.utils.semiBold
import com.surenjanath.crownfoundry.utils.showEvaluationKey
import com.surenjanath.crownfoundry.utils.showLegalMovesKey
import com.surenjanath.crownfoundry.utils.showReasoningKey
import kotlinx.coroutines.launch
import java.util.UUID

const val GameScreenTag = "gameScreen"
const val ResignButtonTag = "resignButton"

/**
 * The referee for a mode. Pass-and-play is always the device's own - it needs no policy and no
 * network - and falls back to the ordinary route only in the window before offline mode is wired,
 * where no screen exists to ask.
 */
private fun apiFor(mode: GameMode): CheckersApi =
    if (mode.isPassAndPlay) Offline.passAndPlay ?: Offline.api else Offline.api

/**
 * The live board.
 *
 * [matchId] is null for "start a new one". Everything the screen knows lives in [GameState]; this
 * function is layout, gestures and the effects that drive the turn loop - nothing more.
 */
@ExperimentalFoundationApi
@ExperimentalAnimationApi
@Composable
fun GameScreen(
    matchId: String?,
    mode: GameMode = GameMode.VersusEngine,
    api: CheckersApi = apiFor(mode)
) {
    val (colorPalette, typography) = LocalAppearance.current

    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val windowInsets = LocalWindowInsets.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val showLegalMoves by rememberPreference(showLegalMovesKey, true)
    var showReasoning by rememberPreference(showReasoningKey, true)
    val showEvaluation by rememberPreference(showEvaluationKey, true)
    val useHaptics by rememberPreference(hapticFeedbackKey, true)
    val difficultyPreference by rememberPreference(difficultyKey, Difficulty.Adaptive)
    val difficulty = difficultyPreference.wire
    val flyingKings by rememberPreference(com.surenjanath.crownfoundry.utils.flyingKingsKey, true)
    val menCaptureBackwards by rememberPreference(com.surenjanath.crownfoundry.utils.menCaptureBackwardsKey, true)
    val mandatoryCapture by rememberPreference(com.surenjanath.crownfoundry.utils.mandatoryCaptureKey, true)
    var activeMatchId by rememberPreference(activeMatchIdKey, "")
    var activeMatchPassAndPlay by rememberPreference(activeMatchPassAndPlayKey, false)
    val playerId = rememberPlayerId()

    val rules = remember(flyingKings, menCaptureBackwards, mandatoryCapture) {
        com.surenjanath.crownfoundry.api.MatchRulesDto(
            flyingKings = flyingKings,
            menCaptureBackwards = menCaptureBackwards,
            mandatoryCapture = mandatoryCapture
        )
    }

    // Survives process death: whatever match was actually started is what gets resumed, not the
    // null the route was originally opened with.
    var resumeId by rememberSaveable { mutableStateOf(matchId) }

    val state = remember(api, mode) {
        GameState(
            api = api,
            difficulty = difficulty,
            playerId = playerId.takeIf { it.isNotEmpty() },
            rules = rules,
            mode = mode,
            onMatchIdChanged = { id ->
                resumeId = id
                activeMatchId = id.orEmpty()
                // Recorded beside the id so "Resume" comes back to the game that was left, not to
                // the engine answering for whoever had the other chair.
                activeMatchPassAndPlay = id != null && mode.isPassAndPlay
            }
        )
    }

    LaunchedEffect(state) {
        if (state.phase == GamePhase.Idle) state.begin(resumeId)
    }

    // A finished game is the moment there is something new to send. If it was played offline the
    // outbox now has a game in it; if it was played online the server has just retrained, and
    // either way this is when the device is most likely to be behind.
    LaunchedEffect(state.isOver) {
        // Pass-and-play produces nothing the server wants: no engine move, no training signal.
        if (state.isOver && !mode.isPassAndPlay) Offline.synchroniseInBackground(playerId)
    }

    var confirmingResign by rememberSaveable { mutableStateOf(false) }
    var showingConsidered by rememberSaveable { mutableStateOf(false) }
    var dismissedGameOver by rememberSaveable { mutableStateOf(false) }

    fun buzz(type: HapticFeedbackType) {
        if (useHaptics) haptics.performHapticFeedback(type)
    }

    val onSquareTap: (Int) -> Unit = { square ->
        when (val result = state.tap(square)) {
            is TapResult.Ready -> {
                buzz(HapticFeedbackType.LongPress)
                scope.launch { state.play(result.notation) }
            }

            is TapResult.Selected, is TapResult.Advanced ->
                buzz(HapticFeedbackType.TextHandleMove)

            else -> Unit
        }
    }

    val openMenu = {
        menuState.display {
            Menu {
                MenuEntry(
                    icon = R.drawable.flag,
                    text = "Resign",
                    enabled = !state.isOver,
                    onClick = {
                        menuState.hide()
                        confirmingResign = true
                    }
                )

                MenuEntry(
                    icon = R.drawable.sparkles,
                    text = "New match",
                    secondaryText = "Abandons this position",
                    onClick = {
                        menuState.hide()
                        dismissedGameOver = false
                        scope.launch { state.rematch() }
                    }
                )

                MenuEntry(
                    icon = R.drawable.brain,
                    text = "What it considered",
                    secondaryText = state.evaluation?.considered?.size
                        ?.let { "$it moves it weighed on its last turn" }
                        ?: "It has not moved yet",
                    enabled = !state.evaluation?.considered.isNullOrEmpty(),
                    onClick = {
                        menuState.hide()
                        showingConsidered = true
                    }
                )

                MenuEntry(
                    icon = R.drawable.text,
                    text = "Copy FEN",
                    secondaryText = state.fen.ifEmpty { "The position is not loaded yet" },
                    enabled = state.fen.isNotEmpty(),
                    onClick = {
                        menuState.hide()
                        context.copyToClipboard(state.fen)
                    }
                )

                MenuEntry(
                    icon = R.drawable.chevron_back,
                    text = "Leave the board",
                    onClick = {
                        menuState.hide()
                        backDispatcher?.onBackPressed()
                    }
                )
            }
        }
    }


    // Everything derived about the game is computed once, here, and handed down. The seats, the
    // action bar and the game-over dialog all read the same answer rather than each working it out.
    val elapsed = rememberMoveClock(turnKey = state.turnNumber, running = state.acceptsTaps)

    val presentation = state.present(
        showReasoning = showReasoning,
        showEvaluation = showEvaluation,
        engineLabel = Offline.engine.state.label
            .takeIf { Offline.hybridOrNull?.isOffline == true },
        elapsedSeconds = elapsed
    )

    val boardSurface: @Composable (Modifier) -> Unit = { boardModifier ->
        CheckersBoard(
            pieces = state.pieces,
            legalMoves = state.legalMoves,
            selection = state.selection,
            animation = state.animation,
            lastMove = state.lastMove,
            suggestion = state.hint?.let(::traceOf),
            showHints = showLegalMoves,
            enabled = state.acceptsTaps,
            onAnimationEnd = state::clearAnimation,
            onSquareTap = onSquareTap,
            modifier = boardModifier
        )
    }

    val actionBar: @Composable (Modifier) -> Unit = { barModifier ->
        GameActionBar(
            isOver = state.isOver,
            event = presentation.event,
            showReasoning = showReasoning,
            hintEnabled = state.acceptsTaps && !state.hinting,
            hintShowing = state.hint != null,
            onHint = {
                if (state.hint != null) state.clearHint()
                else scope.launch { state.requestHint() }
            },
            onToggleReasoning = { showReasoning = !showReasoning },
            onResign = { confirmingResign = true },
            onMenu = openMenu,
            onRematch = { scope.launch { state.rematch() } },
            modifier = barModifier
        )
    }

    val failureCard: @Composable (Modifier) -> Unit = { failureModifier ->
        state.failure?.let { failure ->
            GameFailureCard(
                failure = failure,
                onRetry = { scope.launch { state.retry() } },
                onDismiss = state::dismissFailure,
                modifier = failureModifier
            )
        }
    }

    Box(
        modifier = Modifier
            .testTag(GameScreenTag)
            .background(colorPalette.background0)
            .fillMaxSize()
    ) {
        if (isLandscape) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical).asPaddingValues())
                    .padding(16.dp)
            ) {
                // The board takes a square of whatever height it is given. It is measured before
                // the panel beside it and cannot see the panel's contents, so nothing that appears
                // over there can resize or move it.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxHeight()
                ) {
                    boardSurface(
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colorPalette.background1)
                    )
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    GameTopRail(
                        moveLabel = presentation.moveLabel,
                        onBack = { backDispatcher?.onBackPressed() }
                    )

                    SeatCard(
                        seat = presentation.opponent,
                        modifier = Modifier.testTag(AiPanelTag)
                    )

                    CommentaryStrip(commentary = presentation.commentary)

                    // The slack lives here, between the two chairs, so the rows below stay put.
                    Spacer(modifier = Modifier.weight(1f))

                    SeatCard(seat = presentation.you)

                    actionBar(Modifier.fillMaxWidth())
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
                    .padding(top = 20.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
            ) {
                // Three weighted regions. Each one's height is a fixed share of what is left after
                // insets and padding, so it is settled at measure time and no row appearing inside
                // it can change it. That is the whole point: the board's position is a function of
                // the window and nothing else, and it stays where the player last looked at it.
                Box(
                    modifier = Modifier
                        .weight(TopRegionWeight)
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    // The rail sits at the top of the screen where a header belongs; the
                    // opponent and what it is saying sit against the board, where they are read.
                    // The slack goes between them rather than above everything.
                    Column(
                        verticalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        GameTopRail(
                            moveLabel = presentation.moveLabel,
                            onBack = { backDispatcher?.onBackPressed() }
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SeatCard(
                                seat = presentation.opponent,
                                modifier = Modifier.testTag(AiPanelTag)
                            )

                            CommentaryStrip(commentary = presentation.commentary)
                        }
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(BoardRegionWeight)
                        .fillMaxWidth()
                ) {
                    boardSurface(
                        Modifier
                            .fillMaxSize()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(colorPalette.background1)
                    )
                }

                Box(
                    contentAlignment = Alignment.TopCenter,
                    modifier = Modifier
                        .weight(BottomRegionWeight)
                        .fillMaxWidth()
                        .clipToBounds()
                ) {
                    // Fixed height, scrolling contents: the region cannot grow and shove the
                    // board, and a failure card appearing cannot bury the resign button either.
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        SeatCard(seat = presentation.you)

                        actionBar(Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // A failure floats over the board rather than joining the column.
        //
        // It is an interruption, not a fixture: giving it a place in the layout meant either
        // shoving the board down to make room for it or hiding its "Try again" button below the
        // fold, and that button is the entire reason the card exists.
        failureCard(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp)
                .widthIn(max = 480.dp)
        )
    }


    if (showingConsidered) {
        ConsideredDialog(
            considered = state.evaluation?.considered.orEmpty(),
            played = state.lastAiMove,
            onDismiss = { showingConsidered = false }
        )
    }

    if (confirmingResign) {
        ConfirmationDialog(
            text = if (mode.isPassAndPlay) {
                "Resign this match? The player to move gives it to the other side."
            } else {
                "Resign this match? It counts as a win for the machine, and it will learn " +
                        "from it."
            },
            confirmText = "Resign",
            onDismiss = { confirmingResign = false },
            onConfirm = { scope.launch { state.resign() } }
        )
    }

    if (state.isOver && !dismissedGameOver) {
        GameOverDialog(
            winner = state.winner,
            counts = state.counts,
            passAndPlay = mode.isPassAndPlay,
            onDismiss = { dismissedGameOver = true },
            onRematch = {
                dismissedGameOver = false
                scope.launch { state.rematch() }
            }
        )
    }
}

/**
 * A move notation as two ends of an arrow.
 *
 * Reuses the review screen's parser rather than growing a second one - "11-15" and "11x18x25" are
 * read the same way wherever they appear, and a multi-jump is drawn from where it started to where
 * it finished.
 */
private fun traceOf(notation: String): BoardTrace? {
    val squares = Fen.squaresOfMove(notation)
    return if (squares.size >= 2) BoardTrace(squares.first(), squares.last()) else null
}

/**
 * The line above the board: out of here, and how far in you are.
 *
 * Back navigation used to be wedged inside the opponent's card, which made leaving the game look
 * like something you did to the opponent. It belongs to the screen, so it sits on the screen's own
 * rail - and that rail was going to be empty space otherwise, so it carries the move number, which
 * nothing else on the board tells you.
 */
@Composable
private fun GameTopRail(
    moveLabel: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth()
    ) {
        HeaderIconButton(
            icon = R.drawable.chevron_back,
            color = colorPalette.text,
            contentDescription = "Leave the board",
            onClick = onBack
        )

        BasicText(
            text = moveLabel,
            style = typography.xxs.medium.secondary,
            maxLines = 1,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}

/**
 * How the portrait screen is divided.
 *
 * The board's share is large because the board is what the screen is for; the two seats take a
 * fixed cut of the remainder rather than as much as their contents happen to want. Weights rather
 * than heights so the split survives a large font scale and a short screen, where fixed heights
 * would clip the text or run the board off the bottom.
 */
private const val TopRegionWeight = 1.35f
private const val BoardRegionWeight = 4f
private const val BottomRegionWeight = 1.15f

/**
 * A clock for the move in front of you, not for the match.
 *
 * The old one only ever paused and resumed, so what sat behind a clock icon next to the turn
 * indicator was really the total time you had spent thinking all game. This restarts when the turn
 * number changes, and remembers which turn it was last cleared for, so a rotation does not hand
 * the player a free reset.
 */
@Composable
private fun rememberMoveClock(turnKey: Int, running: Boolean): Int {
    var seconds by rememberSaveable { mutableIntStateOf(0) }
    var clockedTurn by rememberSaveable { mutableIntStateOf(turnKey) }

    LaunchedEffect(turnKey) {
        if (clockedTurn != turnKey) {
            clockedTurn = turnKey
            seconds = 0
        }
    }

    LaunchedEffect(turnKey, running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(1000)
            seconds++
        }
    }

    return seconds
}

/** A stable identity for this install, minted once, so the engine can model one opponent. */
@Composable
private fun rememberPlayerId(): String {
    var stored by rememberPreference(playerIdKey, "")
    val minted = remember { UUID.randomUUID().toString() }

    LaunchedEffect(stored) {
        if (stored.isEmpty()) stored = minted
    }

    return stored.ifEmpty { minted }
}
