package com.foreverjukebox.app.ui

import com.foreverjukebox.app.cast.CastUploadClient
import com.foreverjukebox.app.data.AppMode

/**
 * Whether the Cast button should be enabled for the current mode. Server mode requires a resolved
 * server receiver app ID; Local mode requires the relay to be configured (app ID + base URL). This
 * replaces the old `mode == AppMode.Server && !serverAppId.isNullOrBlank()` rule at the five
 * `castEnabled` sites in [MainViewModel].
 */
fun resolveCastEnabled(mode: AppMode?, serverAppId: String?, relayConfigured: Boolean): Boolean =
    when (mode) {
        AppMode.Server -> !serverAppId.isNullOrBlank()
        AppMode.Local -> relayConfigured
        null -> false
    }

/**
 * A local audio file that exceeds the relay's audio cap cannot be cast (it would be rejected with
 * `413`), so the sender pre-checks and blocks it before uploading. Unknown size (`null`) is allowed
 * through — the relay enforces the cap as a backstop.
 */
fun isLocalCastFileTooLarge(sizeBytes: Long?): Boolean =
    sizeBytes != null && sizeBytes > CastUploadClient.MAX_AUDIO_BYTES
