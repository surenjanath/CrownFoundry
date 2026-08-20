package com.surenjanath.crownfoundry.offline

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.surenjanath.crownfoundry.engine.ArtifactException
import com.surenjanath.crownfoundry.engine.EngineArtifact
import com.surenjanath.crownfoundry.engine.EngineHeader
import com.surenjanath.crownfoundry.engine.QNetwork
import com.surenjanath.crownfoundry.engine.ReplayBuffer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The on-device brain: what is installed, how old it is, and whether it can be played against.
 *
 * The product question this file answers is the one the whole offline mode hangs on - *can I play
 * right now, and is the opponent the one the server would give me?* Those are two different
 * questions and the UI needs both, which is why [EngineStatus] separates "no engine at all" from
 * "an engine, an old one". A stale engine is still a perfectly good opponent; a missing one is not
 * an opponent at all. Only the second is a reason to stop the player.
 */

enum class EngineStatus {
    /** Nothing installed. Offline play is impossible until the device has seen the server once. */
    Missing,

    /** Installed and current with the last manifest seen. */
    Ready,

    /** Installed, playable, but the server has trained past it. */
    Stale,

    /**
     * Installed, and unreadable by this build - a newer artifact format, or a feature vector this
     * app does not know how to produce. Playing on would mean feeding the weights nonsense, so it
     * is treated as no engine at all, with a message that says which way to fix it.
     */
    Incompatible
}

@Stable
data class EngineState(
    val status: EngineStatus = EngineStatus.Missing,
    val header: EngineHeader? = null,
    /** The newest server version this device has heard about, or `null` if it never has. */
    val serverVersion: Int? = null,
    val serverElo: Int? = null,
    val sizeBytes: Long = 0,
    val lastCheckedAt: Long = 0,
    val lastDownloadedAt: Long = 0,
    val lastTrainedAt: Long = 0,
    val pendingUploads: Int = 0,
    val busy: Boolean = false,
    val message: String? = null
) {
    /** The version to show the player, local training included: `v14` or `v14 +3`. */
    val label: String
        get() = when {
            header == null -> "none"
            header.hasLocalTraining -> "v${header.serverVersion} +${header.localGames}"
            else -> "v${header.serverVersion}"
        }

    val canPlayOffline get() = status == EngineStatus.Ready || status == EngineStatus.Stale

    val needsUpdate get() = status == EngineStatus.Missing ||
            status == EngineStatus.Stale ||
            status == EngineStatus.Incompatible

    /** How far behind the server this copy is, or `0` when it is current or unknown. */
    val versionsBehind: Int
        get() {
            val server = serverVersion ?: return 0
            val local = header?.serverVersion ?: return 0
            return maxOf(0, server - local)
        }

    /** One line for the card on the Play screen. */
    val headline: String
        get() = when (status) {
            EngineStatus.Missing -> "AI engine needs updating"
            EngineStatus.Incompatible -> "AI engine needs updating"
            EngineStatus.Stale -> "AI engine needs updating"
            EngineStatus.Ready -> "AI engine ${label} · ready offline"
        }

    val detail: String
        get() = when (status) {
            EngineStatus.Missing ->
                "Connect to the referee once to download the opponent. Until then, matches need a connection."

            EngineStatus.Incompatible ->
                message ?: "The installed engine was built for a newer version of this app."

            EngineStatus.Stale -> {
                val behind = versionsBehind
                val trained = if (header?.hasLocalTraining == true) {
                    " It has learned from ${header.localGames} offline ${plural(header.localGames, "game")} of yours in the meantime."
                } else ""
                "You are playing ${label}; the referee is on v${serverVersion}" +
                        (if (behind > 1) " — $behind versions ahead." else ".") + trained
            }

            EngineStatus.Ready ->
                if (header?.hasLocalTraining == true) {
                    "Current with the referee, plus ${header.localGames} offline ${plural(header.localGames, "game")} it learned from here."
                } else {
                    "Current with the referee. Elo ${header?.elo ?: 1200}, trained on ${header?.gamesTrained ?: 0} games."
                }
        }
}

private fun plural(count: Int, noun: String) = if (count == 1) noun else "${noun}s"

/**
 * The installed engine and everything that persists alongside it.
 *
 * A singleton because there is exactly one brain per device and three screens want to see it. The
 * network object it hands out is *shared and mutable* - training writes into the same weights the
 * search reads - so every entry point that touches it goes through [withNetwork], which serialises
 * access. A half-trained forward pass would not crash; it would quietly return a number from two
 * different models at once, which is worse.
 */
object EngineStore {

    private const val ARTIFACT_NAME = "policy.cfe"
    private const val REPLAY_NAME = "replay.bin"
    private const val DIRECTORY = "engine"

