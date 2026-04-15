package com.foreverjukebox.app.playback

import android.app.ActivityManager
import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.toColorInt
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.foreverjukebox.app.MainActivity
import com.foreverjukebox.app.R
import com.foreverjukebox.app.ui.CastController
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal enum class ForegroundServiceStopCommand {
    StopService,
    ClearNotificationKeepTimer
}

internal fun resolveForegroundServiceStopCommand(
    isSleepTimerActive: Boolean
): ForegroundServiceStopCommand {
    return if (isSleepTimerActive) {
        ForegroundServiceStopCommand.ClearNotificationKeepTimer
    } else {
        ForegroundServiceStopCommand.StopService
    }
}

internal fun sleepTimerExpiryBroadcastActions(): List<String> {
    return listOf(
        ForegroundPlaybackService.ACTION_SLEEP_TIMER_EXPIRED,
        ForegroundPlaybackService.ACTION_CLOSE_FULLSCREEN
    )
}

private object PlaybackServiceConstants {
    const val CHANNEL_ID = "fj_playback"
    const val NOTIFICATION_ID = 2001
    const val ACTION_START = "com.foreverjukebox.app.playback.START"
    const val ACTION_UPDATE = "com.foreverjukebox.app.playback.UPDATE"
    const val ACTION_TOGGLE = "com.foreverjukebox.app.playback.TOGGLE"
    const val ACTION_SET_SLEEP_TIMER = "com.foreverjukebox.app.playback.SET_SLEEP_TIMER"
    const val ACTION_CLEAR_NOTIFICATION_KEEP_TIMER =
        "com.foreverjukebox.app.playback.CLEAR_NOTIFICATION_KEEP_TIMER"
    const val ACTION_SLEEP_TIMER_EXPIRED = "com.foreverjukebox.app.playback.SLEEP_TIMER_EXPIRED"
    const val ACTION_PLAYBACK_STATE_CHANGED =
        "com.foreverjukebox.app.playback.PLAYBACK_STATE_CHANGED"
    const val ACTION_CLOSE_FULLSCREEN = "com.foreverjukebox.app.playback.CLOSE_FULLSCREEN"
    const val EXTRA_IS_CASTING = "com.foreverjukebox.app.playback.extra.IS_CASTING"
    const val EXTRA_CAST_IS_PLAYING = "com.foreverjukebox.app.playback.extra.CAST_IS_PLAYING"
    const val EXTRA_TRACK_TITLE = "com.foreverjukebox.app.playback.extra.TRACK_TITLE"
    const val EXTRA_TRACK_ARTIST = "com.foreverjukebox.app.playback.extra.TRACK_ARTIST"
    const val EXTRA_CAST_DEVICE_NAME = "com.foreverjukebox.app.playback.extra.CAST_DEVICE_NAME"
    const val EXTRA_SLEEP_TIMER_DURATION_MS = "com.foreverjukebox.app.playback.extra.SLEEP_TIMER_DURATION_MS"
    const val CAST_COMMAND_NAMESPACE = "urn:x-cast:com.foreverjukebox.app"
}

private const val NOTIFICATION_ACCENT = "#4AC7FF"
private const val DEFAULT_NOTIFICATION_TITLE = "The Forever Jukebox"
private const val CAST_FALLBACK_DEVICE_LABEL = "Other device"
private const val BLUETOOTH_DISCONNECT_WINDOW_MS = 3_000L

private enum class NotificationMode {
    Local,
    Cast
}

private enum class PlaybackAction {
    Play,
    Pause,
    Stop,
    Toggle
}

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

private data class PlaybackNotificationState(
    val mode: NotificationMode,
    val isPlaying: Boolean,
    val title: String,
    val artist: String,
    val castDeviceName: String?,
    val positionMs: Long = 0L,
    val durationMs: Long? = null
) {
    fun contentText(): String = artist

    fun subText(): String? {
        if (mode != NotificationMode.Cast) {
            return null
        }
        return castDeviceName?.takeIf { it.isNotBlank() } ?: CAST_FALLBACK_DEVICE_LABEL
    }
}

