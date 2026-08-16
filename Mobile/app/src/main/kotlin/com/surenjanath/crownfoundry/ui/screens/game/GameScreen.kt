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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.enums.Difficulty
import com.surenjanath.crownfoundry.ui.components.LocalMenuState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.surenjanath.crownfoundry.ui.components.board.CheckersBoard
import com.surenjanath.crownfoundry.ui.components.themed.ConfirmationDialog
import com.surenjanath.crownfoundry.ui.components.themed.Header
import com.surenjanath.crownfoundry.ui.components.themed.HeaderIconButton
import com.surenjanath.crownfoundry.ui.components.themed.Menu
import com.surenjanath.crownfoundry.ui.components.themed.MenuEntry
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.ui.styling.primaryButton
import com.surenjanath.crownfoundry.utils.activeMatchIdKey
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
    api: CheckersApi = CrownFoundryClient
) {
    val (colorPalette, typography) = LocalAppearance.current

    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val windowInsets = LocalWindowInsets.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    val showLegalMoves by rememberPreference(showLegalMovesKey, true)
    val showReasoning by rememberPreference(showReasoningKey, true)
    val showEvaluation by rememberPreference(showEvaluationKey, true)
    val useHaptics by rememberPreference(hapticFeedbackKey, true)
    val difficultyPreference by rememberPreference(difficultyKey, Difficulty.Adaptive)
    val difficulty = difficultyPreference.wire
    val flyingKings by rememberPreference(com.surenjanath.crownfoundry.utils.flyingKingsKey, true)
    val menCaptureBackwards by rememberPreference(com.surenjanath.crownfoundry.utils.menCaptureBackwardsKey, true)
    val mandatoryCapture by rememberPreference(com.surenjanath.crownfoundry.utils.mandatoryCaptureKey, true)
    var activeMatchId by rememberPreference(activeMatchIdKey, "")
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

    val state = remember(api) {
        GameState(
            api = api,
            difficulty = difficulty,
            playerId = playerId.takeIf { it.isNotEmpty() },
            rules = rules,
            onMatchIdChanged = { id ->
                resumeId = id
                activeMatchId = id.orEmpty()
            }
        )
    }

    LaunchedEffect(state) {
        if (state.phase == GamePhase.Idle) state.begin(resumeId)
    }

    var confirmingResign by rememberSaveable { mutableStateOf(false) }
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

    Box(
        modifier = Modifier
            .testTag(GameScreenTag)
            .background(colorPalette.background0)
            .fillMaxSize()
    ) {
        val board: @Composable (Modifier) -> Unit = { boardModifier ->
            CheckersBoard(
                pieces = state.pieces,
                legalMoves = state.legalMoves,
                selection = state.selection,
                animation = state.animation,
                lastMove = state.lastMove,
                showHints = showLegalMoves,
                enabled = state.acceptsTaps,
                onAnimationEnd = state::clearAnimation,
                onSquareTap = onSquareTap,
                modifier = boardModifier
            )
        }

        val side: @Composable (Modifier) -> Unit = { sideModifier ->
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = sideModifier
            ) {
                state.failure?.let { failure ->
                    GameFailureCard(
                        failure = failure,
                        onRetry = { scope.launch { state.retry() } },
                        onDismiss = state::dismissFailure,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                AiPanel(
                    thinking = state.phase == GamePhase.Thinking,
                    reasoning = state.reasoning,
                    spokeThroughOllama = state.spokeThroughOllama,
                    evaluation = state.evaluation,
                    chosen = state.lastAiMove,
                    showReasoning = showReasoning,
                    showEvaluation = showEvaluation,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .padding(16.dp)
                        .padding(windowInsets.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical).asPaddingValues())
                ) {
                    board(Modifier.fillMaxSize())
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(windowInsets.only(WindowInsetsSides.End + WindowInsetsSides.Vertical).asPaddingValues())
                ) {
                    MatchHeader(
                        state = state,
                        onResign = { confirmingResign = true },
                        onMenu = openMenu,
                        onBack = { backDispatcher?.onBackPressed() }
                    )
                    MatchStatsBar(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                    side(Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(96.dp))
                }
            }
        } else {
            Column(
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(windowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top + WindowInsetsSides.Bottom).asPaddingValues())
                    .padding(top = 16.dp, bottom = 16.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MatchHeader(
                        state = state,
                        onResign = { confirmingResign = true },
                        onMenu = openMenu,
                        onBack = { backDispatcher?.onBackPressed() }
                    )
                    MatchStatsBar(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .aspectRatio(1f)
                ) {
                    board(Modifier.fillMaxSize())
                }

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    state.failure?.let { failure ->
                        GameFailureCard(
                            failure = failure,
                            onRetry = { scope.launch { state.retry() } },
                            onDismiss = state::dismissFailure,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AiPanel(
                        thinking = state.phase == GamePhase.Thinking,
                        reasoning = state.reasoning,
                        spokeThroughOllama = state.spokeThroughOllama,
                        evaluation = state.evaluation,
                        chosen = state.lastAiMove,
                        showReasoning = showReasoning,
                        showEvaluation = showEvaluation,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (confirmingResign) {
        ConfirmationDialog(
            text = "Resign this match? It counts as a win for the machine, and it will learn " +
                    "from it.",
            confirmText = "Resign",
            onDismiss = { confirmingResign = false },
            onConfirm = { scope.launch { state.resign() } }
        )
    }

    if (state.isOver && !dismissedGameOver) {
        GameOverDialog(
            winner = state.winner,
            counts = state.counts,
            onDismiss = { dismissedGameOver = true },
            onRematch = {
                dismissedGameOver = false
                scope.launch { state.rematch() }
            }
        )
    }
}

/** Whose move it is, which turn, and how much wood is still on the board. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MatchHeader(
    state: GameState,
    onResign: () -> Unit,
    onMenu: () -> Unit,
    onBack: () -> Unit
) {
    val (colorPalette, typography) = LocalAppearance.current

    val title = when {
        state.phase == GamePhase.Loading -> "Setting up"
        state.isOver -> when (state.winner) {
            Side.HUMAN -> "You win"
            Side.AI -> "It wins"
            Side.DRAW -> "Draw"
            else -> "Finished"
        }
        state.mustCapture -> "You must capture"
        state.sideToMove == Side.HUMAN -> "Your move"
        else -> "Its move"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // Left: Timer and Flag Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameTimer(isActive = !state.isOver && state.phase != GamePhase.Loading)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        onClick = { if (!state.isOver) onResign() else onMenu() },
                        onLongClick = onMenu
                    )
                    .background(colorPalette.background1)
                    .testTag(ResignButtonTag)
            ) {
                Image(
                    painter = painterResource(R.drawable.flag),
                    contentDescription = "Resign or menu",
                    colorFilter = ColorFilter.tint(
                        if (!state.isOver) colorPalette.text else colorPalette.textDisabled
                    ),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Right: Status, Turn, Tallies, Back Button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                BasicText(
                    text = title,
                    style = typography.m.semiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                BasicText(
                    text = "Turn ${state.turnNumber}",
                    style = typography.xxs.medium.secondary
                )
            }

            val counts = state.counts

            Tally(
                color = colorPalette.accent,
                total = counts.black,
                kings = counts.blackKings,
                label = "You"
            )

            Tally(
                color = colorPalette.text,
                total = counts.white,
                kings = counts.whiteKings,
                label = "It"
            )

            HeaderIconButton(
                icon = R.drawable.chevron_back,
                color = colorPalette.textSecondary,
                onClick = onBack
            )
        }
    }
}

@Composable
private fun GameTimer(isActive: Boolean) {
    val (colorPalette, typography) = LocalAppearance.current
    var seconds by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            delay(1000)
            seconds++
        }
    }

    val minutes = seconds / 60
    val remSeconds = seconds % 60
    val timeText = "%02d:%02d".format(minutes, remSeconds)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorPalette.background1)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.time),
            contentDescription = null,
            colorFilter = ColorFilter.tint(colorPalette.accent),
            modifier = Modifier.size(14.dp)
        )
        BasicText(
            text = timeText,
            style = typography.xs.semiBold
        )
    }
}

@Composable
private fun MatchStatsBar(state: GameState, modifier: Modifier = Modifier) {
    val (colorPalette, typography) = LocalAppearance.current
    val counts = state.counts

    val humanPieces = counts.black
    val aiPieces = counts.white
    val totalPieces = (humanPieces + aiPieces).coerceAtLeast(1)
    val humanFraction = (humanPieces.toFloat() / totalPieces).coerceIn(0.05f, 0.95f)

    val materialDiff = humanPieces - aiPieces
    val kingsDiff = counts.blackKings - counts.whiteKings

    val capturedWhite = (12 - counts.white).coerceAtLeast(0)
    val capturedBlack = (12 - counts.black).coerceAtLeast(0)

    val diffText = when {
        materialDiff > 0 -> "+$materialDiff Pieces"
        materialDiff < 0 -> "-${-materialDiff} Pieces"
        else -> "Equal Pieces"
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colorPalette.background1)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Difficulty pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colorPalette.background2)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    BasicText(
                        text = state.difficulty.replaceFirstChar { it.uppercase() },
                        style = typography.xxs.semiBold.copy(color = colorPalette.accent)
                    )
                }

                // Material diff badge
                BasicText(
                    text = diffText,
                    style = typography.xxs.medium.secondary
                )

                if (kingsDiff != 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.crown),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(
                                if (kingsDiff > 0) colorPalette.accent else colorPalette.textSecondary
                            ),
                            modifier = Modifier.size(10.dp)
                        )
                        BasicText(
                            text = if (kingsDiff > 0) "+$kingsDiff" else "$kingsDiff",
                            style = typography.xxs.medium.secondary
                        )
                    }
                }

                // Rule variant badge if non-standard
                state.rules?.let { r ->
                    if (r.flyingKings || r.menCaptureBackwards) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colorPalette.background2)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            BasicText(
                                text = if (r.flyingKings && r.menCaptureBackwards) "Flying & Back"
                                else if (r.flyingKings) "Flying Kings"
                                else "Back Jumps",
                                style = typography.xxs.medium.secondary
                            )
                        }
                    }
                }
            }

            // Captured pieces summary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    BasicText(text = "Taken:", style = typography.xxs.medium.secondary)
                    if (capturedWhite > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Spacer(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colorPalette.text)
                            )
                            BasicText(text = "$capturedWhite", style = typography.xxs.semiBold)
                        }
                    }
                    if (capturedBlack > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Spacer(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(colorPalette.accent)
                            )
                            BasicText(text = "$capturedBlack", style = typography.xxs.semiBold)
                        }
                    }
                    if (capturedWhite == 0 && capturedBlack == 0) {
                        BasicText(text = "0", style = typography.xxs.medium.secondary)
                    }
                }

                // AI Elo
                if (state.aiStatus.elo > 0) {
                    BasicText(
                        text = "${state.aiStatus.elo} Elo",
                        style = typography.xxs.medium.secondary
                    )
                }
            }
        }

        // Segmented live balance bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colorPalette.background2)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(humanFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(colorPalette.accent)
            )
        }
    }
}

@Composable
private fun Tally(color: Color, total: Int, kings: Int, label: String) {
    val (colorPalette, typography) = LocalAppearance.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colorPalette.background1)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Spacer(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )

        BasicText(text = "$total", style = typography.xs.semiBold)

        if (kings > 0) {
            Image(
                painter = painterResource(R.drawable.crown),
                contentDescription = null,
                colorFilter = ColorFilter.tint(colorPalette.textSecondary),
                modifier = Modifier.size(10.dp)
            )

            BasicText(text = "$kings", style = typography.xxs.medium.secondary)
        }

        BasicText(text = label, style = typography.xxs.medium.secondary)
    }
}

/**
 * Resign on a tap, everything else behind a long press - the same gesture the rest of the app uses
 * to reach a [Menu].
 */
@ExperimentalFoundationApi
@Composable
private fun BoxScope.GameActions(
    enabled: Boolean,
    onResign: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (colorPalette) = LocalAppearance.current

    Box(
        modifier = modifier
            .align(Alignment.BottomEnd)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = { if (enabled) onResign() else onMenu() },
                onLongClick = onMenu
            )
            .background(colorPalette.primaryButton)
            .size(62.dp)
            .testTag(ResignButtonTag)
    ) {
        Image(
            painter = painterResource(R.drawable.flag),
            contentDescription = null,
            colorFilter = ColorFilter.tint(
                if (enabled) colorPalette.text else colorPalette.textDisabled
            ),
            modifier = Modifier
                .align(Alignment.Center)
                .size(20.dp)
        )
    }
}

/** A stable identity for this install, minted once, so the AI can model one opponent over time. */
@Composable
private fun rememberPlayerId(): String {
    var stored by rememberPreference(playerIdKey, "")
    val minted = remember { UUID.randomUUID().toString() }

    LaunchedEffect(stored) {
        if (stored.isEmpty()) stored = minted
    }

    return stored.ifEmpty { minted }
}
