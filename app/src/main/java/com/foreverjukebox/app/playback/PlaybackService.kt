package com.foreverjukebox.app.playback

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.foreverjukebox.app.AppLog
import com.foreverjukebox.app.MainActivity
import com.foreverjukebox.app.ProcessExitDiagnostics
import com.foreverjukebox.app.R
import com.foreverjukebox.app.ui.CastController
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val NOTIFICATION_CHANNEL_ID = "fj_playback"
private const val NOTIFICATION_ID = 2001
private const val SESSION_ID = "ForeverJukeboxPlayback"
private const val BLUETOOTH_DISCONNECT_WINDOW_MS = 3_000L

internal fun isBluetoothOutputDeviceType(type: Int): Boolean {
    return when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> true
        else -> false
    }
}

internal fun hasRecentBluetoothDisconnect(
    nowElapsedMs: Long,
    disconnectElapsedMs: Long?,
    windowMs: Long
): Boolean {
    val disconnectedAt = disconnectElapsedMs ?: return false
    if (windowMs < 0L) return false
    val elapsed = nowElapsedMs - disconnectedAt
    return elapsed in 0L..windowMs
}

internal fun shouldAutoPauseForBluetoothDisconnect(
    isLocalPlayback: Boolean,
    isPlaybackRunning: Boolean,
    hasRecentBluetoothDisconnect: Boolean
): Boolean {
    return isLocalPlayback && isPlaybackRunning && hasRecentBluetoothDisconnect
}

internal enum class MediaButtonStartAction {
    /** Media3's own handling is enough. */
    None,

    /** Put the session's notification in the foreground now, whatever the press does. */
    PromoteSession,

    /** Enter and leave the foreground with a placeholder, then stop: nothing to control. */
    ShutDown
}

/**
 * Covers the media-button starts that arrive as startForegroundService before API 33,
 * where Media3 only promotes once playback engages. The notification's play button is one:
 * a press that cannot start playback (audio focus denied, a retry, nothing loaded) would
 * leave the start unmet. Before API 31 the session's stored media-button target is this
 * service, so a key after process death starts it with no session to show.
 */
internal fun resolveMediaButtonStart(
    sdkInt: Int,
    sessionVisible: Boolean,
    keyCode: Int?
): MediaButtonStartAction {
    // Not gated on the service already being in the foreground: Media3 can leave it
    // without notifying the service, and re-promoting a foreground service is harmless.
    if (keyCode == null || sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        return MediaButtonStartAction.None
    }
    if (!sessionVisible) {
        return if (sdkInt < Build.VERSION_CODES.S) {
            MediaButtonStartAction.ShutDown
        } else {
            MediaButtonStartAction.None
        }
    }
    val isPlayKey = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    return if (isPlayKey) MediaButtonStartAction.PromoteSession else MediaButtonStartAction.None
}

