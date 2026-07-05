package com.foreverjukebox.app.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.foreverjukebox.app.BuildConfig
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.AppPreferences

class ForeverJukeboxCastOptionsProvider : OptionsProvider {
    // CastContext reads the receiver app ID once per process here, so resolution must be
    // deterministic and mode-aware (see CastAppIdResolver.resolveForMode). Switching between Local
    // and Server modes at runtime may therefore require an app restart to re-pick the receiver.
    override fun getCastOptions(context: Context): CastOptions {
        val preferences = AppPreferences(context)
        val baseUrl = runBlocking { preferences.baseUrl.first() }
        // The play flavor has no Server mode, so it is always Local regardless of any persisted value.
        val mode = if (BuildConfig.SERVER_MODE_AVAILABLE) {
            runBlocking { preferences.appMode.first() }
        } else {
            AppMode.Local
        }
        val appId = CastAppIdResolver.resolveForMode(context, mode, baseUrl)
            ?: throw IllegalStateException(
                "No Cast receiver app ID configured for mode=$mode"
            )
        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setResumeSavedSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
