package com.surenjanath.crownfoundry.offline

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.surenjanath.crownfoundry.api.AiTurnDto
import com.surenjanath.crownfoundry.api.AnalyticsSummaryDto
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.HealthDto
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.MatchListDto
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.MoveResultDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.PerformanceDto
import com.surenjanath.crownfoundry.api.ResignDto

/**
 * One [CheckersApi] over two referees.
 *
 * The routing rule is deliberately boring, because the alternative is a class of bug that only
 * ever shows up as a lost game:
 *
 * * **a match belongs to whoever started it.** An `offline-` id is refereed here, forever; a server
 *   uuid goes to the server, forever. A game is never migrated mid-flight - the two referees keep
 *   separate state, and a position that exists in both is a position that can disagree with itself.
 * * **only starting a match may fall back.** If the referee cannot be reached when the player taps
 *   Play, they get an offline game instead of an error. Everything else keeps the existing retry
 *   path, which the game screen already draws properly.
 * * **read-only calls degrade quietly.** History and analytics answer from whichever side is
 *   available, merged when both are.
 *
 * [isOffline] is what the UI badges. It is set by what actually happened on the last call, not by
 * a connectivity probe - the only reliable evidence that the referee is unreachable is having just
 * failed to reach it.
 */
class HybridCheckersApi(
    private val remote: CheckersApi,
    private val local: OfflineCheckersApi,
    private val preferences: EnginePreferences,
    private val engine: EngineStore = EngineStore,
    /**
     * Whether there is a referee to talk to at all.
     *
     * False for a build published without a server, and for one whose player has not named one.
     * It is checked on every call rather than captured once, because the player can type an
     * address into Settings mid-session and expect the next call to use it.
     */
    private val backendAvailable: () -> Boolean = { true }
) : CheckersApi {

    /** True when the last call that mattered was served by the on-device engine. */
    var isOffline by mutableStateOf(false)
        private set

    /** Set when a fall-back happened, so the game screen can say why it is offline. */
    var lastFallbackReason by mutableStateOf<ApiError?>(null)
        private set

    /** The player asked for offline play outright, and there is an engine to give them. */
    private val prefersLocal get() = preferences.preferOffline && engine.state.canPlayOffline

    /**
     * There is no referee to reach, so every call is answered here.
     *
     * Distinct from [prefersLocal], which is a preference and requires an engine to honour. This
     * is a fact about the deployment: with no address configured there is nothing to try, and
     * attempting it anyway would turn every screen into a connection error for no reason. When
     * there is also no engine the local referee says so itself, which is the honest message.
     */
    private val offlineOnly get() = !backendAvailable()

    override suspend fun health(): Outcome<HealthDto> {
        if (offlineOnly || prefersLocal) return local.health().also { isOffline = true }
        return when (val outcome = remote.health()) {
            is Outcome.Success -> outcome.also { isOffline = false }
            is Outcome.Failure ->
                if (canFallBack(outcome.reason)) local.health().also { isOffline = true }
                else outcome
        }
    }

    override suspend fun startMatch(
        difficulty: String,
        playerId: String?,
        rules: MatchRulesDto?
    ): Outcome<MatchDto> {
        if (offlineOnly || prefersLocal) {
            return local.startMatch(difficulty, playerId, rules).also { isOffline = true }
        }

        return when (val outcome = remote.startMatch(difficulty, playerId, rules)) {
            is Outcome.Success -> {
                isOffline = false
                lastFallbackReason = null
                outcome
            }

            is Outcome.Failure -> {
                if (!canFallBack(outcome.reason)) return outcome
                val fallback = local.startMatch(difficulty, playerId, rules)
                if (fallback is Outcome.Success) {
                    isOffline = true
                    lastFallbackReason = outcome.reason
                    fallback
                } else {
                    // No engine either. The referee's own error is the more useful of the two:
                    // "cannot reach the referee" beats "no engine installed" when both are true.
                    outcome
                }
            }
        }
    }

    override suspend fun match(matchId: String): Outcome<MatchDto> =
        route(matchId) { it.match(matchId) }

    override suspend fun matches(playerId: String?, limit: Int): Outcome<MatchListDto> {
        val offline = local.matches(playerId, limit).valueOrNull?.matches.orEmpty()
        if (offlineOnly || prefersLocal) {
            isOffline = true
            return Outcome.Success(MatchListDto(ok = true, matches = offline))
        }

        return when (val outcome = remote.matches(playerId, limit)) {
            is Outcome.Success -> {
                isOffline = false
                // Newest first across both referees, so an offline game sits where it was played.
                val merged = (offline + outcome.value.matches)
                    .sortedByDescending { it.startTime }
                    .take(limit)
                Outcome.Success(outcome.value.copy(matches = merged))
            }

            is Outcome.Failure ->
                if (canFallBack(outcome.reason)) {
                    isOffline = true
                    Outcome.Success(MatchListDto(ok = true, matches = offline))
                } else outcome
        }
    }

    override suspend fun playMove(matchId: String, move: String): Outcome<MoveResultDto> =
        route(matchId) { it.playMove(matchId, move) }

    override suspend fun playMove(matchId: String, from: Int, to: Int): Outcome<MoveResultDto> =
        route(matchId) { it.playMove(matchId, from, to) }

    override suspend fun generateAiTurn(matchId: String): Outcome<AiTurnDto> =
        route(matchId) { it.generateAiTurn(matchId) }

    override suspend fun resign(matchId: String): Outcome<ResignDto> =
        route(matchId) { it.resign(matchId) }

    override suspend fun performance(): Outcome<PerformanceDto> {
        if (offlineOnly || prefersLocal) return local.performance().also { isOffline = true }
        return when (val outcome = remote.performance()) {
            is Outcome.Success -> outcome.also { isOffline = false }
            is Outcome.Failure ->
                if (canFallBack(outcome.reason)) local.performance().also { isOffline = true }
                else outcome
        }
    }

    override suspend fun summary(): Outcome<AnalyticsSummaryDto> {
        if (offlineOnly || prefersLocal) return local.summary().also { isOffline = true }
        return when (val outcome = remote.summary()) {
            is Outcome.Success -> outcome.also { isOffline = false }
            is Outcome.Failure ->
                if (canFallBack(outcome.reason)) local.summary().also { isOffline = true }
                else outcome
        }
    }

    /** A match belongs to whoever started it, and this is the only place that decides which. */
    private suspend fun <T> route(
        matchId: String,
        call: suspend (CheckersApi) -> Outcome<T>
    ): Outcome<T> {
        if (offlineOnly || LocalMatchStore.isOffline(matchId)) {
            isOffline = true
            return call(local)
        }
        return call(remote).also { if (it is Outcome.Success) isOffline = false }
    }

    /**
     * Whether this failure is the kind offline play can answer.
     *
     * Only connectivity qualifies. A referee that answered and said no - an illegal move, a
     * finished match, a match id it has never heard of - has given a real answer, and replacing it
     * with a locally invented one would hide a genuine disagreement between client and server.
     */
    private fun canFallBack(reason: ApiError): Boolean =
        engine.state.canPlayOffline &&
                (reason is ApiError.Unreachable || reason is ApiError.Timeout)
}
