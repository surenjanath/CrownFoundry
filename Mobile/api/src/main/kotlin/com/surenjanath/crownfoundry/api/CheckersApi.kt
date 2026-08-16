package com.surenjanath.crownfoundry.api

/**
 * The only thing the app knows about the Django referee.
 *
 * Every call returns an [Outcome] rather than throwing, because on this app's screens a failure
 * is a state to draw, not an exception to crash on: the board keeps the position it had and
 * shows why the referee could not be asked.
 *
 * [baseUrl] is settable at runtime - the Backend settings screen writes to it - and takes effect
 * on the next call without rebuilding the HTTP engine.
 */
interface CheckersApi {
    suspend fun health(): Outcome<HealthDto>

    suspend fun startMatch(
        difficulty: String = "adaptive",
        playerId: String? = null,
        rules: MatchRulesDto? = null
    ): Outcome<MatchDto>

    suspend fun match(matchId: String): Outcome<MatchDto>

    suspend fun matches(playerId: String? = null, limit: Int = 50): Outcome<MatchListDto>

    /** [move] is canonical notation: `11-15`, `11x18`, `11x18x25`. */
    suspend fun playMove(matchId: String, move: String): Outcome<MoveResultDto>

    /** Lets the referee work out which legal move joins these two squares. */
    suspend fun playMove(matchId: String, from: Int, to: Int): Outcome<MoveResultDto>

    suspend fun generateAiTurn(matchId: String): Outcome<AiTurnDto>

    suspend fun resign(matchId: String): Outcome<ResignDto>

    suspend fun performance(): Outcome<PerformanceDto>

    suspend fun summary(): Outcome<AnalyticsSummaryDto>
}