data class SleepTimerStatus(
    val configuredDurationMs: Long? = null,
    val endRealtimeMs: Long? = null,
    val remainingMs: Long = 0L
) {
    val isActive: Boolean
        get() = endRealtimeMs != null && remainingMs > 0L
}

class ForegroundPlaybackService : Service() {
    private lateinit var mediaSession: MediaSessionCompat
    private val castController by lazy { CastController(application as Application) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeNotificationState: PlaybackNotificationState? = null
    private var sleepTimerJob: Job? = null
    private var sleepTimerEndRealtimeMs: Long? = null
    private var hasStartedForeground = false
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
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                return
            }
            handleAudioBecomingNoisy()
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        mediaSession = MediaSessionCompat(this, "ForeverJukeboxPlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent?): Boolean {
                    val keyEvent = mediaButtonIntent?.let { intent ->
                        IntentCompat.getParcelableExtra(
                            intent,
                            Intent.EXTRA_KEY_EVENT,
                            KeyEvent::class.java
                        )
                    }
                        ?: return super.onMediaButtonEvent(mediaButtonIntent)
                    if (keyEvent.action != KeyEvent.ACTION_DOWN) {
                        return true
                    }
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            handlePlaybackAction(PlaybackAction.Toggle)
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            handlePlaybackAction(PlaybackAction.Play)
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            handlePlaybackAction(PlaybackAction.Pause)
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_STOP -> {
                            handlePlaybackAction(PlaybackAction.Stop)
                            return true
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }

                override fun onPlay() {
                    handlePlaybackAction(PlaybackAction.Play)
                }

                override fun onPause() {
                    handlePlaybackAction(PlaybackAction.Pause)
                }

                override fun onStop() {
                    handlePlaybackAction(PlaybackAction.Stop)
                }
            })
        }
        registerBluetoothRouteMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            PlaybackServiceConstants.ACTION_TOGGLE -> {
                if (intent.getBooleanExtra(PlaybackServiceConstants.EXTRA_IS_CASTING, false)) {
                    handleCastToggle()
                } else {
                    handlePlaybackAction(PlaybackAction.Toggle)
                }
            }
            PlaybackServiceConstants.ACTION_SET_SLEEP_TIMER -> {
                val durationMs = intent.getLongExtra(
                    PlaybackServiceConstants.EXTRA_SLEEP_TIMER_DURATION_MS,
                    0L
                )
                if (durationMs > 0L) {
                    startSleepTimer(durationMs)
                } else {
                    clearSleepTimer()
                }
            }
            PlaybackServiceConstants.ACTION_CLEAR_NOTIFICATION_KEEP_TIMER -> {
                clearPlaybackNotificationKeepTimer()
            }
            PlaybackServiceConstants.ACTION_START, PlaybackServiceConstants.ACTION_UPDATE -> {
                val castState = intent.toCastNotificationState()
                if (castState != null) {
                    updateNotification(castState)
                } else {
                    refreshNotificationForCurrentPlayback()
                }
            }
        }
        return START_STICKY
    }

    private fun refreshNotificationForCurrentPlayback() {
        val active = activeNotificationState
        if (active?.mode == NotificationMode.Cast) {
            updateNotification(active)
            return
        }
        val controller = PlaybackControllerHolder.get(this)
        updateNotification(buildLocalNotificationState(controller.isPlaying()))
    }

    private fun buildLocalNotificationState(isPlaying: Boolean): PlaybackNotificationState {
        val controller = PlaybackControllerHolder.get(this)
        val title = controller.getTrackTitle().orEmpty().ifBlank { DEFAULT_NOTIFICATION_TITLE }
        val artist = controller.getTrackArtist().orEmpty()
        val positionMs = controller.getPlaybackPositionMs().coerceAtLeast(0L)
        val durationMs = controller.getTrackDurationMs()?.coerceAtLeast(0L)
        return PlaybackNotificationState(
            mode = NotificationMode.Local,
            isPlaying = isPlaying,
            title = title,
            artist = artist,
            castDeviceName = null,
            positionMs = positionMs,
            durationMs = durationMs
        )
    }

    private fun Intent.toCastNotificationState(): PlaybackNotificationState? {
        if (!getBooleanExtra(PlaybackServiceConstants.EXTRA_IS_CASTING, false)) {
            return null
        }
        val title = getStringExtra(PlaybackServiceConstants.EXTRA_TRACK_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NOTIFICATION_TITLE
        val artist = getStringExtra(PlaybackServiceConstants.EXTRA_TRACK_ARTIST).orEmpty()
        val deviceName = getStringExtra(PlaybackServiceConstants.EXTRA_CAST_DEVICE_NAME)
        return PlaybackNotificationState(
            mode = NotificationMode.Cast,
            isPlaying = getBooleanExtra(PlaybackServiceConstants.EXTRA_CAST_IS_PLAYING, false),
            title = title,
            artist = artist,
            castDeviceName = deviceName
        )
    }

    private fun updateNotification(state: PlaybackNotificationState) {
        activeNotificationState = state
        createChannel()
        val contentText = state.contentText()
        val artwork = if (state.mode == NotificationMode.Local) {
            loadNotificationArtwork()
        } else {
            null
        }
        updateMediaSession(state, artwork)

        val toggleIntent = Intent(this, ForegroundPlaybackService::class.java).apply {
            action = PlaybackServiceConstants.ACTION_TOGGLE
            putExtra(PlaybackServiceConstants.EXTRA_IS_CASTING, state.mode == NotificationMode.Cast)
            if (state.mode == NotificationMode.Cast) {
                putExtra(PlaybackServiceConstants.EXTRA_CAST_IS_PLAYING, state.isPlaying)
                putExtra(PlaybackServiceConstants.EXTRA_TRACK_TITLE, state.title)
                putExtra(PlaybackServiceConstants.EXTRA_TRACK_ARTIST, state.artist)
                putExtra(PlaybackServiceConstants.EXTRA_CAST_DEVICE_NAME, state.castDeviceName)
            }
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_LISTEN_TAB, true)
        }
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionIconRes = when (state.mode) {
            NotificationMode.Cast -> if (state.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            NotificationMode.Local -> if (state.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        }
        val actionLabel = when (state.mode) {
            NotificationMode.Cast -> if (state.isPlaying) "Pause" else "Play"
            NotificationMode.Local -> if (state.isPlaying) "Pause" else "Play"
        }
        val actionIcon = tintedIcon(actionIconRes, NOTIFICATION_ACCENT.toColorInt())

        val builder = NotificationCompat.Builder(this, PlaybackServiceConstants.CHANNEL_ID)
            .setContentTitle(state.title)
            .setSmallIcon(R.drawable.ic_all_inclusive)
            .setColor(NOTIFICATION_ACCENT.toColorInt())
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityPendingIntent)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    actionIcon,
                    actionLabel,
                    togglePendingIntent
                ).build()
            )

        if (contentText.isNotBlank()) {
            builder.setContentText(contentText)
        }
        state.subText()?.let { builder.setSubText(it) }

        if (state.mode == NotificationMode.Local) {
            if (artwork != null) {
                builder.setLargeIcon(artwork)
            }
            builder.setProgress(
                state.durationMs?.toInt() ?: 0,
                state.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                state.durationMs == null
            )
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
        }

        val notification: Notification = builder.build()
        if (hasStartedForeground) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(PlaybackServiceConstants.NOTIFICATION_ID, notification)
        } else {
            try {
                startForeground(PlaybackServiceConstants.NOTIFICATION_ID, notification)
                hasStartedForeground = true
            } catch (error: ForegroundServiceStartNotAllowedException) {
                // Android can reject entering foreground if the app is background-restricted.
                // Avoid crashing the process; drop this notification update.
                Log.w(
                    TAG,
                    "Foreground start denied for playback notification update.",
                    error
                )
                activeNotificationState = null
                hasStartedForeground = false
                stopSelf()
            }
        }
    }

    private fun tintedIcon(resId: Int, color: Int): IconCompat {
        val source = BitmapFactory.decodeResource(resources, resId)
        val bitmap = createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun updateMediaSession(
        notificationState: PlaybackNotificationState,
        artwork: Bitmap?
    ) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE
            )
            .setState(
                if (notificationState.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                notificationState.positionMs,
                if (notificationState.isPlaying) 1f else 0f
            )
            .build()
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, notificationState.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, notificationState.artist)
        if (notificationState.durationMs != null) {
            metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, notificationState.durationMs)
        }
        if (artwork != null) {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artwork)
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork)
        }
        mediaSession.setPlaybackState(playbackState)
        mediaSession.setMetadata(metadata.build())
        mediaSession.isActive = true
    }

    private fun handlePlaybackAction(action: PlaybackAction) {
        val activeState = activeNotificationState
        val targetPlayState = when (action) {
            PlaybackAction.Play -> true
            PlaybackAction.Pause,
            PlaybackAction.Stop -> false
            PlaybackAction.Toggle -> !(activeState?.isPlaying ?: PlaybackControllerHolder.get(this).isPlaying())
        }
        if (activeState?.mode == NotificationMode.Cast) {
            val command = when (action) {
                PlaybackAction.Play -> "play"
                PlaybackAction.Pause -> "pause"
                PlaybackAction.Stop -> "stop"
                PlaybackAction.Toggle -> if (activeState.isPlaying) "pause" else "play"
            }
            val sent = castController.sendCommand(
                PlaybackServiceConstants.CAST_COMMAND_NAMESPACE,
                command
            )
            if (sent) {
                updateNotification(activeState.copy(isPlaying = targetPlayState))
            } else {
                activeNotificationState = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val controller = PlaybackControllerHolder.get(this)
        val autocanonizer = controller.autocanonizer
        val autocanonizerRunning = autocanonizer.isRunning()
        val autocanonizerPaused = autocanonizer.isPaused()
        when (action) {
            PlaybackAction.Play -> {
                if (autocanonizerRunning) {
                    updateNotification(buildLocalNotificationState(true))
                } else if (autocanonizerPaused) {
                    val resumed = autocanonizer.resume()
                    if (resumed) {
                        controller.startExternalPlayback(resetTimers = false)
                    }
                    updateNotification(buildLocalNotificationState(resumed))
                } else if (!controller.isPlaying()) {
                    val running = controller.playOrResumePlayback()
                    updateNotification(buildLocalNotificationState(running))
                } else {
                    updateNotification(buildLocalNotificationState(true))
                }
            }
            PlaybackAction.Pause -> {
                if (autocanonizerRunning) {
                    autocanonizer.pause()
                    controller.pauseExternalPlayback()
                } else if (controller.isPlaying()) {
                    controller.pausePlayback()
                }
                updateNotification(buildLocalNotificationState(false))
            }
            PlaybackAction.Stop -> {
                controller.stopPlayback()
                autocanonizer.stop()
                controller.stopExternalPlayback()
                updateNotification(buildLocalNotificationState(false))
            }
            PlaybackAction.Toggle -> {
                if (autocanonizerRunning) {
                    autocanonizer.pause()
                    controller.pauseExternalPlayback()
                    updateNotification(buildLocalNotificationState(false))
                } else if (autocanonizerPaused) {
                    val resumed = autocanonizer.resume()
                    if (resumed) {
                        controller.startExternalPlayback(resetTimers = false)
                    }
                    updateNotification(buildLocalNotificationState(resumed))
                } else if (controller.isPlaying()) {
                    controller.pausePlayback()
                    updateNotification(buildLocalNotificationState(false))
                } else {
                    val running = controller.playOrResumePlayback()
                    updateNotification(buildLocalNotificationState(running))
                }
            }
        }
        broadcastLocalPlaybackStateChanged()
    }

    private fun handleCastToggle() {
        handlePlaybackAction(PlaybackAction.Toggle)
    }

    private fun registerBluetoothRouteMonitoring() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        manager.registerAudioDeviceCallback(bluetoothAudioDeviceCallback, null)
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            this,
            audioBecomingNoisyReceiver,
            filter,
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
        val now = SystemClock.elapsedRealtime()
        val hasRecentDisconnect = hasRecentBluetoothDisconnect(
            nowElapsedMs = now,
            disconnectElapsedMs = lastBluetoothOutputDisconnectElapsedMs,
            windowMs = BLUETOOTH_DISCONNECT_WINDOW_MS
        )
        // Consume the removal signal so unrelated later noisy events do not auto-pause.
        lastBluetoothOutputDisconnectElapsedMs = null
        val isLocalPlayback = activeNotificationState?.mode != NotificationMode.Cast
        val isPlaybackRunning = PlaybackControllerHolder.get(this).isPlaying()
        if (
            !shouldAutoPauseForBluetoothDisconnect(
                isLocalPlayback = isLocalPlayback,
                isPlaybackRunning = isPlaybackRunning,
                hasRecentBluetoothDisconnect = hasRecentDisconnect
            )
        ) {
            return
        }
        handlePlaybackAction(PlaybackAction.Pause)
    }

    private fun broadcastLocalPlaybackStateChanged() {
        sendBroadcast(Intent(PlaybackServiceConstants.ACTION_PLAYBACK_STATE_CHANGED).apply {
            setPackage(packageName)
        })
    }

    private fun clearPlaybackNotificationKeepTimer() {
        activeNotificationState = null
        if (hasStartedForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            hasStartedForeground = false
        } else {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(PlaybackServiceConstants.NOTIFICATION_ID)
        }
        mediaSession.isActive = false
    }

    private fun startSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        val endRealtime = SystemClock.elapsedRealtime() + durationMs
        sleepTimerEndRealtimeMs = endRealtime
        publishSleepTimerState(
            configuredDurationMs = durationMs,
            endRealtimeMs = endRealtime,
            remainingMs = durationMs
        )
        sleepTimerJob = serviceScope.launch {
            while (isActive) {
                val remainingMs = (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                publishSleepTimerState(
                    configuredDurationMs = durationMs,
                    endRealtimeMs = endRealtime,
                    remainingMs = remainingMs
                )
                if (remainingMs <= 0L) {
                    break
                }
                delay(min(1000L, remainingMs))
            }
            if (sleepTimerEndRealtimeMs == endRealtime) {
                handleSleepTimerExpired()
            }
        }
    }

    private fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndRealtimeMs = null
        publishSleepTimerState(
            configuredDurationMs = null,
            endRealtimeMs = null,
            remainingMs = 0L
        )
    }

    private fun publishSleepTimerState(
        configuredDurationMs: Long?,
        endRealtimeMs: Long?,
        remainingMs: Long
    ) {
        _sleepTimerState.value = SleepTimerStatus(
            configuredDurationMs = configuredDurationMs,
            endRealtimeMs = endRealtimeMs,
            remainingMs = remainingMs
        )
    }

    private fun handleSleepTimerExpired() {
        val activeMode = activeNotificationState?.mode
        clearSleepTimer()
        val controller = PlaybackControllerHolder.get(this)
        controller.stopPlayback()
        controller.autocanonizer.stop()
        controller.stopExternalPlayback()
        if (activeMode == NotificationMode.Cast) {
            castController.sendCommand(PlaybackServiceConstants.CAST_COMMAND_NAMESPACE, "stop")
            activeNotificationState?.let { state ->
                updateNotification(state.copy(isPlaying = false))
            } ?: refreshNotificationForCurrentPlayback()
        } else if (activeMode == NotificationMode.Local) {
            refreshNotificationForCurrentPlayback()
        }
        sleepTimerExpiryBroadcastActions().forEach { action ->
            sendBroadcast(Intent(action).apply {
                setPackage(packageName)
            })
        }
    }

    override fun onDestroy() {
        activeNotificationState = null
        isRunning = false
        hasStartedForeground = false
        unregisterBluetoothRouteMonitoring()
        clearSleepTimer()
        serviceScope.cancel()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User explicitly removed the app task; tear down playback notification/service.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(PlaybackServiceConstants.CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            PlaybackServiceConstants.CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun loadNotificationArtwork(): Bitmap? {
        val drawable = AppCompatResources.getDrawable(this, R.drawable.notification_background) ?: return null
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 512
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 512
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        private const val TAG = "ForegroundPlaybackSvc"
        @Volatile
        private var isRunning: Boolean = false
        private val _sleepTimerState = MutableStateFlow(SleepTimerStatus())
        val sleepTimerState: StateFlow<SleepTimerStatus> = _sleepTimerState
        const val ACTION_SLEEP_TIMER_EXPIRED: String =
            PlaybackServiceConstants.ACTION_SLEEP_TIMER_EXPIRED
        const val ACTION_PLAYBACK_STATE_CHANGED: String =
            PlaybackServiceConstants.ACTION_PLAYBACK_STATE_CHANGED
        const val ACTION_CLOSE_FULLSCREEN: String =
            PlaybackServiceConstants.ACTION_CLOSE_FULLSCREEN

        fun start(context: Context) {
            val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_START
            }
            if (isRunning) {
                context.startService(intent)
            } else if (canStartForegroundService(context)) {
                context.startForegroundService(intent)
            }
        }

        fun update(context: Context) {
            val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_UPDATE
            }
            if (isRunning) {
                context.startService(intent)
            } else if (canStartForegroundService(context)) {
                context.startForegroundService(intent)
            }
        }

        fun setSleepTimer(context: Context, durationMs: Long?) {
            val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_SET_SLEEP_TIMER
                putExtra(
                    PlaybackServiceConstants.EXTRA_SLEEP_TIMER_DURATION_MS,
                    durationMs ?: 0L
                )
            }
            context.startService(intent)
        }

        fun updateCast(
            context: Context,
            isPlaying: Boolean,
            title: String?,
            artist: String?,
            deviceName: String?
        ) {
            val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_UPDATE
                putExtra(PlaybackServiceConstants.EXTRA_IS_CASTING, true)
                putExtra(PlaybackServiceConstants.EXTRA_CAST_IS_PLAYING, isPlaying)
                putExtra(PlaybackServiceConstants.EXTRA_TRACK_TITLE, title)
                putExtra(PlaybackServiceConstants.EXTRA_TRACK_ARTIST, artist)
                putExtra(PlaybackServiceConstants.EXTRA_CAST_DEVICE_NAME, deviceName)
            }
            if (isRunning) {
                context.startService(intent)
            } else if (canStartForegroundService(context)) {
                context.startForegroundService(intent)
            }
        }

        fun stop(context: Context) {
            when (resolveForegroundServiceStopCommand(_sleepTimerState.value.isActive)) {
                ForegroundServiceStopCommand.ClearNotificationKeepTimer -> {
                    val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                        action = PlaybackServiceConstants.ACTION_CLEAR_NOTIFICATION_KEEP_TIMER
                    }
                    context.startService(intent)
                }
                ForegroundServiceStopCommand.StopService -> {
                    context.stopService(Intent(context, ForegroundPlaybackService::class.java))
                }
            }
        }

        private fun canStartForegroundService(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                return true
            }
            val appState = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(appState)
            return appState.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        }
    }
}
