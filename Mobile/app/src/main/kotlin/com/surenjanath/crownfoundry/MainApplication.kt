package com.surenjanath.crownfoundry

import android.app.Application
import androidx.core.content.edit
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.offline.Offline
import com.surenjanath.crownfoundry.utils.backendUrlKey
import com.surenjanath.crownfoundry.utils.effectiveBackendUrl
import com.surenjanath.crownfoundry.utils.playerIdKey
import com.surenjanath.crownfoundry.utils.preferences
import java.util.UUID

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // The AI adapts to one opponent, so the install needs an identity that outlives a match.
        val playerId = with(preferences) {
            val existing = getString(playerIdKey, null)
                ?: UUID.randomUUID().toString().also { edit { putString(playerIdKey, it) } }

            // Left at its own default when this build ships without a server: pointing the
            // client at a placeholder would have every screen resolve a hostname that is not
            // meant to exist. Offline.backendAvailable is what stops the calls being made.
            effectiveBackendUrl(getString(backendUrlKey, null))?.let {
                CrownFoundryClient.baseUrl = it
            }
            existing
        }

        // Read the installed engine off disk and work out whether it is current. Both happen on a
        // background scope, so a cold start is not waiting on file IO or on the referee answering.
        Offline.initialise(this)
        Offline.synchroniseInBackground(playerId)
    }
}
