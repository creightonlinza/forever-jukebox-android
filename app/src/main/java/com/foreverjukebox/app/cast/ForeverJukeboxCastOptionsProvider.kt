package com.foreverjukebox.app.cast

import android.content.Context
import com.foreverjukebox.app.BuildConfig
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

class ForeverJukeboxCastOptionsProvider : OptionsProvider {
    // Both Local and Server modes cast to the same relay receiver, so the app ID is a compile-time
    // constant (per-flavor, since the Cast console allows one sender package per receiver app id).
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(BuildConfig.RELAY_CAST_APP_ID)
            .setResumeSavedSession(true)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
        return null
    }
}
