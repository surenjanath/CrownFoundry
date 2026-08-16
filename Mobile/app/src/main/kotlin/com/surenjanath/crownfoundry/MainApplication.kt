package com.surenjanath.crownfoundry

import android.app.Application
import androidx.core.content.edit
import com.surenjanath.crownfoundry.api.CrownFoundryClient
import com.surenjanath.crownfoundry.utils.backendUrlKey
import com.surenjanath.crownfoundry.utils.defaultBackendUrl
import com.surenjanath.crownfoundry.utils.playerIdKey
import com.surenjanath.crownfoundry.utils.preferences
import java.util.UUID

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // The AI adapts to one opponent, so the install needs an identity that outlives a match.
        with(preferences) {
            if (getString(playerIdKey, null) == null) {
                edit { putString(playerIdKey, UUID.randomUUID().toString()) }
            }

            CrownFoundryClient.baseUrl = getString(backendUrlKey, null) ?: defaultBackendUrl
        }
    }
}
