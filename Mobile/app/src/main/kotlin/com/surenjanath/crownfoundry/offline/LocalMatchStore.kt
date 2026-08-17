package com.surenjanath.crownfoundry.offline

import android.content.Context
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.api.Side
import com.surenjanath.crownfoundry.engine.MistakeMemory
import com.surenjanath.crownfoundry.engine.OpponentProfile
import com.surenjanath.crownfoundry.engine.replayMoves
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Games played with nobody watching, and everything the device learned from them.
 *
 * A match is stored as its move list and nothing else. That is the same thing the server needs on
 * sync, the same thing the engine needs to replay a position, and the smallest thing that cannot
 * disagree with itself - a stored FEN and a stored move list can drift apart, a move list cannot
 * drift from itself.
 *
 * JSON in the app's private directory rather than a database: the whole corpus is a few hundred
 * short strings, it is written once per game, and a Room dependency for that would cost more in
 * build time than it saves in anything.
 */

@Serializable
data class LocalMatch(
    /** The id the outbox and the server's idempotency check both key on. */
    val localId: String,
    /** What [com.surenjanath.crownfoundry.api.CheckersApi] callers see; prefixed so it cannot
     *  be mistaken for a server uuid by anything that stored one. */
    val matchId: String,
    val difficulty: String = "adaptive",
    val rules: MatchRulesDto? = null,
    val moves: List<String> = emptyList(),
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val winner: String? = null,
    val resignedBy: String? = null,
    val engineVersion: Int = 0,
    /** Set once the server has confirmed the import, so the outbox stops offering it. */
    val uploaded: Boolean = false,
    /** The AI's narration, one entry per AI move, so a resumed match keeps its commentary. */
    val reasoning: List<String> = emptyList(),
    val aiCaptures: Int = 0,
    val humanCaptures: Int = 0
) {
    val isFinished get() = winner != null
    val isActive get() = winner == null
}

@Serializable
private data class StoredCorpus(
    val matches: List<LocalMatch> = emptyList(),
    /** `fen -> notations the AI has been punished for playing there`. */
    val mistakes: Map<String, List<String>> = emptyMap()
)

class LocalMatchStore(private val file: File) {

    constructor(context: Context) : this(
        File(File(context.filesDir, "engine").also { it.mkdirs() }, "matches.json")
    )

    private val mutex = Mutex()
    private var corpus: StoredCorpus? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Keep the corpus bounded. A hundred games is more history than the Matches screen shows and
     * a few tens of kilobytes on disk; past that the oldest uploaded games are dropped first.
     */
    private val matchLimit = 100
    private val mistakeLimit = 400

    // --- reading -------------------------------------------------------------------------------

    suspend fun all(): List<LocalMatch> = mutex.withLock { read().matches }

    suspend fun find(matchId: String): LocalMatch? =
        mutex.withLock { read().matches.firstOrNull { it.matchId == matchId } }

    /** Finished games the server has not confirmed yet, oldest first. */
    suspend fun pendingUploads(): List<LocalMatch> = mutex.withLock {
        read().matches.filter { it.isFinished && !it.uploaded && it.moves.isNotEmpty() }
            .sortedBy { it.startedAt }
    }

    /**
     * What the device knows about the human it keeps playing.
     *
     * The same three quantities `ai.service._update_opponent_model` keeps on the server, computed
     * from the same thing: the human's own moves. `styleAggression` is captures per own move;
     * `styleKingRush` is how hard they push for promotion.
     */
    suspend fun opponentProfile(): OpponentProfile = mutex.withLock {
        val finished = read().matches.filter { it.isFinished }
        if (finished.isEmpty()) return@withLock OpponentProfile()

        val humanWins = finished.count { it.winner == Side.HUMAN }
        var humanPlies = 0
        var captures = 0
        var crownings = 0

        for (match in finished) {
            val plies = replayMoves(match.moves, match.rules.toEngineRules())
            for (ply in plies) {
                if (sideName(ply.side) != Side.HUMAN) continue
                humanPlies++
                captures += ply.move.captures.size
                if (ply.move.crowned) crownings++
            }
        }

        OpponentProfile(
            totalGames = finished.size,
            winRate = humanWins.toFloat() / finished.size,
            styleAggression = if (humanPlies == 0) 0f
            else minOf(1f, captures.toFloat() / humanPlies),
            styleKingRush = if (humanPlies == 0) 0f
            else minOf(1f, 8f * crownings / humanPlies)
        )
    }

    /**
     * The positions and moves the agent has already been punished for.
     *
     * Read once and captured, because [MistakeMemory] is called from inside the search and cannot
     * suspend. The snapshot goes stale over the course of a game, which is exactly right: a move
     * played this game should not become a "known mistake" before the game has finished.
     */
    suspend fun mistakeMemory(): MistakeMemory {
        val snapshot = mutex.withLock { read().mistakes }
        return MistakeMemory { fen -> snapshot[fen]?.toSet() ?: emptySet() }
    }

    // --- writing -------------------------------------------------------------------------------

