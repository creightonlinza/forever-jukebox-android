package com.foreverjukebox.app.ui

import com.foreverjukebox.app.cast.CastRelayClient
import com.foreverjukebox.app.data.AppMode

/**
 * Whether the Cast button should be enabled. Both modes cast through the same relay receiver, so the
 * only requirements are a chosen mode and a configured relay (app ID + base URL, both compiled-in
 * BuildConfig values).
 */
fun resolveCastEnabled(mode: AppMode?, relayConfigured: Boolean): Boolean =
    mode != null && relayConfigured

/**
 * A local audio file that exceeds the relay's audio cap cannot be cast (it would be rejected with
 * `413`), so the sender pre-checks and blocks it before uploading. Unknown size (`null`) is allowed
 * through — the relay enforces the cap as a backstop.
 */
fun isLocalCastFileTooLarge(sizeBytes: Long?): Boolean =
    sizeBytes != null && sizeBytes > CastRelayClient.MAX_AUDIO_BYTES
