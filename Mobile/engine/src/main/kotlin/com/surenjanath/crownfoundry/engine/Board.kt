package com.surenjanath.crownfoundry.engine

/**
 * The rules of English draughts, as an immutable board - the Kotlin half of
 * `Backend/game/engine/{moves,board}.py`.
 *
 * The one deliberate departure from the Python is the representation: squares live in a flat
 * `IntArray(33)` of piece codes rather than a map. The search runs this generator on a phone
 * tens of thousands of times per move, and an `IntArray` copy is an intrinsic while a `HashMap`
 * copy is a few hundred allocations. Every rule, every ordering decision and every edge case is
 * otherwise a line-for-line port.
 */

const val EMPTY = 0
const val BLACK_MAN = 1
const val BLACK_KING = 2
const val WHITE_MAN = 3
const val WHITE_KING = 4

fun sideOfPiece(code: Int) = if (code <= BLACK_KING) BLACK else WHITE
fun isKing(code: Int) = code == BLACK_KING || code == WHITE_KING
fun kingOf(side: Int) = if (side == BLACK) BLACK_KING else WHITE_KING
fun manOf(side: Int) = if (side == BLACK) BLACK_MAN else WHITE_MAN

/** Plies without a capture or a promotion before the game is declared drawn. */
const val NO_PROGRESS_PLIES = 40

/** How many times a position may repeat before the game is declared drawn. */
const val REPETITION_LIMIT = 3

class IllegalMove(message: String) : IllegalArgumentException(message)

/** Raised when an origin/destination pair matches more than one legal jump path. */
class AmbiguousMove(message: String) : IllegalArgumentException(message)

data class VariantRules(
    val flyingKings: Boolean = true,
    val menCaptureBackwards: Boolean = true,
    val mandatoryCapture: Boolean = true
) {
    companion object {
        val DEFAULT = VariantRules()

        /** Strict English draughts: kings step one square, men only capture forward. */
        val ENGLISH = VariantRules(
            flyingKings = false, menCaptureBackwards = false, mandatoryCapture = true
        )
    }
}

