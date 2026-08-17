package com.surenjanath.crownfoundry.engine

/**
 * The AI's voice when there is nobody to ask - the Kotlin half of `heuristic_reason` in
 * `Backend/ai/ollama.py`.
 *
 * Online, an Ollama model turns the search's shortlist into a sentence. Offline there is no model
 * and no server, so the narrator does what the backend's fallback does: it reads what the move
 * actually did to the position and says something true about it. Not as fluent as an LLM, and
 * never wrong, which is the trade the player is better served by when the alternative is silence.
 */

private val CENTRE_SQUARES = (1..SQUARE_COUNT).filter { rowOf(it) in 2..5 && colOf(it) in 2..5 }.toSet()

private val EDGE_SQUARES = (1..SQUARE_COUNT)
    .filter { rowOf(it) == 0 || rowOf(it) == 7 || colOf(it) == 0 || colOf(it) == 7 }
    .toSet()

/** Squares 1..4 / 29..32 hold the double corner and the crowning row a side must defend. */
private val BACK_RANK = mapOf(BLACK to setOf(1, 2, 3, 4), WHITE to setOf(29, 30, 31, 32))

object Narrator {

    const val SOURCE_LOCAL = "local"

    /** Compose a sentence out of what [move] actually does to [board]. */
    fun explain(board: Board, move: Move): String {
        val side = board.sideToMove
        val clauses = ArrayList<String>(4)

        if (move.captures.isNotEmpty()) {
            val n = move.captures.size
            clauses.add(
                if (n == 1) "taking a piece"
                else "running a $n-piece jump through ${move.notation()}"
            )
        }
        if (move.crowned) clauses.add("crowning on ${move.destination}")

        val after = try {
            board.apply(move)
        } catch (failure: IllegalMove) {
            null
        }

        var exposes = false
        var threatens = false
        if (after != null) {
            exposes = after.hasJump()
            if (!exposes) {
                // Look one further ply: does this move set up a capture for us next turn?
                for (reply in after.legalMoves().take(8)) {
                    if (after.apply(reply).hasJump()) {
                        threatens = true
                        break
                    }
                }
            }
        }

        if (move.captures.isEmpty() && move.destination in CENTRE_SQUARES &&
            move.origin !in CENTRE_SQUARES
        ) {
            clauses.add("stepping into the centre where I keep more options")
        } else if (move.captures.isEmpty() && move.destination in EDGE_SQUARES) {
            clauses.add("hugging the edge, where the piece cannot be jumped")
        }

        if (move.origin in BACK_RANK.getValue(side)) {
            clauses.add("though it gives up a back-rank guard")
        }

        if (threatens) clauses.add("and it sets up a capture next turn")
        if (exposes && move.captures.isNotEmpty()) clauses.add("accepting the trade that comes back")
        else if (exposes) clauses.add("even though it offers a trade")

        if (clauses.isEmpty()) {
            clauses.add(
                "advancing ${move.origin} to ${move.destination} to keep the position tidy"
            )
        }

        val body = clauses.first() + if (clauses.size > 1) {
            ", " + clauses.drop(1).joinToString(", ")
        } else ""

        val sentence = "Playing ${move.notation()}: $body"
        return if (sentence.endsWith(".")) sentence else "$sentence."
    }
}
