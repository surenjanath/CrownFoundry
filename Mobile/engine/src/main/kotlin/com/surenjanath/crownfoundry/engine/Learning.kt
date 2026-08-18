package com.surenjanath.crownfoundry.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/**
 * Learning on the device - the training half of `Backend/ai/agent.py`.
 *
 * The server learns at three cadences. The device runs the middle one: a Monte-Carlo pass over the
 * whole game once it ends, mixed with a sample of older experience so fitting one game does not
 * wash the rest out. Online per-move gradient steps are deliberately left out - they buy little
 * against a human playing at human speed and they would put a training pass between the tap and
 * the piece moving.
 *
 * This is genuinely learning, not a placeholder: the same returns, the same reward table, the same
 * loss-tail penalty as the backend. What the device cannot do is *replace* the server's policy, so
 * every game it learns from is also queued for upload. The server retrains on the real corpus and
 * the result comes back as the next version, which is what keeps one player's phone from drifting
 * into a policy nobody else's would recognise.
 */

/** One half-move of a played-out game. */
class Ply(
    @JvmField val board: Board,
    @JvmField val move: Move,
    @JvmField val after: Board,
    @JvmField val side: Int
)

class Transition(
    @JvmField val action: FloatArray,
    @JvmField val reward: Float,
    @JvmField val nextState: FloatArray?,
    @JvmField val done: Boolean,
    @JvmField val monteCarloReturn: Float,
    @JvmField val notation: String = "",
    @JvmField val fen: String = "",
    @JvmField var priority: Float = 1f
)

/**
 * How much of the terminal penalty is smeared back over the moves that produced a loss, on top of
 * the discounted return. This is `prd.md`'s "sequence that led to the loss receives a negative
 * weight penalty" made concrete.
 */
private const val LOSS_TAIL = 6
private const val LOSS_TAIL_PENALTY = 2f

const val DEFAULT_GAMMA = 0.95f

/**
 * Turn a played-out game into learning examples for [side].
 *
 * Rewards accrue to the side's own decision points: the move's own captures and crowning, plus the
 * material the opponent takes back before the side moves again. A man that crowns in the middle of
 * a jump earns both, because the engine ends the sequence there and both events happened.
 */
fun buildTransitions(
    plies: List<Ply>,
    winner: Int?,
    side: Int,
    gamma: Float = DEFAULT_GAMMA,
    lossPenalty: Boolean = true
): List<Transition> {
    val own = plies.indices.filter { plies[it].side == side }
    if (own.isEmpty()) return emptyList()

    val rewards = FloatArray(own.size)
    for (slot in own.indices) {
        val i = own[slot]
        val ply = plies[i]
        var reward = REWARD_CAPTURE * ply.move.captures.size
        if (ply.move.crowned) reward += REWARD_CROWN
        val end = if (slot + 1 < own.size) own[slot + 1] else plies.size
        for (j in i + 1 until end) reward += REWARD_PIECE_LOST * plies[j].move.captures.size
        rewards[slot] = reward
    }

    val lastIndex = rewards.size - 1
    when (winner) {
        side -> rewards[lastIndex] += REWARD_WIN
        DRAW_RESULT, null -> Unit
        else -> {
            rewards[lastIndex] += REWARD_LOSS
            if (lossPenalty) {
                // Weighting the final decisions harder is what makes the agent stop walking into
                // the same losing line; the discounted return alone spreads it too thin.
                for (k in 1..minOf(LOSS_TAIL, rewards.size)) {
                    rewards[rewards.size - k] -= LOSS_TAIL_PENALTY * (1f - (k - 1f) / LOSS_TAIL)
                }
            }
        }
    }

    val returns = FloatArray(rewards.size)
    var running = 0f
    for (i in rewards.indices.reversed()) {
        running = rewards[i] + gamma * running
        returns[i] = running
    }

    return own.indices.map { slot ->
        val ply = plies[own[slot]]
        val last = slot == own.size - 1
        Transition(
            action = encode(ply.after, side),
            reward = rewards[slot],
            nextState = if (last) null else encode(plies[own[slot + 1]].after, side),
            done = last,
            monteCarloReturn = returns[slot],
            notation = ply.move.notation(),
            fen = ply.board.toFen(),
            priority = abs(returns[slot]) + 1e-3f
        )
    }
}

