package com.surenjanath.crownfoundry.ui.screens.game

import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.engine.Board
import com.surenjanath.crownfoundry.engine.DEFAULT_NODE_BUDGET
import com.surenjanath.crownfoundry.engine.Knobs
import com.surenjanath.crownfoundry.engine.LocalAgent
import com.surenjanath.crownfoundry.offline.EngineStore
import com.surenjanath.crownfoundry.offline.toEngineRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asking the engine what you should play.
 *
 * The same policy that is trying to beat you will also tell you what it would do in your seat,
 * which is the most useful thing it owns and it was only ever pointed the other way. It runs on the
 * device, so a hint costs nothing and works with no connection - and it is the honest answer rather
 * than a canned one, because it is the identical search the opponent uses on its own turn.
 *
 * An interface so the turn machine stays testable on the JVM: [GameState] must not reach for the
 * installed policy directly, or none of it could be driven from a unit test.
 */
fun interface Hinter {
    /** The best move in [fen], or null when there is no engine on the device to ask. */
    suspend fun bestMove(fen: String, rules: MatchRulesDto?): String?
}

/**
 * The real one.
 *
 * Searched a ply deeper than the opponent plays at and with the risk bonus off: a hint is advice,
 * not a personality, and nudging it toward bold moves would be answering a different question from
 * the one the player asked.
 */
object EngineHinter : Hinter {

    private const val HINT_DEPTH = 5

    override suspend fun bestMove(fen: String, rules: MatchRulesDto?): String? =
        EngineStore.withNetwork { net ->
            withContext(Dispatchers.Default) {
                val board = runCatching {
                    Board.fromFen(fen, rules = rules.toEngineRules())
                }.getOrNull() ?: return@withContext null

                val agent = LocalAgent(
                    net = net,
                    knobs = Knobs(
                        depth = HINT_DEPTH,
                        epsilon = 0f,
                        risk = 0.5f,
                        topK = 1,
                        nodeBudget = DEFAULT_NODE_BUDGET
                    )
                )

                agent.scoreMoves(board, applyRiskBonus = false)
                    .firstOrNull()
                    ?.move
                    ?.notation()
            }
        }
}
