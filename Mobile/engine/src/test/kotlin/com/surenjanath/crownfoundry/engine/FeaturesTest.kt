package com.surenjanath.crownfoundry.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoder, pinned to the backend.
 *
 * This is the file that matters most in the whole module. A wrong scalar here does not throw and
 * does not fail a game: it silently feeds the downloaded weights a vector they were never fitted
 * against, and the AI just plays badly for reasons nothing in the app would report. Every expected
 * value below was printed by `ai.features.encode` and pasted in.
 */
class FeaturesTest {

    private fun tail(fen: String, perspective: Int): List<Float> {
        val vector = encode(Board.fromFen(fen), perspective)
        return (128 until FEATURE_SIZE).map { round6(vector[it]) }
    }

    private fun nonZeroPlanes(fen: String, perspective: Int): List<Int> {
        val vector = encode(Board.fromFen(fen), perspective)
        return (0 until 128).filter { vector[it] != 0f }
    }

    private fun round6(value: Float) = Math.round(value * 1_000_000f) / 1_000_000f

    @Test
    fun `feature size matches the network the backend ships`() {
        assertEquals(148, FEATURE_SIZE)
        assertEquals(8, CENTRE_COUNT)
        assertEquals(14, EDGE_COUNT)
    }

    @Test
    fun `the opening position encodes exactly as the backend encodes it`() {
        val fen = "B:W21,22,23,24,25,26,27,28,29,30,31,32:B1,2,3,4,5,6,7,8,9,10,11,12"

        assertEquals(
            listOf(
                1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0.25f, 0.25f, 0.142857f, 0.142857f,
                0.428571f, 0.428571f, 0.35f, 0f, 0.35f, 1f, 0f, 1f
            ),
            tail(fen, BLACK)
        )
        assertEquals(
            listOf(
                0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 0.25f, 0.25f, 0.142857f, 0.142857f,
                0.428571f, 0.428571f, 0.35f, 0f, -0.35f, 1f, 0f, 1f
            ),
            tail(fen, WHITE)
        )
        assertEquals((0..11).toList() + (84..95).toList(), nonZeroPlanes(fen, BLACK))
        assertEquals((0..11).toList() + (84..95).toList(), nonZeroPlanes(fen, WHITE))
    }

    @Test
    fun `a position with kings on both sides encodes exactly as the backend encodes it`() {
        val fen = "W:WK15,23:B10,K1"

        assertEquals(
            listOf(
                0f, 0f, 0f, 0f, 0.166667f, 0.166667f, 0.25f, 0f, 0.125f, 0.25f,
                0.285714f, 0.285714f, 0.071429f, 0f, 0.05f, 1f, -0.05f, 0.166667f, 0f, 1f
            ),
            tail(fen, BLACK)
        )
        assertEquals(listOf(9, 32, 86, 110), nonZeroPlanes(fen, BLACK))

        assertEquals(
            listOf(
                1f, 0f, 0f, 0f, 0.166667f, 0.166667f, 0f, 0.25f, 0.25f, 0.125f,
                0.285714f, 0.285714f, 0f, 0.071429f, 0.05f, 1f, 0.05f, 0.166667f, 0f, 1f
            ),
            tail(fen, WHITE)
        )
        assertEquals(listOf(9, 49, 86, 127), nonZeroPlanes(fen, WHITE))
    }

    @Test
    fun `a material imbalance encodes exactly as the backend encodes it`() {
        val fen = "B:W18,26,27:B11,K31"

        assertEquals(
            listOf(
                1f, -0.033333f, -0.166667f, 0.083333f, 0.166667f, 0.25f, 0f, 0f, 0.125f, 0.125f,
                0.285714f, 0.238095f, 0.071429f, 0f, 0.25f, 1f, 0.25f, 0.208333f, 0f, 1f
            ),
            tail(fen, BLACK)
        )
        assertEquals(listOf(10, 62, 81, 89, 90), nonZeroPlanes(fen, BLACK))

        assertEquals(
            listOf(
                0f, 0.033333f, 0.166667f, -0.083333f, 0.25f, 0.166667f, 0f, 0f, 0.125f, 0.125f,
                0.238095f, 0.285714f, 0f, 0.071429f, 0.25f, 1f, -0.25f, 0.208333f, 0f, 1f
            ),
            tail(fen, WHITE)
        )
        assertEquals(listOf(5, 6, 14, 85, 97), nonZeroPlanes(fen, WHITE))
    }

    @Test
    fun `the encoding is perspective-symmetric`() {
        // Rotating the board 180 degrees and swapping the colours has to produce an identical
        // vector read from the other side. That symmetry is why one set of weights plays both
        // colours, and it is the property a plane-index bug breaks first.
        val fen = "B:W18,26,27:B11,K31"
        val mirrored = mirrorFen(fen)

        val own = encode(Board.fromFen(fen), BLACK)
        val theirs = encode(Board.fromFen(mirrored), WHITE)

        for (i in 0 until FEATURE_SIZE) {
            assertEquals("component $i", own[i], theirs[i], 1e-6f)
        }
    }

    @Test
    fun `the no-progress counter reaches the vector`() {
        val fen = "B:W18,26,27:B11,K31"
        assertEquals(0f, encode(Board.fromFen(fen), BLACK)[146], 1e-6f)
        assertEquals(
            0.5f,
            encode(Board.fromFen(fen, pliesSinceProgress = 20), BLACK)[146],
            1e-6f
        )
        // Clamped, so a position that has stalled past the draw limit does not run off the scale.
        assertEquals(
            1f,
            encode(Board.fromFen(fen, pliesSinceProgress = 200), BLACK)[146],
            1e-6f
        )
    }

    @Test
    fun `the bias term is always one`() {
        for (fen in BoardTest.FENS) {
            for (perspective in listOf(BLACK, WHITE)) {
                assertEquals(fen, 1f, encode(Board.fromFen(fen), perspective)[147], 0f)
            }
        }
    }

    @Test
    fun `encoding into a reused buffer gives the same answer as a fresh one`() {
        val buffer = FloatArray(FEATURE_SIZE) { 99f }
        val board = Board.fromFen("W:WK15,23:B10,K1")

        encode(Board.initial(), BLACK, buffer)
        val reused = encode(board, WHITE, buffer)
        val fresh = encode(board, WHITE)

        assertTrue(reused.contentEquals(fresh))
    }

    /** Rotate a PDN-style FEN 180 degrees and swap the colours; `n -> 33 - n`. */
    private fun mirrorFen(fen: String): String {
        val parts = fen.split(":")
        fun flip(part: String) = part.substring(1)
            .split(",")
            .filter { it.isNotBlank() }
            .map { token ->
                val king = token.startsWith("K")
                val number = 33 - (if (king) token.substring(1) else token).toInt()
                (if (king) "K" else "") + number
            }
            .sortedBy { it.removePrefix("K").toInt() }
        val white = flip(parts[2])
        val black = flip(parts[1])
        val side = if (parts[0].uppercase() == "B") "W" else "B"
        return "$side:W${white.joinToString(",")}:B${black.joinToString(",")}"
    }
}