/**
 * Experience the device keeps between games.
 *
 * Bounded and evicted oldest-first. 2000 transitions is about eighty games of draughts and costs
 * roughly 1.2 MB of floats, which is the right order for something that lives in an app's private
 * directory and gets rewritten after every match.
 */
class ReplayBuffer(val capacity: Int = 2000, private val random: Random = Random.Default) {
    private val items = ArrayDeque<Transition>()

    val size get() = items.size

    fun isEmpty() = items.isEmpty()

    fun push(transition: Transition) {
        items.addLast(transition)
        while (items.size > capacity) items.removeFirst()
    }

    fun extend(transitions: List<Transition>) = transitions.forEach(::push)

    fun clear() = items.clear()

    /**
     * A batch, optionally weighted toward the transitions the network is worst at.
     *
     * Prioritised sampling here is the cheap kind: one weighted draw per slot, with replacement.
     * A sum-tree would be exact and would also be the most complicated thing in this module, for
     * a buffer of two thousand.
     */
    fun sample(batchSize: Int, prioritized: Boolean = true): List<Transition> {
        if (items.isEmpty() || batchSize <= 0) return emptyList()
        val pool = items.toList()
        if (pool.size <= batchSize) return pool

        if (!prioritized) return List(batchSize) { pool[random.nextInt(pool.size)] }

        var total = 0.0
        for (item in pool) total += item.priority.toDouble()
        if (total <= 0.0) return List(batchSize) { pool[random.nextInt(pool.size)] }

        return List(batchSize) {
            var target = random.nextDouble() * total
            var chosen = pool[pool.size - 1]
            for (item in pool) {
                target -= item.priority.toDouble()
                if (target <= 0.0) {
                    chosen = item
                    break
                }
            }
            chosen
        }
    }

    /** Serialise for the app's private storage. Only the fields learning actually reads. */
    fun toBytes(): ByteArray {
        val pool = items.toList()
        val perItem = 4 + FEATURE_SIZE * 4 + 4 + 1 + 1 + FEATURE_SIZE * 4
        val buffer = ByteBuffer.allocate(8 + pool.size * perItem).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(REPLAY_FORMAT)
        buffer.putInt(pool.size)
        for (item in pool) {
            buffer.putFloat(item.reward)
            for (value in item.action) buffer.putFloat(value)
            buffer.putFloat(item.monteCarloReturn)
            buffer.put(if (item.done) 1 else 0)
            buffer.put(if (item.nextState != null) 1 else 0)
            val next = item.nextState
            if (next != null) for (value in next) buffer.putFloat(value)
            else for (i in 0 until FEATURE_SIZE) buffer.putFloat(0f)
        }
        return buffer.array()
    }

    companion object {
        private const val REPLAY_FORMAT = 1

        /** Restore a buffer, or an empty one for anything unreadable. Cached experience is not
         *  worth crashing over - the device just relearns it from the games it plays next. */
        fun fromBytes(bytes: ByteArray?, capacity: Int = 2000): ReplayBuffer {
            val buffer = ReplayBuffer(capacity)
            if (bytes == null || bytes.size < 8) return buffer
            return try {
                val reader = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if (reader.int != REPLAY_FORMAT) return buffer
                val count = reader.int
                repeat(minOf(count, capacity)) {
                    val reward = reader.float
                    val action = FloatArray(FEATURE_SIZE) { reader.float }
                    val monteCarloReturn = reader.float
                    val done = reader.get() != 0.toByte()
                    val hasNext = reader.get() != 0.toByte()
                    val next = FloatArray(FEATURE_SIZE) { reader.float }
                    buffer.push(
                        Transition(
                            action = action,
                            reward = reward,
                            nextState = if (hasNext) next else null,
                            done = done,
                            monteCarloReturn = monteCarloReturn,
                            priority = abs(monteCarloReturn) + 1e-3f
                        )
                    )
                }
                buffer
            } catch (failure: Exception) {
                ReplayBuffer(capacity)
            }
        }
    }
}

