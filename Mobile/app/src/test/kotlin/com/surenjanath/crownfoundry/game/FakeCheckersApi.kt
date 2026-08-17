package com.surenjanath.crownfoundry.game

import com.surenjanath.crownfoundry.api.AiTurnDto
import com.surenjanath.crownfoundry.api.AnalyticsSummaryDto
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.HealthDto
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.MatchListDto
import com.surenjanath.crownfoundry.api.MoveResultDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.PerformanceDto
import com.surenjanath.crownfoundry.api.ResignDto
import com.surenjanath.crownfoundry.api.Side

/**
 * A referee that answers from a script. Every turn-machine test drives this; nothing in
 * `app/src/test` ever opens a socket or touches `CrownFoundryClient`.
 */
class FakeCheckersApi : CheckersApi {
    var startOutcome: Outcome<MatchDto> = Outcome.Success(Fixtures.initialMatch)
    var matchOutcome: Outcome<MatchDto> = Outcome.Success(Fixtures.initialMatch)
    var resignOutcome: Outcome<ResignDto> =
        Outcome.Success(ResignDto(gameOver = true, winner = Side.AI))

    // Scriptable so the offline tests can make the referee unreachable without a socket.
    var healthOutcome: Outcome<HealthDto> = Outcome.Success(HealthDto(ok = true))
    var matchesOutcome: Outcome<MatchListDto> = Outcome.Success(MatchListDto())

    /** Consumed in order; the last entry repeats once the queue runs dry. */
    val moveOutcomes = ArrayDeque<Outcome<MoveResultDto>>()
    val aiOutcomes = ArrayDeque<Outcome<AiTurnDto>>()

    val movesSent = mutableListOf<String>()
    var startCalls = 0
        private set
    var matchCalls = 0
        private set
    var aiCalls = 0
        private set
    var resignCalls = 0
        private set

    private var lastMoveOutcome: Outcome<MoveResultDto> =
        Outcome.Success(Fixtures.afterHumanOpening())
    private var lastAiOutcome: Outcome<AiTurnDto> = Outcome.Success(Fixtures.aiReply())

    override suspend fun health() = healthOutcome

    override suspend fun startMatch(
        difficulty: String,
        playerId: String?,
        rules: com.surenjanath.crownfoundry.api.MatchRulesDto?
    ): Outcome<MatchDto> {
        startCalls += 1
        return startOutcome
    }

    override suspend fun match(matchId: String): Outcome<MatchDto> {
        matchCalls += 1
        return matchOutcome
    }

    override suspend fun matches(playerId: String?, limit: Int) = matchesOutcome

    override suspend fun playMove(matchId: String, move: String): Outcome<MoveResultDto> {
        movesSent += move
        moveOutcomes.removeFirstOrNull()?.let { lastMoveOutcome = it }
        return lastMoveOutcome
    }

    override suspend fun playMove(matchId: String, from: Int, to: Int) =
        playMove(matchId, "$from-$to")

    override suspend fun generateAiTurn(matchId: String): Outcome<AiTurnDto> {
        aiCalls += 1
        aiOutcomes.removeFirstOrNull()?.let { lastAiOutcome = it }
        return lastAiOutcome
    }

    override suspend fun resign(matchId: String): Outcome<ResignDto> {
        resignCalls += 1
        return resignOutcome
    }

    override suspend fun performance() = Outcome.Success(PerformanceDto())

    override suspend fun summary() = Outcome.Success(AnalyticsSummaryDto())
}
