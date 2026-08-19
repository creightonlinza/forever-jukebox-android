package com.foreverjukebox.app.ui

import android.app.Application
import android.os.PowerManager

/**
 * Keeps the CPU awake across a track-load pipeline stage. With the screen off and no
 * audio rendering, nothing else holds a wakelock (a media foreground service keeps the
 * process alive but not the CPU awake), so the kernel suspends within seconds and
 * freezes in-flight HTTP calls, MediaCodec decodes, and coroutine delays mid-load.
 * Every stage of a load — analysis fetch, cached decode, retry backoff, polling,
 * download — must run under this hold to make progress while pocketed.
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
