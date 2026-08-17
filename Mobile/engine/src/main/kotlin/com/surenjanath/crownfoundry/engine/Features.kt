package com.surenjanath.crownfoundry.engine

/**
 * State representation for the Q-network - the Kotlin half of `Backend/ai/features.py`.
 *
 * This file has to agree with the Python to the last float. The weights the device downloads were
 * fitted against vectors the Python produced; a scalar in the wrong slot here does not throw, it
 * just makes the downloaded policy play like a different, worse policy. `FeaturesTest` pins every
 * component against values taken from the backend.
 *
 * Layout:
 * ```
 * [   0 ..  31]  own men          (perspective-oriented square index)
 * [  32 ..  63]  own kings
 * [  64 ..  95]  opponent men
 * [  96 .. 127]  opponent kings
 * [ 128 .. 147]  engineered scalars
 * ```
 */

const val PLANE_SIZE = 32
const val N_PLANES = 4
const val ENGINEERED_COUNT = 20
const val FEATURE_SIZE = N_PLANES * PLANE_SIZE + ENGINEERED_COUNT

const val KING_VALUE = 1.6f

/**
 * Centre and edge masks, in absolute square numbers. Both sets are invariant under the 180-degree
 * rotation that maps `n -> 33 - n`, which is what keeps the encoding perspective-symmetric.
 */
private val CENTRE = BooleanArray(SQUARE_COUNT + 1).also { mask ->
    for (n in 1..SQUARE_COUNT) mask[n] = rowOf(n) in 2..5 && colOf(n) in 2..5
}

private val EDGE = BooleanArray(SQUARE_COUNT + 1).also { mask ->
    for (n in 1..SQUARE_COUNT) mask[n] = rowOf(n) == 0 || rowOf(n) == 7 || colOf(n) == 0 || colOf(n) == 7
}

val CENTRE_COUNT = (1..SQUARE_COUNT).count { CENTRE[it] }  // 8
val EDGE_COUNT = (1..SQUARE_COUNT).count { EDGE[it] }      // 14

/**
 * 0-based plane index for [square] seen from [perspective].
 *
 * Black advances toward higher square numbers, White toward lower ones. Indexing White's view
 * backwards means "index 0..3 is my back rank, 28..31 is the promotion row" for both sides, which
 * is what lets one set of weights play both colours.
 */
fun perspectiveIndex(square: Int, perspective: Int): Int =
    if (perspective == BLACK) square - 1 else 32 - square

/** Encode [board] from [perspective] into a `FEATURE_SIZE`-long vector. */
fun encode(board: Board, perspective: Int, into: FloatArray? = null): FloatArray {
    val vec = into?.also { it.fill(0f) } ?: FloatArray(FEATURE_SIZE)

    var ownMen = 0
    var ownKings = 0
    var oppMen = 0
    var oppKings = 0
    var ownCentre = 0
    var oppCentre = 0
    var ownEdge = 0
    var oppEdge = 0
    var ownBackRank = 0
    var oppBackRank = 0
    var ownAdvancementSum = 0
    var oppAdvancementSum = 0

    for (square in 1..SQUARE_COUNT) {
        val code = board.codes[square]
        if (code == EMPTY) continue

        val index = perspectiveIndex(square, perspective)
        val king = isKing(code)
        val mine = sideOfPiece(code) == perspective

        if (mine) {
            if (king) {
                ownKings++
                vec[PLANE_SIZE + index] = 1f
            } else {
                ownMen++
                vec[index] = 1f
                ownAdvancementSum += index / 4
            }
            if (CENTRE[square]) ownCentre++
            if (EDGE[square]) ownEdge++
            if (index < 4) ownBackRank++
        } else {
            if (king) {
                oppKings++
                vec[3 * PLANE_SIZE + index] = 1f
            } else {
                oppMen++
                vec[2 * PLANE_SIZE + index] = 1f
                oppAdvancementSum += index / 4
            }
            if (CENTRE[square]) oppCentre++
            if (EDGE[square]) oppEdge++
            if (index >= 28) oppBackRank++
        }
    }

    val ownTotal = ownMen + ownKings
    val oppTotal = oppMen + oppKings

    val moves = board.legalMoves()
    val toMoveIsSelf = if (board.sideToMove == perspective) 1f else 0f
    val mobility = minOf(moves.size, 20) / 20f
    val captureAvailable = if (moves.any { it.isJump }) 1f else 0f

    val ownMaterial = ownMen + KING_VALUE * ownKings
    val oppMaterial = oppMen + KING_VALUE * oppKings

    val ownAdvancement = if (ownMen == 0) 0f else (ownAdvancementSum.toFloat() / ownMen) / 7f
    val oppAdvancement = if (oppMen == 0) 0f else 1f - (oppAdvancementSum.toFloat() / oppMen) / 7f

    var i = N_PLANES * PLANE_SIZE
    vec[i++] = toMoveIsSelf
    vec[i++] = (ownMaterial - oppMaterial) / 12f
    vec[i++] = (ownMen - oppMen) / 12f
    vec[i++] = (ownKings - oppKings) / 12f
    vec[i++] = ownTotal / 12f
    vec[i++] = oppTotal / 12f
    // "Back rank integrity": pieces still guarding the four squares the opponent must reach to
    // crown. Losing it is the classic way a winning draughts position evaporates.
    vec[i++] = ownBackRank / 4f
    vec[i++] = oppBackRank / 4f
    vec[i++] = ownCentre / CENTRE_COUNT.toFloat()
    vec[i++] = oppCentre / CENTRE_COUNT.toFloat()
    vec[i++] = ownAdvancement
    vec[i++] = oppAdvancement
    vec[i++] = ownEdge / EDGE_COUNT.toFloat()
    vec[i++] = oppEdge / EDGE_COUNT.toFloat()
    vec[i++] = mobility
    vec[i++] = captureAvailable
    vec[i++] = if (toMoveIsSelf != 0f) mobility else -mobility
    vec[i++] = (ownTotal + oppTotal) / 24f
    vec[i++] = minOf(board.pliesSinceProgress, 40) / 40f
    vec[i] = 1f

    return vec
}