    /**
     * The starter engine shipped in the APK, regenerated by
     * `python manage.py export_engine --out Mobile/app/src/main/assets/policy.cfe`.
     */
    private const val BUNDLED_ASSET = "policy.cfe"

    private val mutex = Mutex()

    var state by mutableStateOf(EngineState())
        private set

    private var network: QNetwork? = null
    private var replay: ReplayBuffer? = null
    private var directory: File? = null
    private var loaded = false

    /**
     * Read whatever is on disk, falling back to the engine bundled in the APK.
     *
     * Safe to call more than once; the second call is a no-op. Never throws. An engine that will
     * not load is a state to draw, not a reason to fail to start the app.
     *
     * The bundled fallback is what makes a fresh install playable. Downloading the opponent from
     * the server is the *update* path, not the *first* path: an install from the Play Store has
     * never met a referee, may never be pointed at one, and a checkers app whose first screen
     * says "connect to something to get an opponent" is a checkers app that does not work.
     */
    suspend fun initialise(context: Context, preferences: EnginePreferences) =
        initialise(File(context.filesDir, DIRECTORY), preferences) {
            context.assets.open(BUNDLED_ASSET).use { it.readBytes() }
        }

    /**
     * The directory-taking form, so the store can be exercised without an Android context.
     *
     * [bundled] supplies the shipped starter artifact. It is a lambda rather than a byte array
     * because reading a 110 KB asset is work worth skipping when the device already has an
     * engine, which is every launch after the first.
     */
    suspend fun initialise(
        root: File,
        preferences: EnginePreferences,
        bundled: (() -> ByteArray)? = null
    ) = mutex.withLock {
        if (loaded) return@withLock
        loaded = true

        val dir = root.also { it.mkdirs() }
        directory = dir

        withContext(Dispatchers.IO) {
            val artifact = File(dir, ARTIFACT_NAME)
            if (artifact.exists()) {
                try {
                    val (header, net) = EngineArtifact.read(artifact.readBytes())
                    network = net
                    replay = ReplayBuffer.fromBytes(File(dir, REPLAY_NAME).takeIf { it.exists() }?.readBytes())
                    state = state.copy(
                        status = statusFor(header, preferences.lastServerVersion),
                        header = header,
                        serverVersion = preferences.lastServerVersion,
                        serverElo = preferences.lastServerElo,
                        sizeBytes = artifact.length(),
                        lastCheckedAt = preferences.lastCheckedAt,
                        lastDownloadedAt = preferences.lastDownloadedAt,
                        lastTrainedAt = preferences.lastTrainedAt
                    )
                } catch (failure: ArtifactException) {
                    state = state.copy(
                        status = EngineStatus.Incompatible,
                        message = failure.message,
                        serverVersion = preferences.lastServerVersion
                    )
                } catch (failure: Exception) {
                    state = state.copy(
                        status = EngineStatus.Missing,
                        message = "The installed engine could not be read (${failure.message}).",
                        serverVersion = preferences.lastServerVersion
                    )
                }
            } else if (!seedFromBundle(dir, preferences, bundled)) {
                state = state.copy(
                    status = EngineStatus.Missing,
                    serverVersion = preferences.lastServerVersion,
                    lastCheckedAt = preferences.lastCheckedAt
                )
            }
        }
    }

    /**
     * Install the artifact shipped inside the APK. Returns whether there is now an engine.
     *
     * It is written to disk rather than held in memory so the rest of the store needs no notion
     * of where the weights came from: on-device training persists into it, the next launch reads
     * it as an ordinary installed engine, and a server download later overwrites it.
     *
     * Every failure here is survivable - a build with no asset, a corrupt one, a full disk - and
     * each leaves the caller to report [EngineStatus.Missing], which is exactly the state the app
     * handled before an engine was ever bundled.
     */
    private fun seedFromBundle(
        dir: File,
        preferences: EnginePreferences,
        bundled: (() -> ByteArray)?
    ): Boolean {
        val blob = try {
            bundled?.invoke()
        } catch (failure: Exception) {
            null
        } ?: return false

        return try {
            val (header, net) = EngineArtifact.read(blob)
            network = net
            replay = ReplayBuffer()
            try {
                File(dir, ARTIFACT_NAME).writeBytes(blob)
            } catch (failure: Exception) {
                // Playable this session, re-seeded from the asset the next one. Not worth failing.
            }
            state = state.copy(
                status = statusFor(header, preferences.lastServerVersion),
                header = header,
                serverVersion = preferences.lastServerVersion,
                serverElo = preferences.lastServerElo,
                sizeBytes = blob.size.toLong(),
                lastCheckedAt = preferences.lastCheckedAt,
                message = null
            )
            true
        } catch (failure: Exception) {
            false
        }
    }

