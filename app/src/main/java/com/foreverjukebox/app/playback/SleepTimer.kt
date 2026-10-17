package com.foreverjukebox.app.playback

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.foreverjukebox.app.ui.CastController
import com.foreverjukebox.app.ui.stopAllPlaybackTransports
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val SLEEP_TIMER_TICK_MS = 1_000L

data class SleepTimerStatus(
    val configuredDurationMs: Long? = null,
    val endRealtimeMs: Long? = null,
    val remainingMs: Long = 0L
) {
    val isActive: Boolean
        get() = endRealtimeMs != null && remainingMs > 0L
}

internal fun sleepTimerExpiryBroadcastActions(): List<String> {
    return listOf(
        PlaybackBroadcasts.ACTION_SLEEP_TIMER_EXPIRED,
        PlaybackBroadcasts.ACTION_CLOSE_FULLSCREEN
    )
}

/**
 * Counts down to [onExpired], publishing the remaining time each tick. Remaining time is
 * recomputed from [nowMs] on every tick, so a countdown held up while the CPU slept
 * catches up as soon as it runs again.
 */
internal class SleepTimerCountdown(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val onExpired: () -> Unit
) {
    private val _state = MutableStateFlow(SleepTimerStatus())
    val state: StateFlow<SleepTimerStatus> = _state
    private var job: Job? = null

    fun start(durationMs: Long) {
        job?.cancel()
        val endMs = nowMs() + durationMs
        _state.value = SleepTimerStatus(durationMs, endMs, durationMs)
        job = scope.launch {
            while (true) {
                val remainingMs = (endMs - nowMs()).coerceAtLeast(0L)
                _state.value = SleepTimerStatus(durationMs, endMs, remainingMs)
                if (remainingMs <= 0L) {
                    break
                }
                delay(min(SLEEP_TIMER_TICK_MS, remainingMs))
            }
            job = null
            _state.value = SleepTimerStatus()
            onExpired()
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        _state.value = SleepTimerStatus()
    }
}

/**
 * The app's sleep timer. It belongs to the process rather than the playback service: the
 * service's lifetime follows the media session, and Media3 lets it go after a pause.
 * Expiry stops every transport, stops the cast receiver when casting, and tells the UI.
 */
object SleepTimer {
    @Volatile
    private var appContext: Context? = null
    private val countdown = SleepTimerCountdown(
        scope = MainScope(),
        nowMs = SystemClock::elapsedRealtime,
        onExpired = { appContext?.let(::stopPlaybackForSleepTimer) }
    )

    val state: StateFlow<SleepTimerStatus> = countdown.state

    /** Starts the timer for [durationMs], or clears it when null or not positive. */
    fun set(context: Context, durationMs: Long?) {
        appContext = context.applicationContext
        if (durationMs != null && durationMs > 0L) {
            countdown.start(durationMs)
        } else {
            countdown.clear()
        }
    }

    private fun stopPlaybackForSleepTimer(context: Context) {
        stopAllPlaybackTransports(PlaybackControllerHolder.get(context))
        if (PlaybackSessionState.snapshot.surface is PlaybackSessionSurface.Cast) {
            CastController(context as Application).sendTransportCommand("stop")
        }
        sleepTimerExpiryBroadcastActions().forEach { action ->
            PlaybackBroadcasts.send(context, action)
        }
    }
}
