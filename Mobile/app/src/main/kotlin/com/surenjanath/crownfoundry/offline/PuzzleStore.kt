package com.surenjanath.crownfoundry.offline

import android.content.Context
import com.surenjanath.crownfoundry.api.MatchRulesDto
import com.surenjanath.crownfoundry.engine.PuzzleSeed
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The positions you got wrong, kept so you can get them right.
 *
 * Every puzzle here came out of one of your own games: a move the engine scored as a mistake, in a
 * position you actually reached, with an answer you could have played at the time. That is the
 * whole appeal, and it is why nothing generates puzzles from a database of studies - a position
 * you have never seen teaches you about draughts, and a position you lost teaches you about you.
 *
 * Stored as JSON beside the match corpus, for the same reasons: a few dozen short strings, written
 * when a game is reviewed, and a database dependency for that would cost more than it saves.
 */

@Serializable
data class Puzzle(
    /** [fen] and [best] together - so re-reviewing a game does not collect it twice. */
    val id: String,
    val fen: String,
    val rules: MatchRulesDto? = null,
    /** The move the engine would have played. The one right answer. */
    val best: String,
    /** What was actually played, shown once the puzzle is over. */
    val played: String,
    val loss: Float = 0f,
    val quality: String = "",
    val matchId: String = "",
    val ply: Int = 0,
    val collectedAt: Long = 0,
    val solved: Boolean = false,
    /** Counts finished attempts, right or wrong, so "solved first time" is answerable. */
    val attempts: Int = 0
) {
    val isUnsolved get() = !solved

    /** Right on the first try. The only badge worth having. */
    val solvedFirstTime get() = solved && attempts <= 1

    companion object {
        fun idOf(fen: String, best: String) = "$fen|$best"
    }
}

class PuzzleStore(private val file: File) {

    constructor(context: Context) : this(
        File(File(context.filesDir, "engine").also { it.mkdirs() }, "puzzles.json")
    )

    private val mutex = Mutex()
    private var cached: List<Puzzle>? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Bounded, and it is the *solved* ones that go first when it fills.
     *
     * A puzzle you have already answered has done its job; one still waiting is the only kind
     * there is any point keeping.
     */
    private val limit = 60

    suspend fun all(): List<Puzzle> = mutex.withLock { read() }

    suspend fun unsolved(): List<Puzzle> = all().filter { it.isUnsolved }

    suspend fun find(id: String): Puzzle? = all().firstOrNull { it.id == id }

    /**
     * File away whatever a game's analysis turned up. Returns how many were genuinely new.
     *
     * Deliberately additive and idempotent: reviewing the same game twice must not double the
     * puzzle list, and must not reset the progress on a puzzle already answered.
     */
    suspend fun collect(
        seeds: List<PuzzleSeed>,
        matchId: String,
        now: Long = System.currentTimeMillis()
    ): Int = mutate { current ->
        val known = current.mapTo(HashSet()) { it.id }
        val fresh = seeds
            .map { seed ->
                Puzzle(
                    id = Puzzle.idOf(seed.fen, seed.best),
                    fen = seed.fen,
                    rules = seed.rules.toDto(),
                    best = seed.best,
                    played = seed.played,
                    loss = seed.loss,
                    quality = seed.quality.label,
                    matchId = matchId,
                    ply = seed.ply,
                    collectedAt = now
                )
            }
            .filter { known.add(it.id) }

        (current + fresh).trimmed() to fresh.size
    }

    /** Record a finished attempt. [solved] sticks: a puzzle answered stays answered. */
    suspend fun record(id: String, solved: Boolean): Puzzle? = mutate { current ->
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return@mutate current to null

        val updated = current[index].let {
            it.copy(solved = it.solved || solved, attempts = it.attempts + 1)
        }
        current.toMutableList().also { it[index] = updated } to updated
    }

    suspend fun clear() = mutate { _ -> emptyList<Puzzle>() to Unit }

    private suspend fun <T> mutate(block: (List<Puzzle>) -> Pair<List<Puzzle>, T>): T =
        mutex.withLock {
            val (next, result) = block(read())
            cached = next
            withContext(Dispatchers.IO) {
                try {
                    file.parentFile?.mkdirs()
                    file.writeText(json.encodeToString(ListSerializer, next))
                } catch (failure: Exception) {
                    // Same bargain the match corpus makes: an unwritable disk costs the player
                    // their puzzle history, not their app.
                }
            }
            result
        }

    private fun read(): List<Puzzle> = cached ?: load().also { cached = it }

    private fun load(): List<Puzzle> = try {
        if (file.exists()) json.decodeFromString(ListSerializer, file.readText()) else emptyList()
    } catch (failure: Exception) {
        emptyList()
    }

    /** Newest first, and the ones still to be answered outlive the ones already beaten. */
    private fun List<Puzzle>.trimmed(): List<Puzzle> {
        val ordered = sortedWith(
            compareByDescending<Puzzle> { it.isUnsolved }.thenByDescending { it.collectedAt }
        )
        return ordered.take(limit)
    }

    private companion object {
        val ListSerializer = kotlinx.serialization.builtins.ListSerializer(Puzzle.serializer())
    }
}
