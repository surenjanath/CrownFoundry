package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.Outcome

/**
 * Two people, one phone, no opponent to wait for.
 *
 * The turn machine, the board, the animations and the match history all work against a
 * [CheckersApi]; pass-and-play differs from a normal game in exactly one respect, which is what
 * `startMatch` creates. So that is all this overrides - everything else is the offline referee,
 * unchanged, still the only authority on what is legal.
 *
 * Doing it here rather than by adding a flag to [CheckersApi] keeps the wire interface describing
 * what the Django referee actually offers. The server has no pass-and-play endpoint and should not
 * grow a parameter for a mode it will never serve.
 */
class PassAndPlayApi(private val offline: OfflineCheckersApi) : CheckersApi by offline {

    override suspend fun startMatch(
        difficulty: String,
        playerId: String?,
        rules: MatchRulesDto?
    ): Outcome<MatchDto> = offline.startPassAndPlay(rules)
}
