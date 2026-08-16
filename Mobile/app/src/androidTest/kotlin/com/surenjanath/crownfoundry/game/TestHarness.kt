package com.surenjanath.crownfoundry.game

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import com.surenjanath.crownfoundry.api.AiTurnDto
import com.surenjanath.crownfoundry.api.AnalyticsSummaryDto
import com.surenjanath.crownfoundry.api.AppliedMoveDto
import com.surenjanath.crownfoundry.api.BoardDto
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.EvaluationDto
import com.surenjanath.crownfoundry.api.HealthDto
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.MatchListDto
import com.surenjanath.crownfoundry.api.MoveDto
import com.surenjanath.crownfoundry.api.MoveResultDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.PerformanceDto
import com.surenjanath.crownfoundry.api.PieceDto
import com.surenjanath.crownfoundry.api.ResignDto
import com.surenjanath.crownfoundry.api.ScoredMoveDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.ui.styling.Appearance
import com.surenjanath.crownfoundry.ui.styling.DefaultDarkColorPalette
import com.surenjanath.crownfoundry.ui.styling.LocalAppearance
import com.surenjanath.crownfoundry.ui.styling.typographyOf
import kotlinx.coroutines.CompletableDeferred

/** The design system, provided by hand so a screen can be hosted without MainActivity. */
@Composable
fun Themed(content: @Composable () -> Unit) {
    val colorPalette = DefaultDarkColorPalette

    CompositionLocalProvider(
        LocalAppearance provides Appearance(
            colorPalette = colorPalette,
            typography = typographyOf(
                color = colorPalette.text,
                useSystemFont = true,
                applyFontPadding = false
            ),
            thumbnailShape = RoundedCornerShape(8.dp)
        ),
        content = content
    )
}

object UiFixtures {
    const val MATCH_ID = "6f3a1c2e-0000-4000-8000-000000000001"

    const val INITIAL_FEN =
        "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"

    val initialPieces: List<PieceDto> =
        (1..12).map { PieceDto(square = it, side = Side.BLACK) } +
                (21..32).map { PieceDto(square = it, side = Side.WHITE) }

    val initialBoard = BoardDto(
        fen = INITIAL_FEN,
        sideToMove = Side.BLACK,
        pieces = initialPieces
    )

    val openingMoves = listOf(
        move("9-13"), move("9-14"),
        move("10-14"), move("10-15"),
        move("11-15"), move("11-16"),
        move("12-16")
    )

    val initialMatch = MatchDto(
        matchId = MATCH_ID,
        initialBoard = INITIAL_FEN,
        board = initialBoard,
        legalMoves = openingMoves
    )

    fun move(notation: String): MoveDto {
        val parts = notation.split('-', 'x').map(String::toInt)
        return MoveDto(notation = notation, from = parts.first(), to = parts.last())
    }

    fun afterHumanMove(notation: String = "11-15") = MoveResultDto(
        board = BoardDto(
            fen = "W:W21,...:B...",
            sideToMove = Side.WHITE,
            pieces = initialPieces.map { if (it.square == 11) it.copy(square = 15) else it }
        ),
        legalMoves = listOf(move("22-17"), move("23-18")),
        appliedMove = AppliedMoveDto(notation = notation),
        turnNumber = 1
    )

    fun aiReply(gameOver: Boolean = false, winner: String? = null) = AiTurnDto(
        aiMove = "23-18",
        aiReasoning = "Holding the centre so your right flank has nothing to trade into.",
        reasoningSource = "ollama",
        board = BoardDto(
            fen = "B:W21,...:B...",
            sideToMove = Side.BLACK,
            pieces = initialPieces.map { if (it.square == 11) it.copy(square = 15) else it }
        ),
        legalMoves = openingMoves,
        evaluation = EvaluationDto(
            qValue = 0.41,
            confidence = 0.78,
            considered = listOf(ScoredMoveDto("23-18", 0.41), ScoredMoveDto("22-17", 0.36))
        ),
        gameOver = gameOver,
        winner = winner,
        turnNumber = 2
    )
}

/**
 * The referee, scripted. [holdAiTurn] lets a test park the opponent mid-thought so the thinking
 * state can be looked at - the real call can take a minute and a half.
 */
class ScriptedApi : CheckersApi {
    var startOutcome: Outcome<MatchDto> = Outcome.Success(UiFixtures.initialMatch)
    var moveOutcome: Outcome<MoveResultDto> = Outcome.Success(UiFixtures.afterHumanMove())
    var aiOutcome: Outcome<AiTurnDto> = Outcome.Success(UiFixtures.aiReply())

    var holdAiTurn: CompletableDeferred<Unit>? = null

    val movesSent = mutableListOf<String>()
    var startCalls = 0
        private set
    var aiCalls = 0
        private set
    var resignCalls = 0
        private set

    override suspend fun health() = Outcome.Success(HealthDto(ok = true))

    override suspend fun startMatch(
        difficulty: String,
        playerId: String?,
        rules: com.surenjanath.crownfoundry.api.MatchRulesDto?
    ): Outcome<MatchDto> {
        startCalls += 1
        return startOutcome
    }

    override suspend fun match(matchId: String) = startOutcome

    override suspend fun matches(playerId: String?, limit: Int) =
        Outcome.Success(MatchListDto())

    override suspend fun playMove(matchId: String, move: String): Outcome<MoveResultDto> {
        movesSent += move
        return moveOutcome
    }

    override suspend fun playMove(matchId: String, from: Int, to: Int) =
        playMove(matchId, "$from-$to")

    override suspend fun generateAiTurn(matchId: String): Outcome<AiTurnDto> {
        aiCalls += 1
        holdAiTurn?.await()
        return aiOutcome
    }

    override suspend fun resign(matchId: String): Outcome<ResignDto> {
        resignCalls += 1
        return Outcome.Success(ResignDto(gameOver = true, winner = Side.AI))
    }

    override suspend fun performance() = Outcome.Success(PerformanceDto())

    override suspend fun summary() = Outcome.Success(AnalyticsSummaryDto())
}

/** How many nodes currently carry [tag] - the shape `waitUntil` needs. */
fun ComposeTestRule.nodesWithTag(tag: String): Int =
    onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size