    /**
     * Run [block] against the live network, with nothing else touching it.
     *
     * Returns `null` when there is no engine, which is the caller's cue to fall back to the
     * server rather than to invent a move.
     */
    suspend fun <T> withNetwork(block: suspend (QNetwork) -> T): T? = mutex.withLock {
        val net = network ?: return@withLock null
        block(net)
    }

    suspend fun <T> withLearning(block: suspend (QNetwork, ReplayBuffer) -> T): T? = mutex.withLock {
        val net = network ?: return@withLock null
        val buffer = replay ?: ReplayBuffer().also { replay = it }
        block(net, buffer)
    }

    /** Replace the installed engine with a freshly downloaded artifact. */
    suspend fun install(blob: ByteArray, preferences: EnginePreferences): Result<EngineHeader> =
        mutex.withLock {
            val dir = directory ?: return@withLock Result.failure(
                IllegalStateException("the engine store has not been initialised")
            )
            try {
                val (header, net) = EngineArtifact.read(blob)
                withContext(Dispatchers.IO) {
                    // Write beside the target and rename, so a download killed halfway through
                    // leaves the previous engine intact rather than a truncated file.
                    val staging = File(dir, "$ARTIFACT_NAME.part")
                    staging.writeBytes(blob)
                    staging.renameTo(File(dir, ARTIFACT_NAME))
                    // The optimiser state belonged to the old weights; the experience does not,
                    // so the replay buffer survives a model swap and the moments do not.
                    File(dir, REPLAY_NAME).takeIf { it.exists() }?.let {
                        replay = ReplayBuffer.fromBytes(it.readBytes())
                    }
                }
                network = net
                preferences.lastDownloadedAt = System.currentTimeMillis()
                preferences.lastServerVersion = header.version
                preferences.lastServerElo = header.elo

                state = state.copy(
                    status = EngineStatus.Ready,
                    header = header,
                    serverVersion = header.version,
                    serverElo = header.elo,
                    sizeBytes = blob.size.toLong(),
                    lastDownloadedAt = preferences.lastDownloadedAt,
                    message = null
                )
                Result.success(header)
            } catch (failure: Exception) {
                state = state.copy(message = failure.message)
                Result.failure(failure)
            }
        }

    /** Persist the weights after on-device training, keeping the server version they came from. */
    suspend fun persistLocalTraining(
        gamesLearned: Int,
        loss: Float,
        preferences: EnginePreferences
    ) = mutex.withLock {
        val dir = directory ?: return@withLock
        val net = network ?: return@withLock
        val previous = state.header ?: return@withLock

        val header = previous.copy(
            localGames = previous.localGames + gamesLearned,
            localLoss = loss,
            baseVersion = previous.serverVersion
        )
        try {
            withContext(Dispatchers.IO) {
                File(dir, ARTIFACT_NAME).writeBytes(EngineArtifact.write(net, header))
                replay?.let { File(dir, REPLAY_NAME).writeBytes(it.toBytes()) }
            }
            preferences.lastTrainedAt = System.currentTimeMillis()
            state = state.copy(header = header, lastTrainedAt = preferences.lastTrainedAt)
        } catch (failure: Exception) {
            // The weights in memory are still the trained ones; only the copy on disk is stale.
            // Losing a game's worth of learning to a full disk is not worth a visible failure.
            state = state.copy(message = "Could not save training (${failure.message}).")
        }
    }

    /** Fold a freshly seen manifest into the state, whether or not anything gets downloaded. */
    fun observeManifest(version: Int, elo: Int, preferences: EnginePreferences) {
        preferences.lastServerVersion = version
        preferences.lastServerElo = elo
        preferences.lastCheckedAt = System.currentTimeMillis()
        state = state.copy(
            status = state.header?.let { statusFor(it, version) } ?: state.status,
            serverVersion = version,
            serverElo = elo,
            lastCheckedAt = preferences.lastCheckedAt
        )
    }

    fun setBusy(busy: Boolean) {
        state = state.copy(busy = busy)
    }

    fun setPendingUploads(count: Int) {
        state = state.copy(pendingUploads = count)
    }

    fun setMessage(message: String?) {
        state = state.copy(message = message)
    }

    private fun statusFor(header: EngineHeader, serverVersion: Int?): EngineStatus = when {
        serverVersion == null -> EngineStatus.Ready
        serverVersion > header.serverVersion -> EngineStatus.Stale
        else -> EngineStatus.Ready
    }

    /** Test seam: drop everything so a fresh [initialise] reads the disk again. */
    internal fun resetForTest() {
        network = null
        replay = null
        directory = null
        loaded = false
        state = EngineState()
    }
}