data class TrainingReport(
    val transitions: Int,
    val loss: Float,
    val durationMs: Long,
    val replaySize: Int
)

/**
 * Fit [net] to one finished game.
 *
 * Two passes, matching the backend: the game's own Monte-Carlo returns first, then one bootstrapped
 * step over a sample of older experience so the policy keeps what it already knew.
 */
class OfflineLearner(
    private val net: QNetwork,
    val replay: ReplayBuffer = ReplayBuffer(),
    private val gamma: Float = DEFAULT_GAMMA,
    private val random: Random = Random.Default
) {

    fun learnFromMatch(
        plies: List<Ply>,
        winner: Int?,
        aiSide: Int,
        epochs: Int = 3,
        batchSize: Int = 32
    ): TrainingReport {
        val started = System.nanoTime()
        val transitions = buildTransitions(plies, winner, aiSide, gamma)
        if (transitions.isEmpty()) {
            return TrainingReport(0, 0f, 0, replay.size)
        }

        replay.extend(transitions)
        var loss = trainOn(transitions, epochs, batchSize)

        val replayed = replay.sample(batchSize, prioritized = true)
        if (replayed.size >= 8) {
            loss = 0.5f * (loss + trainBootstrapped(replayed))
        }

        return TrainingReport(
            transitions = transitions.size,
            loss = loss,
            durationMs = (System.nanoTime() - started) / 1_000_000,
            replaySize = replay.size
        )
    }

    /** Batch-fit against precomputed Monte-Carlo returns. Returns the mean loss. */
    fun trainOn(transitions: List<Transition>, epochs: Int = 1, batchSize: Int = 32): Float {
        if (transitions.isEmpty()) return 0f

        val inputs = transitions.map { it.action }
        val targets = FloatArray(transitions.size) {
            transitions[it].monteCarloReturn.coerceIn(-TERMINAL_VALUE, TERMINAL_VALUE)
        }

        var total = 0f
        var batches = 0
        repeat(maxOf(1, epochs)) {
            val order = transitions.indices.shuffled(random)
            var start = 0
            while (start < order.size) {
                val slice = order.subList(start, minOf(start + batchSize, order.size))
                total += net.trainBatch(slice.map { inputs[it] }, FloatArray(slice.size) { targets[slice[it]] })
                batches++
                start += batchSize
            }
        }
        return if (batches == 0) 0f else total / batches
    }

    /**
     * One TD step over replayed experience. `nextState` already holds the *greedy* afterstate
     * picked when the transition was recorded, so evaluating it is the max over next actions.
     */
    private fun trainBootstrapped(batch: List<Transition>): Float {
        val targets = FloatArray(batch.size) { i ->
            val transition = batch[i]
            var target = transition.reward
            val next = transition.nextState
            if (next != null && !transition.done) target += gamma * net.predict(next)
            target.coerceIn(-TERMINAL_VALUE, TERMINAL_VALUE)
        }
        // Refresh priorities from what the network gets wrong now, not from what it got wrong
        // when the transition was recorded.
        for (i in batch.indices) {
            batch[i].priority = abs(targets[i] - net.predict(batch[i].action)) + 1e-3f
        }
        return net.trainBatch(batch.map { it.action }, targets)
    }
}

/**
 * Rebuild the plies of a game from its move list. Empty for anything that does not replay.
 *
 * [from] is the position the move list starts at, which is the opening for every game this app
 * plays. Review takes the stored one instead of assuming: a game set up from a position would
 * otherwise replay against the wrong board and report every move as a blunder.
 */
fun replayMoves(
    moves: List<String>,
    rules: VariantRules = VariantRules.DEFAULT,
    from: Board = Board.initial(rules)
): List<Ply> {
    var board = from
    val plies = ArrayList<Ply>(moves.size)
    for (notation in moves) {
        val move = try {
            board.parseMove(notation)
        } catch (failure: IllegalArgumentException) {
            return plies
        }
        val after = board.apply(move)
        plies.add(Ply(board, move, after, board.sideToMove))
        board = after
    }
    return plies
}
