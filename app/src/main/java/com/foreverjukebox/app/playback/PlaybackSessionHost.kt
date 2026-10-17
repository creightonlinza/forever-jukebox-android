package com.foreverjukebox.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.foreverjukebox.app.AppLog
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException

/**
 * The UI layer's handle on the playback service. [show] publishes what the session should
 * present and keeps the service bound through a [MediaController] connection; [hide]
 * publishes an empty session and lets the service go. Media3 owns the foreground state
 * from there, promoting the service while playback is engaged.
 *
 * The connection is only a lifetime anchor: transport commands from the UI keep going to
 * [PlaybackController] directly. Callable from any thread; the connection itself is
 * created and released on the main thread.
 */
internal class PlaybackSessionHost(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var connectionWanted = false
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var lastConnectAttemptMs = 0L
    private var failureLogged = false
    private val applyConnection = Runnable { applyWantedConnection() }

    fun show(snapshot: PlaybackSessionSnapshot) {
        PlaybackSessionState.publish(snapshot)
        setConnectionWanted(true)
    }

    fun hide() {
        PlaybackSessionState.publish(PlaybackSessionSnapshot())
        setConnectionWanted(false)
    }

    private fun setConnectionWanted(wanted: Boolean) {
        connectionWanted = wanted
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyWantedConnection()
        } else {
            mainHandler.post(applyConnection)
        }
    }

    private fun applyWantedConnection() {
        val future = controllerFuture
        if (connectionWanted) {
            val now = SystemClock.elapsedRealtime()
            if (shouldConnect(future, now)) {
                future?.let(MediaController::releaseFuture)
                lastConnectAttemptMs = now
                controllerFuture = connect()
            }
        } else if (future != null) {
            controllerFuture = null
            MediaController.releaseFuture(future)
        }
    }

    // A connection that failed, or whose service went away, is replaced on a later show,
    // at most once per retry interval so a persistent failure doesn't rebind on every sync.
    private fun shouldConnect(future: ListenableFuture<MediaController>?, nowMs: Long): Boolean {
        if (future == null) {
            return true
        }
        val live = !future.isDone || runCatching { future.get().isConnected }.getOrDefault(false)
        return !live && nowMs - lastConnectAttemptMs >= RECONNECT_INTERVAL_MS
    }

    private fun connect(): ListenableFuture<MediaController> {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        future.addListener(
            { logConnectionFailure(future) },
            ContextCompat.getMainExecutor(appContext)
        )
        return future
    }

    private fun logConnectionFailure(future: ListenableFuture<MediaController>) {
        val failure = runCatching { future.get() }.exceptionOrNull()
        if (failure == null) {
            failureLogged = false
            return
        }
        // A cancelled future was released before it connected. Repeated failures are
        // logged once until a connection succeeds again.
        if (failure !is CancellationException && !failureLogged) {
            failureLogged = true
            val cause = (failure as? ExecutionException)?.cause ?: failure
            AppLog.warn(TAG, "Playback session connection failed", cause)
        }
    }

    private companion object {
        private const val TAG = "PlaybackSessionHost"
        private const val RECONNECT_INTERVAL_MS = 5_000L
    }
}
