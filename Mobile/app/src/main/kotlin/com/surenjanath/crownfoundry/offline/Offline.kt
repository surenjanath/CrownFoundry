package com.surenjanath.crownfoundry.offline

import android.content.Context
import com.surenjanath.crownfoundry.api.CheckersApi
import com.surenjanath.crownfoundry.api.CrownFoundryClient
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
    private var preferences: EnginePreferences? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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

    val settings: EnginePreferences? get() = preferences

    fun initialise(context: Context) {
        if (hybrid != null) return

        val enginePreferences = EnginePreferences(context)
        val matchStore = LocalMatchStore(context)
        val offline = OfflineCheckersApi(
            store = matchStore,
            engine = EngineStore,
            preferences = enginePreferences
        )

        preferences = enginePreferences
        store = matchStore
        passAndPlayApi = PassAndPlayApi(offline)
        hybrid = HybridCheckersApi(
            remote = CrownFoundryClient,
            local = offline,
            preferences = enginePreferences
        )
        sync = EngineSync(
            api = CrownFoundryClient,
            matches = matchStore,
            preferences = enginePreferences
        )

        scope.launch {
            EngineStore.initialise(context, enginePreferences)
            EngineStore.setPendingUploads(matchStore.pendingUploads().size)
        }
    }

    /**
     * Push what was played offline and pull whatever the server has since trained.
     *
     * Fire-and-forget: called when the app comes forward and after a match ends. A player who
     * wants a definite answer uses the button in Settings, which awaits [synchronise].
     */
    fun synchroniseInBackground(playerId: String?, force: Boolean = false) {
        val engineSync = sync ?: return
        scope.launch {
            runCatching { engineSync.synchronise(playerId, force) }
        }
    }

    suspend fun synchronise(
        playerId: String?,
        force: Boolean = false
    ): Pair<EngineSync.UploadResult, EngineSync.Result>? = sync?.synchronise(playerId, force)

    suspend fun refresh(force: Boolean = false): EngineSync.Result? = sync?.refresh(force)

    suspend fun uploadOutbox(playerId: String?): EngineSync.UploadResult? =
        sync?.uploadOutbox(playerId)
}
