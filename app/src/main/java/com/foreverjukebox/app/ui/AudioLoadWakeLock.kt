package com.foreverjukebox.app.ui

import android.app.Application
import android.os.PowerManager

/**
 * Keeps the CPU awake across a track-load pipeline stage. With the screen off and no
 * audio rendering, nothing else holds a wakelock (a media foreground service keeps the
 * process alive but not the CPU awake), so the kernel suspends within seconds and
 * in-flight MediaCodec decodes fail outright, while HTTP calls and coroutine delays
 * freeze. Every stage of a load — analysis fetch, cached decode, retry backoff,
 * polling, download — must run under this hold to make progress while pocketed.
 *
 * Deep doze honors this hold without the battery whitelist only while the app's
 * process is at foreground-service importance or better; the playback service must
 * therefore stay up across track changes for the hold to mean anything. Both
 * together are what let a skip load in deep doze — on-device testing showed the
 * load failing with either one missing.
 */
interface AudioLoadHold {
    suspend fun <T> hold(block: suspend () -> T): T
}

class AudioLoadWakeLock(private val application: Application) : AudioLoadHold {
    // A fresh wakelock instance per hold: instances release independently and the CPU
    // stays awake while any is held, so overlapping/nested holds compose without
    // reference counting. The acquire timeout bounds battery cost if release is missed.
    override suspend fun <T> hold(block: suspend () -> T): T {
        val powerManager = application.getSystemService(PowerManager::class.java)
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        wakeLock.setReferenceCounted(false)
        return try {
            wakeLock.acquire(TIMEOUT_MS)
            block()
        } finally {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }

    private companion object {
        const val TAG = "ForeverJukebox:AudioLoad"
        const val TIMEOUT_MS = 10 * 60 * 1000L
    }
}
