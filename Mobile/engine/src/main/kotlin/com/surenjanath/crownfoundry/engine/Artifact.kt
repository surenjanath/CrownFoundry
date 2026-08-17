package com.surenjanath.crownfoundry.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reading and writing the shipping engine artifact - the Kotlin half of `Backend/ai/export.py`.
 *
 * ```
 * offset  size          field
 * 0       4             magic, "CFE1"
 * 4       4             uint32 little-endian header length
 * 8       header_len    UTF-8 JSON header
 * ...     4 * n         float32 little-endian payload, layer by layer
 * ```
 *
 * Layers are written in order, each as `W` (`fanIn * fanOut`, row-major) then `b` (`fanOut`).
 *
 * The same format is written back out after on-device training, so a fine-tuned engine survives
 * the app being killed. [EngineHeader.baseVersion] is what makes that safe: it records the server
 * policy the local weights were derived from, so "am I stale?" stays answerable after the device
 * has trained on top of what it downloaded.
 */

private const val MAGIC = "CFE1"
private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.US_ASCII)

const val ARTIFACT_FORMAT = 1

/** A phone has no business loading a multi-megabyte "policy"; refuse before allocating for it. */
const val MAX_ARTIFACT_BYTES = 32 * 1024 * 1024

class ArtifactException(message: String) : IllegalArgumentException(message)

@Serializable
data class EngineHeader(
    val format: Int = 0,
    /** The server policy version these weights came from. The whole basis of the stale check. */
    val version: Int = 0,
    val layers: List<Int> = emptyList(),
    @SerialName("feature_size") val featureSize: Int = FEATURE_SIZE,
    val lr: Float = 1e-3f,
    val beta1: Float = 0.9f,
    val beta2: Float = 0.999f,
    val eps: Float = 1e-8f,
    @SerialName("grad_clip") val gradClip: Float = 5f,
    @SerialName("huber_delta") val huberDelta: Float = 1f,
    @SerialName("step_count") val stepCount: Int = 0,
    val elo: Int = 1200,
    @SerialName("games_trained") val gamesTrained: Int = 0,
    @SerialName("last_loss") val lastLoss: Float? = null,
    val notes: String = "",
    @SerialName("created_at") val createdAt: String = "",

    // --- device-only fields; the backend reader ignores anything it does not know ---

    /** Same as [version] on a fresh download; preserved across local fine-tuning. */
    @SerialName("base_version") val baseVersion: Int = -1,
    /** Offline games this copy has trained on since it was downloaded. */
    @SerialName("local_games") val localGames: Int = 0,
    @SerialName("local_loss") val localLoss: Float? = null
) {
    /** The version to compare against the server's manifest, whatever local training has happened. */
    val serverVersion: Int get() = if (baseVersion >= 0) baseVersion else version

    val architecture: String get() = layers.joinToString("-")

    val hasLocalTraining: Boolean get() = localGames > 0
}

private val artifactJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

object EngineArtifact {

    /** Parse an artifact into its header and a ready-to-play, ready-to-train network. */
    fun read(blob: ByteArray): Pair<EngineHeader, QNetwork> {
        if (blob.size < 8) throw ArtifactException("not a CFE1 artifact: ${blob.size} bytes")
        for (i in MAGIC_BYTES.indices) {
            if (blob[i] != MAGIC_BYTES[i]) throw ArtifactException("not a CFE1 artifact")
        }
        if (blob.size > MAX_ARTIFACT_BYTES) {
            throw ArtifactException("artifact is ${blob.size} bytes, past the $MAX_ARTIFACT_BYTES cap")
        }

        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        val headerLength = buffer.int
        if (headerLength <= 0 || 8L + headerLength > blob.size) {
            throw ArtifactException("header length $headerLength runs past the end of the blob")
        }

        val header = try {
            artifactJson.decodeFromString(
                EngineHeader.serializer(),
                String(blob, 8, headerLength, Charsets.UTF_8)
            )
        } catch (failure: Exception) {
            throw ArtifactException("header is not readable: ${failure.message}")
        }

        if (header.format != ARTIFACT_FORMAT) {
            throw ArtifactException(
                "artifact format ${header.format} is newer than this build understands " +
                        "($ARTIFACT_FORMAT); update the app"
            )
        }
        if (header.layers.size < 2) {
            throw ArtifactException("header must list at least an input and an output layer")
        }
        if (header.layers[0] != FEATURE_SIZE) {
            // A feature-size mismatch means the backend changed its state representation. Playing
            // on regardless would silently produce nonsense evaluations, so it is a hard refusal.
            throw ArtifactException(
                "artifact expects ${header.layers[0]} features, this build encodes $FEATURE_SIZE"
            )
        }

        val net = QNetwork(
            layerSizes = header.layers.toIntArray(),
            lr = header.lr,
            beta1 = header.beta1,
            beta2 = header.beta2,
            eps = header.eps,
            gradClip = header.gradClip,
            huberDelta = header.huberDelta
        )

        var cursor = 8 + headerLength
        for (layer in 0 until net.nLayers) {
            cursor = readFloats(blob, cursor, net.weights[layer])
            cursor = readFloats(blob, cursor, net.biases[layer])
        }
        if (cursor != blob.size) {
            throw ArtifactException("${blob.size - cursor} trailing bytes after the last layer")
        }

        net.stepCount = header.stepCount
        return header to net
    }

    /** Serialise [net] under [header]. The inverse of [read], byte for byte. */
    fun write(net: QNetwork, header: EngineHeader): ByteArray {
        val stamped = header.copy(
            format = ARTIFACT_FORMAT,
            layers = net.layerSizes.toList(),
            featureSize = FEATURE_SIZE,
            lr = net.lr,
            beta1 = net.beta1,
            beta2 = net.beta2,
            eps = net.eps,
            gradClip = net.gradClip,
            huberDelta = net.huberDelta,
            stepCount = net.stepCount,
            baseVersion = header.serverVersion
        )
        val encoded = artifactJson.encodeToString(EngineHeader.serializer(), stamped)
            .toByteArray(Charsets.UTF_8)

        var floats = 0
        for (layer in 0 until net.nLayers) {
            floats += net.weights[layer].size + net.biases[layer].size
        }

        val buffer = ByteBuffer.allocate(8 + encoded.size + 4 * floats).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC_BYTES)
        buffer.putInt(encoded.size)
        buffer.put(encoded)
        for (layer in 0 until net.nLayers) {
            for (value in net.weights[layer]) buffer.putFloat(value)
            for (value in net.biases[layer]) buffer.putFloat(value)
        }
        return buffer.array()
    }

    /** The identity a download is verified against; matches `hashlib.sha256` on the server. */
    fun checksum(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    private fun readFloats(blob: ByteArray, cursor: Int, into: FloatArray): Int {
        val end = cursor + into.size * 4
        if (end > blob.size) {
            throw ArtifactException(
                "payload truncated: wanted ${into.size} floats, ${blob.size - cursor} bytes left"
            )
        }
        val buffer = ByteBuffer.wrap(blob, cursor, into.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in into.indices) into[i] = buffer.float
        return end
    }
}
