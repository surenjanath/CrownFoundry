package com.surenjanath.crownfoundry.offline

import com.surenjanath.crownfoundry.api.ApiError
import com.surenjanath.crownfoundry.api.EngineApi
import com.surenjanath.crownfoundry.api.EngineManifestDto
import com.surenjanath.crownfoundry.api.OfflineMatchDto
import com.surenjanath.crownfoundry.api.Outcome
import com.surenjanath.crownfoundry.engine.ARTIFACT_FORMAT
import com.surenjanath.crownfoundry.engine.EngineArtifact
import java.time.Instant

/**
 * Keeping the on-device engine current, and getting offline games home.
 *
 * Both halves of the loop live here because they are one conversation: the device hands the server
 * the games it played alone, the server replays them, trains on them, and publishes a new policy -
 * which the device then downloads. That round trip is what stops the offline engine from becoming
 * a private fork of the real one. Play offline for a week and the phone is training its own copy;
 * connect once and the week's games are folded into the policy every other device gets.
 *
 * Nothing here is allowed to throw at a caller. A failed sync is a message on a card, not an
 * interrupted game - by the time this runs the player may well be mid-match against the engine it
 * is trying to replace.
 */
class EngineSync(
    private val api: EngineApi,
    private val matches: LocalMatchStore,
    private val preferences: EnginePreferences,
    private val engine: EngineStore = EngineStore
) {

    /** How many games go up in one call. The server refuses more than fifty. */
    private val uploadBatch = 25

    sealed interface Result {
        /** The device is current; nothing was downloaded because nothing needed to be. */
        data class UpToDate(val version: Int) : Result

        data class Updated(val from: Int?, val to: Int) : Result

        /** A newer engine exists and was not fetched - auto-update is off, or this was a check. */
        data class UpdateAvailable(val local: Int?, val server: Int) : Result

        data class Failed(val reason: ApiError) : Result

        /** The artifact arrived but is not what the manifest promised, or not readable here. */
        data class Rejected(val detail: String) : Result
    }

    data class UploadResult(
        val imported: Int = 0,
        val duplicates: Int = 0,
        val discarded: Int = 0,
        val remaining: Int = 0,
        val serverVersion: Int? = null,
        val failure: ApiError? = null
    )

    /**
     * Ask the server what it has, and take it if it is newer.
     *
     * [force] downloads regardless of the auto-update setting - it is what the "Update now" button
     * calls. Without it, a device with auto-update off still learns that it is behind, which is
     * what puts the badge on the Play screen.
     */
    suspend fun refresh(force: Boolean = false): Result {
        engine.setBusy(true)
        try {
            val manifest = when (val outcome = api.engineManifest()) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> return Result.Failed(outcome.reason)
            }

            engine.observeManifest(manifest.version, manifest.elo, preferences)

            val local = engine.state.header?.serverVersion
            val wanted = local == null ||
                    manifest.version > local ||
                    engine.state.status == EngineStatus.Incompatible

            if (!wanted) return Result.UpToDate(manifest.version)
            if (!force && !preferences.autoUpdate) {
                return Result.UpdateAvailable(local, manifest.version)
            }

            // Refuse a format this build cannot read *before* spending the download on it.
            if (manifest.format > ARTIFACT_FORMAT) {
                return Result.Rejected(
                    "The referee is publishing engine format ${manifest.format}; this app reads " +
                            "$ARTIFACT_FORMAT. Update CrownFoundry to keep playing offline."
                )
            }

            val blob = when (val outcome = api.downloadEngine()) {
                is Outcome.Success -> outcome.value
                is Outcome.Failure -> return Result.Failed(outcome.reason)
            }

            verify(blob, manifest)?.let { return Result.Rejected(it) }

            return engine.install(blob, preferences).fold(
                onSuccess = { Result.Updated(from = local, to = it.version) },
                onFailure = { Result.Rejected(it.message ?: "The engine could not be installed.") }
            )
        } finally {
            engine.setBusy(false)
            refreshPendingCount()
        }
    }

    /**
     * A download is only worth installing if it is the thing that was advertised.
     *
     * The checksum guards against a truncated transfer or a caching proxy serving a stale body;
     * the architecture check guards against something subtler - a server whose feature vector has
     * moved on, whose weights would load cleanly and then score every position wrongly.
     */
    private fun verify(blob: ByteArray, manifest: EngineManifestDto): String? {
        if (manifest.sizeBytes > 0 && blob.size.toLong() != manifest.sizeBytes) {
            return "The engine download was ${blob.size} bytes; the referee promised ${manifest.sizeBytes}."
        }
        if (manifest.checksum.isNotBlank()) {
            val actual = EngineArtifact.checksum(blob)
            if (!actual.equals(manifest.checksum, ignoreCase = true)) {
                return "The engine download did not match its checksum and was discarded."
            }
        }
        return null
    }

    /**
     * Send finished offline games to the server, oldest first.
     *
     * Games the server rejects outright are marked done rather than retried: a move list it will
     * not replay today it will not replay tomorrow either, and an outbox that never drains would
     * re-send the same broken game on every launch forever.
     */
    suspend fun uploadOutbox(playerId: String?): UploadResult {
        val pending = matches.pendingUploads()
        if (pending.isEmpty()) {
            refreshPendingCount()
            return UploadResult()
        }

        var imported = 0
        var duplicates = 0
        var discarded = 0
        var serverVersion: Int? = null

        for (batch in pending.chunked(uploadBatch)) {
            val payload = batch.map { match ->
                OfflineMatchDto(
                    localId = match.localId,
                    difficulty = match.difficulty,
                    rules = match.rules,
                    moves = match.moves,
                    resignedBy = match.resignedBy,
                    startedAt = Instant.ofEpochMilli(match.startedAt).toString(),
                    finishedAt = match.finishedAt?.let { Instant.ofEpochMilli(it).toString() },
                    engineVersion = match.engineVersion
                )
            }

            when (val outcome = api.syncOfflineMatches(playerId, payload)) {
                is Outcome.Failure -> {
                    refreshPendingCount()
                    return UploadResult(
                        imported = imported,
                        duplicates = duplicates,
                        discarded = discarded,
                        remaining = matches.pendingUploads().size,
                        failure = outcome.reason
                    )
                }

                is Outcome.Success -> {
                    val result = outcome.value
                    imported += result.imported
                    duplicates += result.accepted.count { it.duplicate }

                    val settled = result.accepted.map { it.localId }.toMutableList()
                    for (rejection in result.rejected) {
                        if (rejection.error in PERMANENT_REJECTIONS) {
                            settled.add(rejection.localId)
                            discarded++
                        }
                    }
                    matches.markUploaded(settled.filter { it.isNotBlank() })

                    result.engine?.let {
                        serverVersion = it.version
                        engine.observeManifest(it.version, it.elo, preferences)
                    }
                }
            }
        }

        preferences.lastSyncedAt = System.currentTimeMillis()
        val remaining = matches.pendingUploads().size
        engine.setPendingUploads(remaining)
        return UploadResult(imported, duplicates, discarded, remaining, serverVersion)
    }

    /**
     * The whole loop: push what was played offline, then pull whatever that produced.
     *
     * Upload first on purpose. The server trains on an imported game in the background, so the
     * manifest fetched afterwards is the one most likely to already reflect it - and when it does
     * not, the next launch picks it up.
     */
    suspend fun synchronise(playerId: String?, force: Boolean = false): Pair<UploadResult, Result> {
        val uploaded = uploadOutbox(playerId)
        val refreshed = refresh(force)
        return uploaded to refreshed
    }

    private suspend fun refreshPendingCount() {
        engine.setPendingUploads(matches.pendingUploads().size)
    }

    private companion object {
        /**
         * Server error codes that mean "this game will never import". Anything else - a 500, a
         * timeout, a proxy - is transient and stays in the outbox.
         */
        val PERMANENT_REJECTIONS = setOf(
            "illegal_move",
            "empty_match",
            "match_too_long",
            "invalid_difficulty",
            "invalid_match",
            "move_after_end"
        )
    }
}
