package com.surenjanath.crownfoundry.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-language test.
 *
 * `policy-fixture.cfe` was written by `ai.export.build_artifact` from a network the backend
 * actually trained, and `policy-fixture.json` records what that network predicted for a set of
 * positions - computed in numpy, in float64, from `ai.features.encode`.
 *
 * If this file passes, the whole offline chain is proven end to end in one assertion: the reader
 * transcribes the weights, the encoder builds the same vector, and the forward pass reaches the
 * same number the server would have. If it fails, the AI on the device is not the AI on the
 * server, whatever the version badge says.
 *
 * To regenerate after a deliberate format change, see `Backend/ai/tests/test_export.py`.
 */
class ArtifactTest {

    private val blob: ByteArray by lazy {
        requireNotNull(javaClass.getResourceAsStream("/policy-fixture.cfe")) {
            "policy-fixture.cfe is missing from the test resources"
        }.use { it.readBytes() }
    }

    private val fixture by lazy {
        Json.parseToJsonElement(
            requireNotNull(javaClass.getResourceAsStream("/policy-fixture.json")) {
                "policy-fixture.json is missing from the test resources"
            }.use { it.readBytes().decodeToString() }
        ).jsonObject
    }

    @Test
    fun `the checksum matches what the server computed`() {
        assertEquals(
            fixture["checksum"]!!.jsonPrimitive.content,
            EngineArtifact.checksum(blob)
        )
        assertEquals(fixture["size"]!!.jsonPrimitive.int, blob.size)
    }

    @Test
    fun `the header carries what the manifest advertises`() {
        val (header, net) = EngineArtifact.read(blob)
        val manifest = fixture["manifest"]!!.jsonObject

        assertEquals(manifest["version"]!!.jsonPrimitive.int, header.version)
        assertEquals(manifest["elo"]!!.jsonPrimitive.int, header.elo)
        assertEquals(manifest["games_trained"]!!.jsonPrimitive.int, header.gamesTrained)
        assertEquals(manifest["architecture"]!!.jsonPrimitive.content, header.architecture)
        assertEquals(manifest["architecture"]!!.jsonPrimitive.content, net.architecture)
        assertEquals(FEATURE_SIZE, header.featureSize)
        // A fresh download has no local training on top of it yet.
        assertEquals(header.version, header.serverVersion)
        assertEquals(0, header.localGames)
    }

    @Test
    fun `the encoder builds the same vector the backend built`() {
        for (case in fixture["cases"]!!.jsonArray) {
            val entry = case.jsonObject
            val fen = entry["fen"]!!.jsonPrimitive.content
            val perspective = requireNotNull(Side.parse(entry["perspective"]!!.jsonPrimitive.content))
            val expected = entry["features"]!!.jsonArray.map { it.jsonPrimitive.float }

            val actual = encode(Board.fromFen(fen), perspective)
            assertEquals(expected.size, actual.size)
            for (i in expected.indices) {
                assertEquals("$fen [$perspective] component $i", expected[i], actual[i], 1e-6f)
            }
        }
    }

    @Test
    fun `the forward pass reaches the same value the backend reached`() {
        val (_, net) = EngineArtifact.read(blob)

        for (case in fixture["cases"]!!.jsonArray) {
            val entry = case.jsonObject
            val fen = entry["fen"]!!.jsonPrimitive.content
            val perspective = requireNotNull(Side.parse(entry["perspective"]!!.jsonPrimitive.content))
            val expected = entry["value"]!!.jsonPrimitive.float

            val actual = net.predict(encode(Board.fromFen(fen), perspective))
            // float32 against the backend's float64, over three layers. 1e-4 is two orders of
            // magnitude below the gaps the search actually decides moves on.
            assertEquals("$fen [$perspective]", expected, actual, 1e-4f)
        }
    }

    @Test
    fun `an artifact round trips through write and read`() {
        val (header, net) = EngineArtifact.read(blob)
        val rewritten = EngineArtifact.write(net, header)
        val (readBack, restored) = EngineArtifact.read(rewritten)

        assertEquals(header.version, readBack.version)
        assertEquals(header.elo, readBack.elo)
        assertEquals(net.architecture, restored.architecture)
        for (layer in 0 until net.nLayers) {
            assertTrue("weights $layer", net.weights[layer].contentEquals(restored.weights[layer]))
            assertTrue("biases $layer", net.biases[layer].contentEquals(restored.biases[layer]))
        }
    }

    @Test
    fun `local training is recorded without losing the server version`() {
        val (header, net) = EngineArtifact.read(blob)
        val trained = header.copy(localGames = 4, localLoss = 0.31f, version = header.version)

        val (readBack, _) = EngineArtifact.read(EngineArtifact.write(net, trained))

        assertEquals(4, readBack.localGames)
        assertTrue(readBack.hasLocalTraining)
        // Still comparable against the server manifest, which is the whole point of keeping it.
        assertEquals(header.version, readBack.serverVersion)
    }

    @Test
    fun `garbage is refused rather than loaded as weights`() {
        assertThrows(ArtifactException::class.java) { EngineArtifact.read(ByteArray(0)) }
        assertThrows(ArtifactException::class.java) { EngineArtifact.read("not a model".toByteArray()) }
        assertThrows(ArtifactException::class.java) { EngineArtifact.read(blob.copyOf(blob.size - 8)) }
        assertThrows(ArtifactException::class.java) { EngineArtifact.read(blob + ByteArray(4)) }
    }

    @Test
    fun `a newer format is refused with something the user can act on`() {
        // Patch the format digit in place. The header is ASCII JSON and the payload is raw
        // floats, so this edits the one byte and leaves the length prefix correct.
        val patched = blob.copyOf()
        val marker = "\"format\":1".toByteArray(Charsets.US_ASCII)
        val at = patched.indexOfSequence(marker)
        assertTrue("the header should declare its format", at >= 0)
        patched[at + marker.size - 1] = '2'.code.toByte()

        val failure = assertThrows(ArtifactException::class.java) { EngineArtifact.read(patched) }
        assertTrue(failure.message!!, failure.message!!.contains("update the app"))
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        outer@ for (start in 0..size - needle.size) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return start
        }
        return -1
    }

    @Test
    fun `a checksum change is detected`() {
        val tampered = blob.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        assertNotEquals(EngineArtifact.checksum(blob), EngineArtifact.checksum(tampered))
    }
}
