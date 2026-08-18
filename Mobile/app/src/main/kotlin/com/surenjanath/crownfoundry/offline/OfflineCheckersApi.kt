package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.AiStatusDto
import com.surenjanath.crownfoundry.api.AiTurnDto
import com.surenjanath.crownfoundry.api.AnalyticsSummaryDto
import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.AppliedMoveDto
import com.surenjanath.crownfoundry.api.CapturePointDto
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.EvaluationDto
import com.surenjanath.crownfoundry.api.GameLengthPointDto
import com.surenjanath.crownfoundry.api.HealthDto
import com.surenjanath.crownfoundry.api.HistoryEntryDto
import com.surenjanath.crownfoundry.api.MatchDto
import com.surenjanath.crownfoundry.api.MatchListDto
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.MatchSummaryDto
import com.surenjanath.crownfoundry.api.MoveResultDto
import com.surenjanath.crownfoundry.api.OllamaStatusDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.api.PerformanceDto
import com.surenjanath.crownfoundry.api.ResignDto
import com.surenjanath.crownfoundry.api.ScoredMoveDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.api.WinRatePointDto
import com.surenjanath.crownfoundry.engine.AmbiguousMove
import com.surenjanath.crownfoundry.engine.Board
import com.surenjanath.crownfoundry.engine.DEFAULT_NODE_BUDGET
import com.surenjanath.crownfoundry.engine.DRAW_RESULT
import com.surenjanath.crownfoundry.engine.IllegalMove
import com.surenjanath.crownfoundry.engine.LocalAgent
import com.surenjanath.crownfoundry.engine.Move
import com.surenjanath.crownfoundry.engine.Narrator
import com.surenjanath.crownfoundry.engine.OfflineLearner
import com.surenjanath.crownfoundry.engine.buildTransitions
import com.surenjanath.crownfoundry.engine.confidenceOf
import com.surenjanath.crownfoundry.engine.knobsFor
import com.surenjanath.crownfoundry.engine.replayMoves
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The referee, on the device.
 *
 * This is the piece that makes offline mode *the same app* rather than a reduced one: it answers
 * the exact interface [com.surenjanath.crownfoundry.api.CrownFoundryClient] answers, so the board,
 * the turn machine, the animations and the match history all work against it without knowing they
 * are not talking to Django. Nothing in `ui/` needed changing to gain an offline game.
 *
 * Two rules carry over from the server, and they are the reason this is a referee and not a
 * simulation:
 *
 * * the engine is the only authority - a move is applied because the generator produced it, never
 *   because the caller asked nicely, and an illegal move comes back with the legal ones attached;
 * * the AI's move goes back through the generator before it is played, exactly as `game.views`
 *   does, so a bug in the agent surfaces as a refusal rather than as an impossible position.
 *
 * What is genuinely different offline is the narration. There is no Ollama, so the reasoning comes
 * from [Narrator] - the same heuristic the backend falls back to when Ollama is not installed.
 */
/** What a pass-and-play match records instead of a difficulty. There is no engine to set one. */
const val PASS_AND_PLAY_DIFFICULTY = "pass"