    suspend fun create(
        difficulty: String,
        rules: MatchRulesDto?,
        engineVersion: Int
    ): LocalMatch = mutate { current ->
        val id = UUID.randomUUID().toString()
        val match = LocalMatch(
            localId = id,
            matchId = "$OFFLINE_MATCH_PREFIX$id",
            difficulty = difficulty,
            rules = rules,
            startedAt = System.currentTimeMillis(),
            engineVersion = engineVersion
        )
        current.copy(matches = (current.matches + match).takeNewest()) to match
    }

    /** Append a played ply. Returns the updated match, or `null` if the id is unknown. */
    suspend fun appendMove(
        matchId: String,
        notation: String,
        capturedCount: Int,
        by: String,
        reasoning: String? = null
    ): LocalMatch? = mutate { current ->
        val index = current.matches.indexOfFirst { it.matchId == matchId }
        if (index < 0) return@mutate current to null

        val existing = current.matches[index]
        val updated = existing.copy(
            moves = existing.moves + notation,
            aiCaptures = existing.aiCaptures + if (by == Side.AI) capturedCount else 0,
            humanCaptures = existing.humanCaptures + if (by == Side.HUMAN) capturedCount else 0,
            reasoning = if (reasoning == null) existing.reasoning else existing.reasoning + reasoning
        )
        current.copy(matches = current.matches.replaced(index, updated)) to updated
    }

    suspend fun finish(matchId: String, winner: String, resignedBy: String? = null): LocalMatch? =
        mutate { current ->
            val index = current.matches.indexOfFirst { it.matchId == matchId }
            if (index < 0) return@mutate current to null

            val updated = current.matches[index].copy(
                winner = winner,
                resignedBy = resignedBy,
                finishedAt = System.currentTimeMillis()
            )
            current.copy(matches = current.matches.replaced(index, updated)) to updated
        }

    /** Mark games the server has confirmed. Idempotent, so a duplicated response is harmless. */
    suspend fun markUploaded(localIds: Collection<String>) {
        if (localIds.isEmpty()) return
        val ids = localIds.toSet()
        mutate { current ->
            current.copy(
                matches = current.matches.map {
                    if (it.localId in ids) it.copy(uploaded = true) else it
                }
            ) to Unit
        }
    }

    /**
     * Remember that a move earned a negative return in this position.
     *
     * Bounded: past [mistakeLimit] positions the oldest are dropped. An unbounded memory would
     * grow forever and would also make the agent more and more reluctant to play *anything*,
     * because a long enough game history eventually condemns most of the opening.
     */
    suspend fun recordMistakes(entries: List<Pair<String, String>>) {
        if (entries.isEmpty()) return
        mutate { current ->
            val merged = LinkedHashMap(current.mistakes)
            for ((fen, notation) in entries) {
                val existing = merged[fen].orEmpty()
                if (notation !in existing) merged[fen] = existing + notation
            }
            while (merged.size > mistakeLimit) {
                merged.remove(merged.keys.first())
            }
            current.copy(mistakes = merged) to Unit
        }
    }

    suspend fun clear() = mutate { _ -> StoredCorpus() to Unit }

    // --- plumbing ------------------------------------------------------------------------------

    private suspend fun <T> mutate(block: (StoredCorpus) -> Pair<StoredCorpus, T>): T =
        mutex.withLock {
            val (next, result) = block(read())
            corpus = next
            withContext(Dispatchers.IO) {
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(json.encodeToString(StoredCorpus.serializer(), next))
                } catch (failure: Exception) {
                    // The in-memory corpus is still correct for this session. Losing offline
                    // history to a full disk should not take the game down with it.
                }
            }
            result
        }

    private fun read(): StoredCorpus = corpus ?: load().also { corpus = it }

    private fun load(): StoredCorpus = try {
        if (file.exists()) json.decodeFromString(StoredCorpus.serializer(), file.readText())
        else StoredCorpus()
    } catch (failure: Exception) {
        // A corpus that will not parse is a corpus the app has to get on without. Starting empty
        // costs the player their offline history; refusing to start costs them the app.
        StoredCorpus()
    }

    /**
     * Trim to [matchLimit], dropping the most expendable games first.
     *
     * A game the server has already imported is safe to lose - it is on the server. A game still
     * in the outbox is not: dropping it throws away learning nobody else has a copy of, so those
     * go last, and among equals the oldest goes first.
     */
    private fun List<LocalMatch>.takeNewest(): List<LocalMatch> {
        if (size <= matchLimit) return this
        val expendableFirst = sortedWith(
            compareByDescending<LocalMatch> { it.isFinished && it.uploaded }
                .thenBy { it.startedAt }
        )
        return expendableFirst.drop(size - matchLimit).sortedBy { it.startedAt }
    }

    private fun <T> List<T>.replaced(index: Int, value: T): List<T> =
        toMutableList().also { it[index] = value }

    companion object {
        const val OFFLINE_MATCH_PREFIX = "offline-"

        /** Whether a match id belongs to this store rather than to the referee. */
        fun isOffline(matchId: String?) = matchId?.startsWith(OFFLINE_MATCH_PREFIX) == true
    }
}
