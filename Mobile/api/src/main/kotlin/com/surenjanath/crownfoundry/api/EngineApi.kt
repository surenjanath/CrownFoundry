package com.surenjanath.crownfoundry.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The engine-distribution half of the backend, kept apart from [CheckersApi] on purpose.
 *
 * [CheckersApi] is "referee a game", and the offline mode's whole trick is that it can answer that
 * interface without a network. This one is "keep the on-device brain current", which is inherently
 * online: nothing here has an offline implementation, and nothing here belongs in the seam the
 * offline referee plugs into.
 */
interface EngineApi {

    /** What the server's current policy is. Cheap enough to poll whenever the app comes forward. */
    suspend fun engineManifest(): Outcome<EngineManifestDto>

    /**
     * The policy itself, as CFE1 bytes, verified against [EngineManifestDto.checksum] on arrival.
     *
     * [version] pins the download to the policy the manifest named. Without it the server serves
     * whatever is current, and on a server that trains continuously that can already be a newer
     * policy than the manifest described - so the bytes would be checked against the wrong
     * checksum and a sound engine discarded as corrupt.
     */
    suspend fun downloadEngine(version: Int? = null): Outcome<ByteArray>

    /**
     * Hand back games the device refereed itself. The server replays every move through its own
     * engine before accepting one, and answers with the manifest as it now stands - so a sync that
     * triggers training tells the device it is stale in the same round trip.
     */
    suspend fun syncOfflineMatches(
        playerId: String?,
        matches: List<OfflineMatchDto>
    ): Outcome<EngineSyncDto>
}

@Serializable
data class EngineManifestDto(
    val ok: Boolean = true,
    val format: Int = 0,
    val version: Int = 0,
    val architecture: String = "",
    @SerialName("feature_size") val featureSize: Int = 0,
    val elo: Int = 1200,
    @SerialName("games_trained") val gamesTrained: Int = 0,
    @SerialName("last_loss") val lastLoss: Double? = null,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val checksum: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val notes: String = "",
    val url: String = ENGINE_DOWNLOAD_PATH
)

/** One game played with nobody watching, in the shape `/api/ai/engine/sync/` reads. */
@Serializable
data class OfflineMatchDto(
    @SerialName("local_id") val localId: String,
    val difficulty: String = "adaptive",
    val rules: MatchRulesDto? = null,
    /** Canonical notations, in order, from the opening position. The server replays these. */
    val moves: List<String> = emptyList(),
    /** A resignation leaves no trace in the move list, so it has to be said out of band. */
    @SerialName("resigned_by") val resignedBy: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("engine_version") val engineVersion: Int = 0
)

@Serializable
data class SyncAcceptedDto(
    @SerialName("local_id") val localId: String = "",
    @SerialName("match_id") val matchId: String = "",
    /** True when the server had already imported this one; the client can drop it either way. */
    val duplicate: Boolean = false
)

@Serializable
data class SyncRejectedDto(
    @SerialName("local_id") val localId: String = "",
    val index: Int = 0,
    val error: String = "",
    val detail: String = ""
)

@Serializable
data class EngineSyncDto(
    val ok: Boolean = true,
    val accepted: List<SyncAcceptedDto> = emptyList(),
    val rejected: List<SyncRejectedDto> = emptyList(),
    val imported: Int = 0,
    @SerialName("player_id") val playerId: String = "",
    /** Absent only if the server could not build one; the device then keeps what it has. */
    val engine: EngineManifestDto? = null
)

const val ENGINE_MANIFEST_PATH = "/api/ai/engine/manifest/"
const val ENGINE_DOWNLOAD_PATH = "/api/ai/engine/download/"
const val ENGINE_SYNC_PATH = "/api/ai/engine/sync/"
