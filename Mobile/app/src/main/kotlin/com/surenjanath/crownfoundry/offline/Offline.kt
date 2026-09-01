package com.surenjanath.crownfoundry.offline

import android.content.Context
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.api.EngineApi
import com.surenjanath.crownfoundry.api.PublishedEngineApi
import com.surenjanath.crownfoundry.leaderboard.Leaderboards
import com.surenjanath.crownfoundry.leaderboard.scoresOf
import com.surenjanath.crownfoundry.utils.backendUrlKey
import com.surenjanath.crownfoundry.utils.effectiveBackendUrl
import com.surenjanath.crownfoundry.utils.publishedEngineUrl
import com.surenjanath.crownfoundry.utils.preferences as appPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where offline mode is assembled.
 *
 * The app has no dependency-injection framework and does not want one - screens reach for
 * `CrownFoundryClient` by name. This keeps that shape: one object, wired once from
 * `MainApplication`, handing out the same [CheckersApi] the screens already expect. The only
 * change a screen has to make is asking for [api] instead of `CrownFoundryClient`.
 *
 * Everything is nullable until [initialise] runs, and [api] falls back to the plain network client
 * until then, so a screen that composes early behaves exactly as it did before offline mode
 * existed rather than crashing on a half-built singleton.
 */
object Offline {

    private var hybrid: HybridCheckersApi? = null
    private var passAndPlayApi: PassAndPlayApi? = null
    private var sync: EngineSync? = null
    private var store: LocalMatchStore? = null
    private var puzzleStore: PuzzleStore? = null
    private var preferences: EnginePreferences? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var appContext: Context? = null

    /**
     * Whether the engine can be updated at all, from either kind of source.
     *
     * Distinct from [backendAvailable]: a build with no referee can still be pointed at a
     * published manifest, and that is the shape the Play Store build actually ships in. What it
     * gains is updates; what it still cannot do is send its games anywhere.
     */
    val engineUpdatesAvailable: Boolean
        get() = backendAvailable || publishedEngineUrl != null

    /**
     * Whether there is a referee to reach: an address from the build, or one the player typed.
     *
     * Read on every call rather than captured at start-up, so naming a server in Settings takes
     * effect on the next request instead of on the next launch.
     */
    val backendAvailable: Boolean
        get() {
            val context = appContext ?: return true
            val stored = context.appPreferences.getString(backendUrlKey, null)
            return effectiveBackendUrl(stored) != null
        }

    /** The API every screen should talk to. Routes online/offline; never null. */
    val api: CheckersApi get() = hybrid ?: CrownFoundryClient

    /** `null` before [initialise]; the UI treats that as "no offline information yet". */
    val hybridOrNull: HybridCheckersApi? get() = hybrid

    /**
     * The referee for a game between two people on this phone. Never touches the network and
     * never needs a policy, so it is offered whether or not an engine has been downloaded.
     *
     * `null` only before [initialise], which is the same window in which no screen exists to ask.
     */
    val passAndPlay: CheckersApi? get() = passAndPlayApi

    val engine: EngineStore get() = EngineStore

    val matches: LocalMatchStore? get() = store

    /** The positions the player got wrong, collected when a game is reviewed. */
    val puzzles: PuzzleStore? get() = puzzleStore

    val settings: EnginePreferences? get() = preferences

    fun initialise(context: Context) {
        if (hybrid != null) return

        appContext = context.applicationContext

        val enginePreferences = EnginePreferences(context)
        val matchStore = LocalMatchStore(context)
        val offline = OfflineCheckersApi(
            store = matchStore,
            engine = EngineStore,
            preferences = enginePreferences
        )

        preferences = enginePreferences
        store = matchStore
        puzzleStore = PuzzleStore(context)
        passAndPlayApi = PassAndPlayApi(offline)
        hybrid = HybridCheckersApi(
            remote = CrownFoundryClient,
            local = offline,
            preferences = enginePreferences,
            backendAvailable = { backendAvailable }
        )
        sync = EngineSync(
            api = engineSourceFor(),
            matches = matchStore,
            preferences = enginePreferences
        )

        scope.launch {
            EngineStore.initialise(context, enginePreferences)
            EngineStore.setPendingUploads(matchStore.pendingUploads().size)
        }
    }

    /**
     * The referee when there is one, and the published manifest when there is not.
     *
     * Chosen once, at start-up, rather than per call: a player who types a server address into
     * Settings is choosing a referee, and switching the engine source under a sync already in
     * flight would leave a download being checked against the other source's manifest.
     */
    private fun engineSourceFor(): EngineApi =
        if (backendAvailable) CrownFoundryClient
        else publishedEngineUrl?.let { PublishedEngineApi(it) } ?: CrownFoundryClient

    /**
     * Push what was played offline and pull whatever the server has since trained.
     *
     * Fire-and-forget: called when the app comes forward and after a match ends. A player who
     * wants a definite answer uses the button in Settings, which awaits [synchronise].
     */
    fun synchroniseInBackground(playerId: String?, force: Boolean = false) {
        // Nothing to push to and nothing to pull from; the bundled engine is the whole product.
        if (!engineUpdatesAvailable) return
        val engineSync = sync ?: return

        // This runs when the app comes forward and after every finished game, which on a build
        // that has an outbox is the right cadence - there is something new to send each time. On
        // a build reading a published manifest there is not: nothing goes up, and a policy is
        // published every few days at best, so a fetch per game is a request per game for an
        // answer that has not changed. The button in Settings passes `force` and is never
        // throttled, so "check now" always means now.
        val settings = preferences
        if (!force && settings != null && !backendAvailable) {
            val since = System.currentTimeMillis() - settings.lastCheckedAt
            if (settings.lastCheckedAt > 0 && since < PUBLISHED_CHECK_INTERVAL_MS) return
        }

        scope.launch {
            runCatching { engineSync.synchronise(playerId, force) }
        }
    }

    /** How often a build with no referee looks for a newly published policy on its own. */
    private const val PUBLISHED_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    suspend fun synchronise(
        playerId: String?,
        force: Boolean = false
    ): Pair<EngineSync.UploadResult, EngineSync.Result>? =
        if (!engineUpdatesAvailable) null else sync?.synchronise(playerId, force)

    suspend fun refresh(force: Boolean = false): EngineSync.Result? =
        if (!engineUpdatesAvailable) null else sync?.refresh(force)

    suspend fun uploadOutbox(playerId: String?): EngineSync.UploadResult? =
        if (!backendAvailable) null else sync?.uploadOutbox(playerId)

    /**
     * Recompute the leaderboard scores and post them.
     *
     * Recomputed from the stored corpus rather than incremented as results arrive, because the
     * corpus is the only thing that survives a reinstall-and-restore, and a counter kept beside it
     * would drift the first time a game was written that this call did not see.
     *
     * Fire-and-forget, and a no-op in a build with no leaderboards.
     */
    fun publishScores() {
        if (!Leaderboards.available) return
        val matchStore = store ?: return
        scope.launch {
            runCatching {
                Leaderboards.submitAll(
                    scoresOf(matchStore.all(), puzzleStore?.all().orEmpty())
                )
            }
        }
    }
}