class Move(
    @JvmField val origin: Int,
    @JvmField val destination: Int,
    @JvmField val path: IntArray,
    @JvmField val captures: IntArray,
    @JvmField val crowned: Boolean
) {
    val isJump get() = captures.isNotEmpty()

    /** Every square the mover touches, origin first. */
    fun squares(): IntArray = IntArray(path.size + 1).also {
        it[0] = origin
        path.copyInto(it, 1)
    }

    fun notation(): String = formatMoveString(origin, path, captures.isNotEmpty())

    override fun toString() = notation()

    /**
     * Two moves are the same move when they touch the same squares in the same order. That is
     * exactly the identity `Board.parseMove` resolves against, so a move round-tripped through
     * its notation compares equal to the one the generator produced.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Move) return false
        return origin == other.origin && path.contentEquals(other.path) &&
                captures.contentEquals(other.captures)
    }

    override fun hashCode(): Int = 31 * (31 * origin + path.contentHashCode()) +
            captures.contentHashCode()
}

// --- Zobrist ------------------------------------------------------------------------------------

/**
 * Position hashes, for repetition detection.
 *
 * These are the device's own keys and never cross the wire - offline games sync as move lists,
 * which the server replays under its own hashes - so the only requirement is that they are stable
 * across processes on this device. SplitMix64 from a fixed seed gives that without pulling in a
 * seeded RNG whose stream could change under a platform update.
 */
private object Zobrist {
    val keys: Array<LongArray>
    val sideKey: Long

    init {
        var state = 0x43524F574EL
        fun next(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        keys = Array(SQUARE_COUNT + 1) { LongArray(5) { 0L } }
        for (square in 1..SQUARE_COUNT) {
            for (code in BLACK_MAN..WHITE_KING) keys[square][code] = next()
        }
        sideKey = next()
    }
}

// --- the board ----------------------------------------------------------------------------------

/**
 * A position. Every mutator returns a new instance.
 *
 * [history] holds the position hashes seen since the last irreversible move, current position
 * included. Captures and promotions can never be undone, so nothing from before one can ever
 * repeat and the window stays bounded by [NO_PROGRESS_PLIES].
 */
class Board internal constructor(
    @JvmField internal val codes: IntArray,
    @JvmField val sideToMove: Int,
    @JvmField val pliesSinceProgress: Int,
    @JvmField val positionHash: Long,
    @JvmField val history: LongArray,
    @JvmField val rules: VariantRules
) {

    companion object {
        fun initial(rules: VariantRules = VariantRules.DEFAULT): Board {
            val codes = IntArray(SQUARE_COUNT + 1)
            for (square in 1..12) codes[square] = BLACK_MAN
            for (square in 21..32) codes[square] = WHITE_MAN
            return of(codes, BLACK, 0, null, rules)
        }

        fun fromFen(
            fen: String,
            pliesSinceProgress: Int = 0,
            history: LongArray? = null,
            rules: VariantRules = VariantRules.DEFAULT
        ): Board {
            val (side, codes) = splitFen(fen)
            return of(codes, side, pliesSinceProgress, history, rules)
        }

        internal fun of(
            codes: IntArray,
            sideToMove: Int,
            pliesSinceProgress: Int,
            history: LongArray?,
            rules: VariantRules
        ): Board {
            val hash = hashPosition(codes, sideToMove)
            return Board(
                codes = codes,
                sideToMove = sideToMove,
                pliesSinceProgress = pliesSinceProgress,
                positionHash = hash,
                history = history ?: longArrayOf(hash),
                rules = rules
            )
        }

        private fun hashPosition(codes: IntArray, sideToMove: Int): Long {
            var value = if (sideToMove == WHITE) Zobrist.sideKey else 0L
            for (square in 1..SQUARE_COUNT) {
                val code = codes[square]
                if (code != EMPTY) value = value xor Zobrist.keys[square][code]
            }
            return value
        }
    }

    /** The piece code on [square], or [EMPTY]. Squares are `1..32`; anything else is empty. */
    fun pieceAt(square: Int): Int =
        if (square in 1..SQUARE_COUNT) codes[square] else EMPTY

    fun isEmpty(square: Int) = codes[square] == EMPTY

    fun toFen(): String = joinFen(sideToMove, codes)

    // --- move generation --------------------------------------------------------------------

    fun legalMoves(): List<Move> = generateMoves(codes, sideToMove, rules)

    /** True when [legalMoves] would contain at least one jump. Cheaper than building the list. */
    fun hasJump(): Boolean {
        for (square in 1..SQUARE_COUNT) {
            val code = codes[square]
            if (code == EMPTY || sideOfPiece(code) != sideToMove) continue
            if (canJumpFrom(codes, square, code, sideToMove, rules)) return true
        }
        return false
    }

    /**
     * Play [move] and return the resulting position with the side flipped.
     *
     * The move is trusted: it is expected to have come from [legalMoves], [parseMove] or
     * [resolve], all of which validate. Re-validating here would double the cost of the engine's
     * hottest path for no benefit.
     */
    fun apply(move: Move): Board {
        val next = codes.copyOf()
        var piece = next[move.origin]
        if (piece == EMPTY) throw IllegalMove("no piece on square ${move.origin}")
        next[move.origin] = EMPTY

        var value = positionHash xor Zobrist.keys[move.origin][piece] xor Zobrist.sideKey
        for (square in move.captures) {
            val captured = next[square]
            if (captured == EMPTY) throw IllegalMove("nothing to capture on square $square")
            next[square] = EMPTY
            value = value xor Zobrist.keys[square][captured]
        }

        if (move.crowned) piece = kingOf(sideOfPiece(piece))
        next[move.destination] = piece
        value = value xor Zobrist.keys[move.destination][piece]

        val progress = move.captures.isNotEmpty() || move.crowned
        val plies = if (progress) 0 else pliesSinceProgress + 1
        val nextHistory =
            if (progress) longArrayOf(value)
            else history.copyOf(history.size + 1).also { it[history.size] = value }

        return Board(next, opponent(sideToMove), plies, value, nextHistory, rules)
    }

    // --- move lookup ------------------------------------------------------------------------

    fun parseMove(text: String?): Move {
        val wanted = try {
            parseMoveString(text)
        } catch (failure: MalformedMove) {
            throw IllegalMove(failure.message ?: "malformed move")
        }
        for (move in legalMoves()) {
            if (move.squares().contentEquals(wanted)) return move
        }
        throw IllegalMove("$text is not legal in this position")
    }

    fun resolve(origin: Int, destination: Int): Move {
        val matches = legalMoves().filter { it.origin == origin && it.destination == destination }
        if (matches.isEmpty()) {
            throw IllegalMove("$origin to $destination is not legal in this position")
        }
        if (matches.size > 1) {
            throw AmbiguousMove(
                "$origin to $destination matches several jump paths: " +
                        matches.joinToString(", ") { it.notation() }
            )
        }
        return matches[0]
    }

    // --- state ------------------------------------------------------------------------------

    fun hasPieces(side: Int): Boolean {
        for (square in 1..SQUARE_COUNT) {
            val code = codes[square]
            if (code != EMPTY && sideOfPiece(code) == side) return true
        }
        return false
    }

    fun repetitionCount(): Int = history.count { it == positionHash }

    /** `BLACK`, `WHITE`, [DRAW_RESULT] or `null` while the game is still on. */
    fun winner(): Int? {
        // Annihilation and immobilisation are checked first: a move that ends the game outright
        // settles it even on the ply that would otherwise trip the no-progress counter.
        if (!hasPieces(sideToMove)) return opponent(sideToMove)
        if (legalMoves().isEmpty()) return opponent(sideToMove)
        if (pliesSinceProgress >= NO_PROGRESS_PLIES) return DRAW_RESULT
        if (repetitionCount() >= REPETITION_LIMIT) return DRAW_RESULT
        return null
    }

    /** The winner as the wire string the API uses, or `null`. */
    fun winnerName(): String? = when (val result = winner()) {
        null -> null
        DRAW_RESULT -> Side.DRAW
        else -> Side.of(result)
    }

    fun isTerminal() = winner() != null

    fun totalPieces(): Int = (1..SQUARE_COUNT).count { codes[it] != EMPTY }

    fun count(side: Int, kings: Boolean): Int {
        var total = 0
        for (square in 1..SQUARE_COUNT) {
            val code = codes[square]
            if (code != EMPTY && sideOfPiece(code) == side && isKing(code) == kings) total++
        }
        return total
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Board) return false
        return sideToMove == other.sideToMove && codes.contentEquals(other.codes)
    }

    override fun hashCode(): Int = positionHash.toInt()

    override fun toString() = "Board(${toFen()}, pliesSinceProgress=$pliesSinceProgress)"
}

/** The sentinel [Board.winner] returns for a drawn game; distinct from [BLACK] and [WHITE]. */
const val DRAW_RESULT = 2

// --- move generation ------------------------------------------------------------------------

/**
 * Every legal move for [side] under [rules].
 *
 * Jumps are collected first because [VariantRules.mandatoryCapture] can make them the whole
 * answer, and because a position with a jump available is the common case worth being quick about.
 */
fun generateMoves(codes: IntArray, side: Int, rules: VariantRules): List<Move> {
    val jumps = ArrayList<Move>(8)
    for (origin in 1..SQUARE_COUNT) {
        val code = codes[origin]
        if (code != EMPTY && sideOfPiece(code) == side) {
            collectJumps(codes, origin, code, side, jumps, rules)
        }
    }
    if (jumps.isNotEmpty() && rules.mandatoryCapture) return jumps

    val moves = ArrayList<Move>(16)
    for (origin in 1..SQUARE_COUNT) {
        val code = codes[origin]
        if (code == EMPTY || sideOfPiece(code) != side) continue

        if (!isKing(code)) {
            val steps = STEPS[origin]
            for (direction in manDirs(side)) {
                val destination = steps[direction]
                if (destination != 0 && codes[destination] == EMPTY) {
                    moves.add(
                        Move(
                            origin, destination, intArrayOf(destination), EMPTY_CAPTURES,
                            isPromotionSquare(destination, side)
                        )
                    )
                }
            }
        } else if (rules.flyingKings) {
            for (direction in KING_DIRS) {
                for (destination in RAYS[origin][direction]) {
                    if (codes[destination] != EMPTY) break
                    moves.add(
                        Move(origin, destination, intArrayOf(destination), EMPTY_CAPTURES, false)
                    )
                }
            }
        } else {
            val steps = STEPS[origin]
            for (direction in KING_DIRS) {
                val destination = steps[direction]
                if (destination != 0 && codes[destination] == EMPTY) {
                    moves.add(
                        Move(origin, destination, intArrayOf(destination), EMPTY_CAPTURES, false)
                    )
                }
            }
        }
    }

    return if (jumps.isEmpty()) moves else jumps + moves
}

private val EMPTY_CAPTURES = IntArray(0)

/**
 * Append every complete jump sequence starting at [origin] to [out].
 *
 * A man that reaches its promotion row mid-jump stops there and is crowned: the sequence ends on
 * the crowning, which is the rule that separates English draughts from most of its cousins.
 */
fun collectJumps(
    codes: IntArray,
    origin: Int,
    piece: Int,
    side: Int,
    out: MutableList<Move>,
    rules: VariantRules
) {
    val path = IntArray(12)
    val captures = IntArray(12)
    val captured = BooleanArray(SQUARE_COUNT + 1)

    fun emit(depth: Int, destination: Int, crowned: Boolean) {
        out.add(
            Move(
                origin,
                destination,
                path.copyOfRange(0, depth),
                captures.copyOfRange(0, depth),
                crowned
            )
        )
    }

    if (!isKing(piece)) {
        val directions = if (rules.menCaptureBackwards) KING_DIRS else manDirs(side)

        fun walkMan(square: Int, depth: Int): Boolean {
            var extended = false
            for (direction in directions) {
                val over = JUMPED[square][direction]
                if (over == 0 || captured[over]) continue
                val target = codes[over]
                if (target == EMPTY || sideOfPiece(target) == side) continue
                val land = LANDING[square][direction]
                if (codes[land] != EMPTY && land != origin) continue

                extended = true
                path[depth] = land
                captures[depth] = over
                captured[over] = true

                if (isPromotionSquare(land, side)) emit(depth + 1, land, true)
                else if (!walkMan(land, depth + 1)) emit(depth + 1, land, false)

                captured[over] = false
            }
            return extended
        }

        walkMan(origin, 0)
        return
    }

    if (rules.flyingKings) {
        fun walkFlyingKing(square: Int, depth: Int): Boolean {
            var extended = false
            for (direction in KING_DIRS) {
                val ray = RAYS[square][direction]
                var over = 0
                for (sq in ray) {
                    if (over == 0) {
                        if (sq == origin || codes[sq] == EMPTY) continue
                        if (captured[sq]) break
                        if (sideOfPiece(codes[sq]) == side) break
                        over = sq
                    } else {
                        if (codes[sq] != EMPTY && sq != origin) break

                        extended = true
                        path[depth] = sq
                        captures[depth] = over
                        captured[over] = true

                        if (!walkFlyingKing(sq, depth + 1)) emit(depth + 1, sq, false)

                        captured[over] = false
                    }
                }
            }
            return extended
        }

        walkFlyingKing(origin, 0)
        return
    }

    fun walkStandardKing(square: Int, depth: Int): Boolean {
        var extended = false
        for (direction in KING_DIRS) {
            val over = JUMPED[square][direction]
            if (over == 0 || captured[over]) continue
            val target = codes[over]
            if (target == EMPTY || sideOfPiece(target) == side) continue
            val land = LANDING[square][direction]
            if (codes[land] != EMPTY && land != origin) continue

            extended = true
            path[depth] = land
            captures[depth] = over
            captured[over] = true

            if (!walkStandardKing(land, depth + 1)) emit(depth + 1, land, false)

            captured[over] = false
        }
        return extended
    }

    walkStandardKing(origin, 0)
}

/** Whether [origin] has any jump at all. Used by the "must capture" hint, which needs no paths. */
private fun canJumpFrom(
    codes: IntArray,
    origin: Int,
    piece: Int,
    side: Int,
    rules: VariantRules
): Boolean {
    if (isKing(piece) && rules.flyingKings) {
        for (direction in KING_DIRS) {
            var over = 0
            for (sq in RAYS[origin][direction]) {
                if (over == 0) {
                    if (codes[sq] == EMPTY) continue
                    if (sideOfPiece(codes[sq]) == side) break
                    over = sq
                } else {
                    if (codes[sq] != EMPTY) break
                    return true
                }
            }
        }
        return false
    }

    val directions = when {
        isKing(piece) -> KING_DIRS
        rules.menCaptureBackwards -> KING_DIRS
        else -> manDirs(side)
    }
    for (direction in directions) {
        val over = JUMPED[origin][direction]
        if (over == 0) continue
        val target = codes[over]
        if (target == EMPTY || sideOfPiece(target) == side) continue
        if (codes[LANDING[origin][direction]] == EMPTY) return true
    }
    return false
}

// --- FEN ------------------------------------------------------------------------------------

/** Read a PDN-style FEN into `(sideToMove, codes)`. */
fun splitFen(fen: String?): Pair<Int, IntArray> {
    val text = fen?.trim().orEmpty()
    val parts = text.split(":")
    if (parts.size != 3) throw IllegalMove("fen must have three colon-separated fields: $fen")

    val sideToMove = when (parts[0].trim().uppercase()) {
        "B" -> BLACK
        "W" -> WHITE
        else -> throw IllegalMove("fen side-to-move must be B or W: $fen")
    }

    val codes = IntArray(SQUARE_COUNT + 1)
    var seenBlack = false
    var seenWhite = false

    for (index in 1..2) {
        val field = parts[index].trim()
        if (field.isEmpty()) throw IllegalMove("fen piece list is missing its side letter: $fen")
        val side = when (field[0].uppercaseChar()) {
            'B' -> BLACK
            'W' -> WHITE
            else -> throw IllegalMove("fen piece list must start with B or W: $fen")
        }
        if (side == BLACK) {
            if (seenBlack) throw IllegalMove("fen lists black twice: $fen")
            seenBlack = true
        } else {
            if (seenWhite) throw IllegalMove("fen lists white twice: $fen")
            seenWhite = true
        }

        val body = field.substring(1).trim()
        if (body.isEmpty()) continue
        for (rawToken in body.split(",")) {
            val token = rawToken.trim().uppercase()
            if (token.isEmpty()) throw IllegalMove("empty square in fen: $fen")
            val king = token.startsWith("K")
            val digits = if (king) token.substring(1) else token
            val square = digits.toIntOrNull()
                ?: throw IllegalMove("bad square '$token' in fen: $fen")
            if (square !in 1..SQUARE_COUNT) throw IllegalMove("square out of range in fen: $square")
            if (codes[square] != EMPTY) throw IllegalMove("square $square occupied twice in fen: $fen")
            codes[square] = if (king) kingOf(side) else manOf(side)
        }
    }

    if (!seenBlack || !seenWhite) throw IllegalMove("fen must list both sides: $fen")
    return sideToMove to codes
}

/** Render `(side, codes)` as `B:W21,...:B1,...`. */
fun joinFen(sideToMove: Int, codes: IntArray): String {
    val white = StringBuilder()
    val black = StringBuilder()
    for (square in 1..SQUARE_COUNT) {
        val code = codes[square]
        if (code == EMPTY) continue
        val target = if (sideOfPiece(code) == WHITE) white else black
        if (target.isNotEmpty()) target.append(',')
        if (isKing(code)) target.append('K')
        target.append(square)
    }
    return "${if (sideToMove == WHITE) "W" else "B"}:W$white:B$black"
}