class OfflineCheckersApi(
    private val store: LocalMatchStore,
    private val engine: EngineStore = EngineStore,
    private val preferences: EnginePreferences,
    private val searchDepth: Int = 4,
    private val nodeBudget: Int = DEFAULT_NODE_BUDGET
) : CheckersApi {

    override suspend fun health(): Outcome<HealthDto> = Outcome.Success(
        HealthDto(
            ok = engine.state.canPlayOffline,
            version = "offline",
            ollama = OllamaStatusDto(available = false, model = ""),
            policyVersion = engine.state.header?.serverVersion ?: 0
        )
    )

    override suspend fun startMatch(
        difficulty: String,
        playerId: String?,
        rules: MatchRulesDto?
    ): Outcome<MatchDto> {
        missingEngine()?.let { return Outcome.Failure(it) }

        val match = store.create(
            difficulty = difficulty,
            rules = rules,
            engineVersion = engine.state.header?.serverVersion ?: 0
        )
        return Outcome.Success(envelope(match, boardOf(match)).copy(
            initialBoard = Board.initial(rules.toEngineRules()).toFen()
        ))
    }

    /**
     * Start a game for two people sharing this phone.
     *
     * No engine check, unlike [startMatch]: nothing here needs a policy. A player who has never
     * been online can still hand the phone across the table, which is the whole point of having
     * the rules on the device.
     */
    suspend fun startPassAndPlay(rules: MatchRulesDto?): Outcome<MatchDto> {
        val match = store.create(
            difficulty = PASS_AND_PLAY_DIFFICULTY,
            rules = rules,
            engineVersion = engine.state.header?.serverVersion ?: 0,
            mode = LocalMatch.MODE_PASS
        )
        return Outcome.Success(
            envelope(match, boardOf(match)).copy(
                initialBoard = Board.initial(rules.toEngineRules()).toFen()
            )
        )
    }

    override suspend fun match(matchId: String): Outcome<MatchDto> {
        val match = store.find(matchId) ?: return notFound(matchId)
        return Outcome.Success(envelope(match, boardOf(match), withHistory = true))
    }

    override suspend fun matches(playerId: String?, limit: Int): Outcome<MatchListDto> {
        val summaries = store.all()
            .sortedByDescending { it.startedAt }
            .take(limit.coerceIn(1, 200))
            .map { match ->
                MatchSummaryDto(
                    matchId = match.matchId,
                    startTime = iso(match.startedAt),
                    endTime = match.finishedAt?.let(::iso),
                    status = if (match.isFinished) "finished" else "active",
                    winner = match.winner,
                    totalTurns = match.moves.size,
                    difficulty = match.difficulty,
                    aiCaptures = match.aiCaptures,
                    humanCaptures = match.humanCaptures
                )
            }
        return Outcome.Success(MatchListDto(ok = true, matches = summaries))
    }

    override suspend fun playMove(matchId: String, move: String): Outcome<MoveResultDto> =
        submit(matchId) { board -> board.parseMove(move) }

    override suspend fun playMove(matchId: String, from: Int, to: Int): Outcome<MoveResultDto> =
        submit(matchId) { board -> board.resolve(from, to) }

    private suspend fun submit(
        matchId: String,
        select: (Board) -> Move
    ): Outcome<MoveResultDto> {
        val match = store.find(matchId) ?: return notFound(matchId)
        if (match.isFinished) {
            return Outcome.Failure(
                ApiError.Rejected(400, "match_finished", "This match is already finished.")
            )
        }

        val board = boardOf(match)
        // Pass-and-play has no side of its own to defend: whoever the rules say is to move is the
        // person holding the phone, so the referee only has to check that the move is legal.
        val mover = sideName(board.sideToMove)
        if (!match.isPassAndPlay && mover != Side.HUMAN) {
            return Outcome.Failure(
                ApiError.Rejected(
                    409, "not_your_turn",
                    "It is $mover's turn, not ${Side.HUMAN}'s."
                )
            )
        }

        val move = try {
            select(board)
        } catch (failure: AmbiguousMove) {
            return Outcome.Failure(
                ApiError.Rejected(400, "ambiguous_move", failure.message.orEmpty(), board.legalMoves().toDtos())
            )
        } catch (failure: IllegalMove) {
            // Same contract as the server: the board takes the referee's word for what is legal.
            return Outcome.Failure(ApiError.IllegalMove(board.legalMoves().toDtos()))
        }

        val after = board.apply(move)
        store.appendMove(matchId, move.notation(), move.captures.size, mover)
        val winner = settle(matchId, after)

        return Outcome.Success(
            MoveResultDto(
                ok = true,
                valid = true,
                gameOver = winner != null,
                winner = winner,
                boardState = after.toFen(),
                board = after.toDto(),
                legalMoves = if (winner != null) emptyList() else after.legalMoves().toDtos(),
                appliedMove = AppliedMoveDto(
                    notation = move.notation(),
                    captures = move.captures.toList(),
                    crowned = move.crowned
                ),
                turnNumber = match.moves.size + 1
            )
        )
    }

    override suspend fun generateAiTurn(matchId: String): Outcome<AiTurnDto> {
        val match = store.find(matchId) ?: return notFound(matchId)
        if (match.isFinished) {
            return Outcome.Failure(
                ApiError.Rejected(400, "match_finished", "This match is already finished.")
            )
        }

        if (match.isPassAndPlay) {
            return Outcome.Failure(
                ApiError.Rejected(
                    400, "no_opponent",
                    "This is a pass-and-play match. Both sides are played by hand."
                )
            )
        }

        val board = boardOf(match)
        if (sideName(board.sideToMove) != Side.AI) {
            return Outcome.Failure(
                ApiError.Rejected(400, "not_your_turn", "It is not the opponent's turn.")
            )
        }

        val profile = store.opponentProfile()
        val memory = store.mistakeMemory()

        // The search is a few hundred thousand multiply-adds; it does not belong on the frame
        // thread even when it finishes in fifty milliseconds.
        val chosen = engine.withNetwork { net ->
            withContext(Dispatchers.Default) {
                val agent = LocalAgent(
                    net = net,
                    knobs = knobsFor(match.difficulty, profile, searchDepth, nodeBudget),
                    memory = memory
                )
                val (move, considered) = agent.select(board, explore = true)
                move to considered
            }
        } ?: return Outcome.Failure(
            ApiError.BrainUnavailable(
                "No AI engine is installed on this device yet. Connect to the referee to download one."
            )
        )

        val (proposed, considered) = chosen

        // Put whatever the agent handed back through the generator before trusting it - the same
        // belt-and-braces `game.views.ai_move` applies to the server's own agent.
        val move = try {
            board.parseMove(proposed.notation())
        } catch (failure: IllegalMove) {
            return Outcome.Failure(
                ApiError.BrainUnavailable(
                    "The on-device engine proposed ${proposed.notation()}, which is not legal here."
                )
            )
        }

        val reasoning = Narrator.explain(board, move)
        val after = board.apply(move)
        store.appendMove(matchId, move.notation(), move.captures.size, Side.AI, reasoning)
        val winner = settle(matchId, after)

        val notation = move.notation()
        return Outcome.Success(
            AiTurnDto(
                ok = true,
                aiMove = notation,
                aiReasoning = reasoning,
                reasoningSource = Narrator.SOURCE_LOCAL,
                newBoard = after.toFen(),
                board = after.toDto(),
                legalMoves = if (winner != null) emptyList() else after.legalMoves().toDtos(),
                evaluation = EvaluationDto(
                    qValue = considered.firstOrNull { it.notation == notation }?.q?.toDouble()
                        ?: considered.firstOrNull()?.q?.toDouble() ?: 0.0,
                    confidence = confidenceOf(considered).toDouble(),
                    considered = considered.map { ScoredMoveDto(it.notation, it.q.toDouble()) }
                ),
                gameOver = winner != null,
                winner = winner,
                turnNumber = match.moves.size + 1,
                captures = move.captures.toList(),
                crowned = move.crowned
            )
        )
    }

    override suspend fun resign(matchId: String): Outcome<ResignDto> {
        val match = store.find(matchId) ?: return notFound(matchId)
        if (match.isFinished) {
            return Outcome.Success(ResignDto(ok = true, gameOver = true, winner = match.winner))
        }

        // The human is Black; resigning hands the game to White. In pass-and-play it is whoever
        // has the move who is giving up, so the win goes to the other chair.
        val quitter = if (match.isPassAndPlay) {
            sideName(boardOf(match).sideToMove)
        } else {
            Side.HUMAN
        }
        val winner = if (quitter == Side.AI) Side.HUMAN else Side.AI

        store.finish(matchId, winner, resignedBy = quitter)
        learnFrom(matchId)
        return Outcome.Success(ResignDto(ok = true, gameOver = true, winner = winner))
    }

    // --- analytics ---------------------------------------------------------------------------

    override suspend fun performance(): Outcome<PerformanceDto> {
        // The engine's record, so only games the engine was in.
        val finished = store.all()
            .filter { it.isFinished && !it.isPassAndPlay }
            .sortedBy { it.startedAt }
        var aiWins = 0

        val winRate = finished.mapIndexed { index, match ->
            if (match.winner == Side.AI) aiWins++
            val window = finished.subList(maxOf(0, index - 9), index + 1)
            WinRatePointDto(
                matchIndex = index + 1,
                cumulativeWinRate = aiWins.toDouble() / (index + 1),
                rollingWinRate = window.count { it.winner == Side.AI }.toDouble() / window.size,
                result = match.winner.orEmpty()
            )
        }

        return Outcome.Success(
            PerformanceDto(
                ok = true,
                summary = summaryOf(finished),
                winRateSeries = winRate,
                gameLengthSeries = finished.mapIndexed { index, match ->
                    GameLengthPointDto(matchIndex = index + 1, turns = match.moves.size)
                },
                // Repeat-mistake and loss curves are the server's to compute: they need the whole
                // corpus and the training log, and reporting a device-only slice of them beside
                // server numbers would be worse than reporting nothing.
                mistakeSeries = emptyList(),
                captureSeries = finished.mapIndexed { index, match ->
                    CapturePointDto(
                        matchIndex = index + 1,
                        aiCaptures = match.aiCaptures,
                        humanCaptures = match.humanCaptures
                    )
                },
                training = emptyList()
            )
        )
    }

    override suspend fun summary(): Outcome<AnalyticsSummaryDto> =
        Outcome.Success(summaryOf(store.all().filter { it.isFinished && !it.isPassAndPlay }))

    private fun summaryOf(finished: List<LocalMatch>): AnalyticsSummaryDto {
        val aiWins = finished.count { it.winner == Side.AI }
        val humanWins = finished.count { it.winner == Side.HUMAN }
        val draws = finished.count { it.winner == Side.DRAW }
        val aiCaptures = finished.sumOf { it.aiCaptures }
        val humanCaptures = finished.sumOf { it.humanCaptures }

        return AnalyticsSummaryDto(
            totalMatches = finished.size,
            aiWins = aiWins,
            humanWins = humanWins,
            draws = draws,
            aiWinRate = if (finished.isEmpty()) 0.0 else aiWins.toDouble() / finished.size,
            elo = engine.state.header?.elo ?: 1200,
            policyVersion = engine.state.header?.serverVersion ?: 0,
            avgTurns = if (finished.isEmpty()) 0.0
            else finished.sumOf { it.moves.size }.toDouble() / finished.size,
            captureRatio = if (humanCaptures == 0) aiCaptures.toDouble()
            else aiCaptures.toDouble() / humanCaptures
        )
    }

    // --- internals ---------------------------------------------------------------------------

    /** Rebuild the live position from the move list. Cheap, and it cannot disagree with itself. */
    private fun boardOf(match: LocalMatch): Board {
        var board = Board.initial(match.rules.toEngineRules())
        for (notation in match.moves) {
            board = board.apply(
                try {
                    board.parseMove(notation)
                } catch (failure: IllegalMove) {
                    // Only reachable if the stored corpus was edited underneath us; the position
                    // as far as it replayed is still the truthful one to hand back.
                    return board
                }
            )
        }
        return board
    }

    /** Close the match out if the position is decided, and learn from it. Returns the winner. */
    private suspend fun settle(matchId: String, board: Board): String? {
        val winner = winnerName(board.winner()) ?: return null
        store.finish(matchId, winner)
        learnFrom(matchId)
        return winner
    }

    /**
     * Fine-tune the local weights on a finished game, and remember what not to repeat.
     *
     * Runs inline rather than in the background. It costs tens of milliseconds on the game the
     * device just finished, and it happens on the "game over" dialog rather than mid-turn - so the
     * next game genuinely faces a policy that saw the last one, which is the entire promise of
     * "it learns offline too".
     */
    private suspend fun learnFrom(matchId: String) {
        if (!preferences.learnOnDevice) return
        val match = store.find(matchId) ?: return
        // Nothing to learn from a game the agent did not play. Training on White's moves here
        // would teach the policy to imitate whoever borrowed the phone.
        if (match.isPassAndPlay) return
        val aiSide = sideCode(Side.AI) ?: return

        val plies = replayMoves(match.moves, match.rules.toEngineRules())
        if (plies.isEmpty()) return
        val winner = when (match.winner) {
            Side.DRAW -> DRAW_RESULT
            null -> null
            else -> sideCode(match.winner)
        }

        val transitions = buildTransitions(plies, winner, aiSide)
        // A move that earned a negative return in a position is one to think twice about next
        // time it comes up - the same signal `AIMoveMemory.is_known_mistake` carries on the server.
        store.recordMistakes(
            transitions.filter { it.monteCarloReturn < 0f }.map { it.fen to it.notation }
        )

        val report = engine.withLearning { net, replay ->
            withContext(Dispatchers.Default) {
                OfflineLearner(net, replay).learnFromMatch(plies, winner, aiSide)
            }
        } ?: return

        if (report.transitions > 0) {
            engine.persistLocalTraining(gamesLearned = 1, loss = report.loss, preferences = preferences)
        }
    }

    private fun envelope(
        match: LocalMatch,
        board: Board,
        withHistory: Boolean = false
    ) = MatchDto(
        ok = true,
        matchId = match.matchId,
        board = board.toDto(),
        legalMoves = if (match.isFinished) emptyList() else board.legalMoves().toDtos(),
        turnNumber = match.moves.size,
        status = if (match.isFinished) "finished" else "active",
        winner = match.winner,
        difficulty = match.difficulty,
        rules = match.rules ?: board.rules.toDto(),
        history = if (withHistory) historyOf(match) else emptyList(),
        ai = AiStatusDto(
            policyVersion = engine.state.header?.serverVersion ?: 0,
            gamesTrained = engine.state.header?.gamesTrained ?: 0,
            winRate = 0.0,
            elo = engine.state.header?.elo ?: 1200
        )
    )

    private fun historyOf(match: LocalMatch): List<HistoryEntryDto> {
        var board = Board.initial(match.rules.toEngineRules())
        var aiMoveIndex = 0
        return match.moves.mapIndexedNotNull { index, notation ->
            val move = try {
                board.parseMove(notation)
            } catch (failure: IllegalMove) {
                return@mapIndexedNotNull null
            }
            val side = sideName(board.sideToMove)
            board = board.apply(move)
            val reasoning = if (side == Side.AI) match.reasoning.getOrNull(aiMoveIndex++) else null
            HistoryEntryDto(
                turn = index + 1,
                side = side,
                move = notation,
                fen = board.toFen(),
                reasoning = reasoning
            )
        }
    }

    private fun <T> notFound(matchId: String): Outcome<T> = Outcome.Failure(
        ApiError.Rejected(404, "match_not_found", "No offline match with id $matchId.")
    )

    /** `null` when an engine is installed; the reason to show the player when there is not. */
    private fun missingEngine(): ApiError? =
        if (engine.state.canPlayOffline) null
        else ApiError.BrainUnavailable(
            when (engine.state.status) {
                EngineStatus.Incompatible ->
                    "The installed AI engine needs updating before it can be used offline."

                else ->
                    "No AI engine is installed yet. Connect to the referee once to download it."
            }
        )

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
