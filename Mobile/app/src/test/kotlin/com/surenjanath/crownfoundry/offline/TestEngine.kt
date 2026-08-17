package com.surenjanath.crownfoundry.offline

import android.content.SharedPreferences
import com.surenjanath.crownfoundry.engine.EngineArtifact
import com.surenjanath.crownfoundry.engine.EngineHeader
import com.surenjanath.crownfoundry.engine.FEATURE_SIZE
import com.surenjanath.crownfoundry.engine.QNetwork
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Test scaffolding for the offline package.
 *
 * The engine used here is a small randomised network rather than the shipped policy: these tests
 * are about the referee and the plumbing around it, and a fixture that has to be regenerated
 * whenever the backend trains is a fixture that rots. `:engine` owns the cross-language check that
 * the real weights load correctly.
 */
object TestEngine {

    /** A store with a readable, current engine installed in [directory]. */
    fun readyStore(directory: File, version: Int = 5): EngineStore = runBlocking {
        val preferences = EnginePreferences(FakeSharedPreferences())
        EngineStore.resetForTest()
        EngineStore.initialise(directory, preferences)

        val net = QNetwork(intArrayOf(FEATURE_SIZE, 24, 1)).apply { randomise(seed = 7) }
        val blob = EngineArtifact.write(
            net,
            EngineHeader(version = version, elo = 1250, gamesTrained = 30, baseVersion = version)
        )
        EngineStore.install(blob, preferences).getOrThrow()
        EngineStore
    }

    /** A store that has never seen the server. */
    fun missingStore(): EngineStore = runBlocking {
        val directory = File.createTempFile("crownfoundry", "empty").let {
            it.delete()
            it.mkdirs()
            it
        }
        EngineStore.resetForTest()
        EngineStore.initialise(directory, EnginePreferences(FakeSharedPreferences()))
        EngineStore
    }
}

/**
 * An in-memory [SharedPreferences]. Small enough to be obviously correct, and it keeps these tests
 * off Robolectric - which would otherwise be inflating an Android runtime to store six integers.
 */
class FakeSharedPreferences : SharedPreferences {

    private val values = HashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun contains(key: String?) = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearing = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals.add(key) }
        override fun clear() = apply { clearing = true }

        override fun commit(): Boolean {
            if (clearing) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