/**
 * Hosts the media session for playback controls in the notification, on the lock screen
 * and from media keys. Media3 owns the notification and the foreground state, driven by
 * [JukeboxPlayer]'s state. The service lives while [PlaybackSessionHost] keeps it bound
 * and stops itself once the UI publishes an empty session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val serviceScope = MainScope()
    private val castController by lazy { CastController(application as Application) }
    private var session: MediaSession? = null
    private var jukeboxPlayer: JukeboxPlayer? = null
    private var foregroundRequired = false
    private var foregroundStartPending = false
    private var audioManager: AudioManager? = null
    private var bluetoothRouteMonitoringRegistered = false

    @Volatile
    private var lastBluetoothOutputDisconnectElapsedMs: Long? = null
    private val bluetoothAudioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (removedDevices.any { device -> isBluetoothOutputDeviceType(device.type) }) {
                lastBluetoothOutputDisconnectElapsedMs = SystemClock.elapsedRealtime()
            }
        }
    }
    private val audioBecomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                handleAudioBecomingNoisy()
            }
        }
    }

    private val sessionEvents = object : JukeboxSessionEvents {
        override fun retryFailedLoad() {
            PlaybackBroadcasts.send(this@PlaybackService, PlaybackBroadcasts.ACTION_RETRY_FAILED_LOAD)
        }

        override fun skipToPrevious() {
            PlaybackBroadcasts.send(this@PlaybackService, PlaybackBroadcasts.ACTION_PLAYLIST_PREVIOUS)
        }

        override fun skipToNext() {
            PlaybackBroadcasts.send(this@PlaybackService, PlaybackBroadcasts.ACTION_PLAYLIST_NEXT)
        }

        override fun localPlaybackStateChanged() {
            PlaybackBroadcasts.send(
                this@PlaybackService,
                PlaybackBroadcasts.ACTION_PLAYBACK_STATE_CHANGED
            )
        }

        override fun sendCastCommand(command: String): Boolean {
            return castController.sendTransportCommand(command)
        }

        override fun stopSession() {
            PlaybackSessionState.publish(PlaybackSessionSnapshot())
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessExitDiagnostics.logPreviousExitOnce(this)
        AppLog.info(TAG, "Service created")
        val controller = PlaybackControllerHolder.get(this)
        val player = JukeboxPlayer(
            looper = Looper.getMainLooper(),
            readLocalStatus = {
                LocalPlaybackStatus(
                    isPlaying = controller.isPlaying(),
                    isPaused = controller.isPaused(),
                    isWaitingForFocus = controller.isWaitingForFocus(),
                    title = controller.getTrackTitle(),
                    artist = controller.getTrackArtist(),
                    audioMode = controller.player.getJukeboxAudioMode(),
                    durationMs = controller.getTrackDurationMs()
                )
            },
            readPositionMs = controller::getPlaybackPositionMs,
            commandHandler = JukeboxCommandHandler(
                target = PlaybackControllerTransportTarget(controller),
                events = sessionEvents,
                warn = { message -> AppLog.warn(TAG, message) }
            ),
            artworkData = loadArtworkData()
        )
        jukeboxPlayer = player
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Below API 33 the notification's elapsed-time chronometer is anchored when the
            // notification is built, and Media3 does not rebuild it for position jumps.
            player.addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    triggerNotificationUpdate()
                }
            })
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .setChannelName(R.string.playback_notification_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_all_inclusive) }
        )
        // Idle covers a loaded track that is not started yet; see resolveJukeboxSessionSpec.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                logForegroundStartDenied()
            }
        })
        session = MediaSession.Builder(
            applicationContext.playbackAttributionContext(),
            JukeboxSessionPlayer(player)
        )
            .setId(SESSION_ID)
            .setSessionActivity(listenTabPendingIntent())
            .build()
        serviceScope.launch {
            PlaybackSessionState.published.collect { published ->
                player.publish(published)
                if (published.snapshot.surface == PlaybackSessionSurface.Hidden) {
                    stopSelf()
                }
            }
        }
        registerBluetoothRouteMonitoring()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keyCode = intent
            ?.takeIf { it.action == Intent.ACTION_MEDIA_BUTTON }
            ?.let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) }
            ?.keyCode
        val action = resolveMediaButtonStart(
            sdkInt = Build.VERSION.SDK_INT,
            sessionVisible = PlaybackSessionState.snapshot.surface != PlaybackSessionSurface.Hidden,
            keyCode = keyCode
        )
        val result = super.onStartCommand(intent, flags, startId)
        when (action) {
            MediaButtonStartAction.None -> Unit
            MediaButtonStartAction.PromoteSession -> {
                foregroundStartPending = true
                triggerNotificationUpdate()
                // Consumed by the update above when it ran; never carried to a later one.
                foregroundStartPending = false
            }
            MediaButtonStartAction.ShutDown -> shutDownUnservedStart()
        }
        return result
    }

    // ServiceCompat ignores the type below API 29.
    @SuppressLint("InlinedApi")
    private fun shutDownUnservedStart() {
        AppLog.info(TAG, "Media button started the service with nothing loaded; stopping")
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(
                NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
                .setName(getString(R.string.playback_notification_channel_name))
                .build()
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_all_inclusive)
            .setContentTitle(DEFAULT_NOTIFICATION_TITLE)
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onUpdateNotificationAsync(
        session: MediaSession,
        startInForegroundRequired: Boolean
    ): ListenableFuture<Void?> {
        val foreground = startInForegroundRequired || foregroundStartPending
        foregroundStartPending = false
        if (foreground && !foregroundRequired) {
            AppLog.info(TAG, "Entering foreground")
            foregroundStartDenied = false
        }
        foregroundRequired = foreground
        return super.onUpdateNotificationAsync(session, foreground)
    }

    override fun onDestroy() {
        AppLog.info(TAG, "Service destroyed")
        serviceScope.cancel()
        unregisterBluetoothRouteMonitoring()
        session?.let { activeSession ->
            activeSession.player.release()
            activeSession.release()
        }
        session = null
        jukeboxPlayer = null
        super.onDestroy()
    }

    private fun listenTabPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_LISTEN_TAB, true)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // The artwork is a WebP in drawable-nodpi, packaged as-is, so its bytes can go to the
    // session without a decode and re-encode.
    @SuppressLint("ResourceType")
    private fun loadArtworkData(): ByteArray? {
        return runCatching {
            resources.openRawResource(R.drawable.notification_background).use { it.readBytes() }
        }.onFailure { error ->
            AppLog.warn(TAG, "Notification artwork unavailable", error)
        }.getOrNull()
    }

    private fun registerBluetoothRouteMonitoring() {
        val manager = applicationContext.playbackAttributionContext()
            .getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        manager.registerAudioDeviceCallback(bluetoothAudioDeviceCallback, null)
        ContextCompat.registerReceiver(
            this,
            audioBecomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bluetoothRouteMonitoringRegistered = true
    }

    private fun unregisterBluetoothRouteMonitoring() {
        if (!bluetoothRouteMonitoringRegistered) {
            return
        }
        audioManager?.unregisterAudioDeviceCallback(bluetoothAudioDeviceCallback)
        runCatching { unregisterReceiver(audioBecomingNoisyReceiver) }
        bluetoothRouteMonitoringRegistered = false
        audioManager = null
    }

    private fun handleAudioBecomingNoisy() {
        val player = jukeboxPlayer ?: return
        val hasRecentDisconnect = hasRecentBluetoothDisconnect(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            disconnectElapsedMs = lastBluetoothOutputDisconnectElapsedMs,
            windowMs = BLUETOOTH_DISCONNECT_WINDOW_MS
        )
        // Consume the removal signal so unrelated later noisy events do not auto-pause.
        lastBluetoothOutputDisconnectElapsedMs = null
        if (
            shouldAutoPauseForBluetoothDisconnect(
                isLocalPlayback = player.surface !is PlaybackSessionSurface.Cast,
                isPlaybackRunning = PlaybackControllerHolder.get(this).isPlaying(),
                hasRecentBluetoothDisconnect = hasRecentDisconnect
            )
        ) {
            player.pauseLocalPlayback()
        }
    }

    private companion object {
        private const val TAG = "PlaybackService"

        @Volatile
        private var foregroundStartDenied = false

        // Media3 retries the promotion on every state change, so a denial is logged once
        // per foreground entry rather than on each attempt.
        private fun logForegroundStartDenied() {
            if (!foregroundStartDenied) {
                AppLog.warn(TAG, "Foreground service start denied; continuing without notification.")
            }
            foregroundStartDenied = true
        }
    }
}
