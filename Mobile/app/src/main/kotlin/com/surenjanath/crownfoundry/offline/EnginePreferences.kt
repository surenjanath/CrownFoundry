package com.surenjanath.crownfoundry.offline

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.surenjanath.crownfoundry.utils.preferences

/**
 * The handful of scalars offline mode has to remember between launches.
 *
 * Deliberately a thin typed wrapper over the same `SharedPreferences` the rest of the app uses,
 * rather than a store of its own: these are settings, they are tiny, and the Settings screen edits
 * two of them directly.
 */
class EnginePreferences(private val store: SharedPreferences) {

    constructor(context: Context) : this(context.preferences)

    /** The newest policy version the server has advertised, or `-1` if it never has. */
    var lastServerVersion: Int?
        get() = store.getInt(KEY_SERVER_VERSION, -1).takeIf { it >= 0 }
        set(value) = store.edit { putInt(KEY_SERVER_VERSION, value ?: -1) }

    var lastServerElo: Int?
        get() = store.getInt(KEY_SERVER_ELO, -1).takeIf { it >= 0 }
        set(value) = store.edit { putInt(KEY_SERVER_ELO, value ?: -1) }

    var lastCheckedAt: Long
        get() = store.getLong(KEY_CHECKED_AT, 0)
        set(value) = store.edit { putLong(KEY_CHECKED_AT, value) }

    var lastDownloadedAt: Long
        get() = store.getLong(KEY_DOWNLOADED_AT, 0)
        set(value) = store.edit { putLong(KEY_DOWNLOADED_AT, value) }

    var lastTrainedAt: Long
        get() = store.getLong(KEY_TRAINED_AT, 0)
        set(value) = store.edit { putLong(KEY_TRAINED_AT, value) }

    var lastSyncedAt: Long
        get() = store.getLong(KEY_SYNCED_AT, 0)
        set(value) = store.edit { putLong(KEY_SYNCED_AT, value) }

    /** Keep the engine current without being asked. On by default; it is ~110 KB. */
    var autoUpdate: Boolean
        get() = store.getBoolean(autoUpdateEngineKey, true)
        set(value) = store.edit { putBoolean(autoUpdateEngineKey, value) }

    /**
     * Play against the on-device engine even when the referee is reachable.
     *
     * Off by default, because the server has the LLM commentary and the full training corpus. When
     * it is off the app still falls back to offline automatically the moment a call fails - this
     * setting is for the player who would rather not wait for a round trip at all.
     */
    var preferOffline: Boolean
        get() = store.getBoolean(preferOfflineKey, false)
        set(value) = store.edit { putBoolean(preferOfflineKey, value) }

    /** Fine-tune the local weights on games played offline. */
    var learnOnDevice: Boolean
        get() = store.getBoolean(learnOnDeviceKey, true)
        set(value) = store.edit { putBoolean(learnOnDeviceKey, value) }

    private companion object {
        const val KEY_SERVER_VERSION = "engineServerVersion"
        const val KEY_SERVER_ELO = "engineServerElo"
        const val KEY_CHECKED_AT = "engineCheckedAt"
        const val KEY_DOWNLOADED_AT = "engineDownloadedAt"
        const val KEY_TRAINED_AT = "engineTrainedAt"
        const val KEY_SYNCED_AT = "engineSyncedAt"
    }
}

// Exposed as constants because the Settings screen reads them through `rememberPreference`.
const val autoUpdateEngineKey = "autoUpdateEngine"
const val preferOfflineKey = "preferOffline"
const val learnOnDeviceKey = "learnOnDevice"
