package com.foreverjukebox.app.playback

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
import android.graphics.Canvas
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
import com.foreverjukebox.app.ui.JukeboxAudioMode
import com.foreverjukebox.app.AppLog
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

internal fun mediaSessionPlaybackActions(): Long {
    return PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_STOP or
        PlaybackStateCompat.ACTION_PLAY_PAUSE or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT
}

internal enum class PlaybackNotificationActionSlot {
    Previous,
    Toggle,
    Next
}

internal fun playbackNotificationActionSlots(
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    isLoading: Boolean = false,
    isLoadFailed: Boolean = false
): List<PlaybackNotificationActionSlot> {
    if (isLoading) {
        return emptyList()
    }
    if (isLoadFailed) {
        return listOf(PlaybackNotificationActionSlot.Toggle)
    }
    val actions = mutableListOf<PlaybackNotificationActionSlot>()
    if (canSkipPrevious) {
        actions += PlaybackNotificationActionSlot.Previous
    }
    actions += PlaybackNotificationActionSlot.Toggle
    if (canSkipNext) {
        actions += PlaybackNotificationActionSlot.Next
    }
    return actions
}

internal fun compactActionIndices(actionCount: Int): IntArray {
    val visibleCount = actionCount.coerceIn(0, 3)
    return IntArray(visibleCount) { it }
}

internal fun loadingNotificationProgressBucket(progress: Int?): Int? {
    val safeProgress = progress?.coerceIn(0, 100) ?: return null
    val bucket = (safeProgress / 10) * 10
    return bucket.takeIf { it >= 10 }
}

internal fun loadingNotificationTitle(progressBucket: Int?): String {
    return progressBucket?.let { "Loading - $it%" } ?: LOADING_NOTIFICATION_TITLE
}

internal fun localPlaybackNotificationTitle(
    baseTitle: String?,
    audioMode: JukeboxAudioMode,
    isLoading: Boolean,
    loadingProgressBucket: Int?,
    isLoadFailed: Boolean = false
): String {
    if (isLoadFailed) {
        return LOAD_FAILED_NOTIFICATION_TITLE
    }
    if (isLoading) {
        return loadingNotificationTitle(loadingProgressBucket)
    }
    val title = baseTitle.orEmpty()
    return when {
        title.isBlank() -> DEFAULT_NOTIFICATION_TITLE
        audioMode != JukeboxAudioMode.Off -> "$title (${audioMode.wireValue})"
        else -> title
    }
}

internal fun localPlaybackNotificationArtist(
    baseArtist: String?,
    isLoading: Boolean,
    isLoadFailed: Boolean = false
): String {
    return if (isLoading || isLoadFailed) {
        DEFAULT_NOTIFICATION_TITLE
    } else {
        baseArtist.orEmpty()
    }
}

private object PlaybackServiceConstants {
    const val CHANNEL_ID = "fj_playback"
    const val NOTIFICATION_ID = 2001
    const val ACTION_START = "com.foreverjukebox.app.playback.START"
    const val ACTION_UPDATE = "com.foreverjukebox.app.playback.UPDATE"
    const val ACTION_STOP = "com.foreverjukebox.app.playback.STOP"
    const val ACTION_TOGGLE = "com.foreverjukebox.app.playback.TOGGLE"
    const val ACTION_SET_SLEEP_TIMER = "com.foreverjukebox.app.playback.SET_SLEEP_TIMER"
    const val ACTION_CLEAR_NOTIFICATION_KEEP_TIMER =
        "com.foreverjukebox.app.playback.CLEAR_NOTIFICATION_KEEP_TIMER"
    const val ACTION_SLEEP_TIMER_EXPIRED = "com.foreverjukebox.app.playback.SLEEP_TIMER_EXPIRED"
    const val ACTION_PLAYBACK_STATE_CHANGED =
        "com.foreverjukebox.app.playback.PLAYBACK_STATE_CHANGED"
    const val ACTION_PLAYLIST_PREVIOUS = "com.foreverjukebox.app.playback.PLAYLIST_PREVIOUS"
    const val ACTION_PLAYLIST_NEXT = "com.foreverjukebox.app.playback.PLAYLIST_NEXT"
    const val ACTION_CLOSE_FULLSCREEN = "com.foreverjukebox.app.playback.CLOSE_FULLSCREEN"
    const val ACTION_RETRY_FAILED_LOAD = "com.foreverjukebox.app.playback.RETRY_FAILED_LOAD"
    const val EXTRA_IS_CASTING = "com.foreverjukebox.app.playback.extra.IS_CASTING"
    const val EXTRA_CAST_IS_PLAYING = "com.foreverjukebox.app.playback.extra.CAST_IS_PLAYING"
    const val EXTRA_TRACK_TITLE = "com.foreverjukebox.app.playback.extra.TRACK_TITLE"
    const val EXTRA_TRACK_ARTIST = "com.foreverjukebox.app.playback.extra.TRACK_ARTIST"
    const val EXTRA_CAST_DEVICE_NAME = "com.foreverjukebox.app.playback.extra.CAST_DEVICE_NAME"
    const val EXTRA_CAN_SKIP_PREVIOUS = "com.foreverjukebox.app.playback.extra.CAN_SKIP_PREVIOUS"
    const val EXTRA_CAN_SKIP_NEXT = "com.foreverjukebox.app.playback.extra.CAN_SKIP_NEXT"
    const val EXTRA_IS_LOADING = "com.foreverjukebox.app.playback.extra.IS_LOADING"
    const val EXTRA_IS_LOAD_FAILED = "com.foreverjukebox.app.playback.extra.IS_LOAD_FAILED"
    const val EXTRA_LOADING_PROGRESS = "com.foreverjukebox.app.playback.extra.LOADING_PROGRESS"
    const val EXTRA_SLEEP_TIMER_DURATION_MS = "com.foreverjukebox.app.playback.extra.SLEEP_TIMER_DURATION_MS"
    const val CAST_COMMAND_NAMESPACE = "urn:x-cast:com.foreverjukebox.app"
}

private const val NOTIFICATION_ACCENT = "#4AC7FF"
private const val DEFAULT_NOTIFICATION_TITLE = "Forever Jukebox"
private const val LOADING_NOTIFICATION_TITLE = "Loading"
private const val LOAD_FAILED_NOTIFICATION_TITLE = "Loading Failed"
private const val CAST_FALLBACK_DEVICE_LABEL = "Other device"
private const val BLUETOOTH_DISCONNECT_WINDOW_MS = 3_000L
private const val DEFAULT_ACTION_ICON_SIZE_PX = 96

private enum class NotificationMode {
    Local,
    Cast
}

internal enum class PlaybackAction {
    Play,
    Pause,
    Stop,
    Toggle
}

internal enum class TransportActionRoute {
    /** Drop the press: no meaningful transport target in this notification state. */
    Ignore,

    /** Ask the app to run the failed-load retry flow instead of touching playback. */
    BroadcastRetry,

    /** Normal transport handling (play/pause/stop/toggle the active playback). */
    Handle
}

/**
 * Routes a transport press by notification state. While a load is in flight every
 * press is dropped; while a failed notification is showing, Play/Toggle become the
 * retry trigger (Pause/Stop have nothing to act on); otherwise the press reaches
 * normal playback handling.
 */
internal fun routeTransportAction(
    isLoading: Boolean,
    isLoadFailed: Boolean,
    action: PlaybackAction
): TransportActionRoute {
    if (isLoading) {
        return TransportActionRoute.Ignore
    }
    if (isLoadFailed) {
        return if (action == PlaybackAction.Play || action == PlaybackAction.Toggle) {
            TransportActionRoute.BroadcastRetry
        } else {
            TransportActionRoute.Ignore
        }
    }
    return TransportActionRoute.Handle
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
    val durationMs: Long? = null,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadFailed: Boolean = false
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
    private var notificationArtwork: Bitmap? = null
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
        mediaSession = MediaSessionCompat(
            applicationContext.playbackAttributionContext(),
            "ForeverJukeboxPlayback"
        ).apply {
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
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            broadcastPlaylistPreviousRequested()
                            return true
                        }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            broadcastPlaylistNextRequested()
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

                override fun onSkipToPrevious() {
                    broadcastPlaylistPreviousRequested()
                }

                override fun onSkipToNext() {
                    broadcastPlaylistNextRequested()
                }
            })
        }
        registerBluetoothRouteMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Dispatches a hardware/Bluetooth key to the session callback, which runs the
        // same transport handling as the notification's own buttons.
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            Intent.ACTION_MEDIA_BUTTON -> {
                // Media keys arrive via startForegroundService, so this start has to reach
                // foreground even when the press posted no notification of its own — a
                // press this state ignores, or one that only asks the app to retry a
                // failed load.
                if (!hasStartedForeground) {
                    refreshNotificationForCurrentPlayback()
                }
            }
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
            PlaybackServiceConstants.ACTION_STOP -> {
                pendingForegroundStart = false
                stopAfterPendingForegroundStart()
            }
            PlaybackServiceConstants.ACTION_START, PlaybackServiceConstants.ACTION_UPDATE -> {
                val castState = intent.toCastNotificationState()
                if (castState != null) {
                    updateNotification(castState)
                } else {
                    refreshNotificationForCurrentPlayback(
                        skipAvailability = intent.notificationSkipAvailability(),
                        loadingNotification = intent.loadingNotification(),
                        isLoadFailed = intent.isLoadFailedNotification()
                    )
                }
                pendingForegroundStart = false
            }
        }
        return START_STICKY
    }

    private fun refreshNotificationForCurrentPlayback(
        skipAvailability: NotificationSkipAvailability = activeNotificationSkipAvailability(),
        loadingNotification: LoadingNotification? = null,
        isLoadFailed: Boolean = false
    ) {
        val active = activeNotificationState
        if (active?.mode == NotificationMode.Cast) {
            updateNotification(active)
            return
        }
        val controller = PlaybackControllerHolder.get(this)
        updateNotification(
            buildLocalNotificationState(
                isPlaying = controller.isPlaying(),
                skipAvailability = skipAvailability,
                loadingNotification = loadingNotification,
                isLoadFailed = isLoadFailed
            )
        )
    }

    private data class NotificationSkipAvailability(
        val canSkipPrevious: Boolean,
        val canSkipNext: Boolean
    )

    private data class LoadingNotification(
        val progressBucket: Int?
    )

    private fun buildLocalNotificationState(
        isPlaying: Boolean,
        skipAvailability: NotificationSkipAvailability = activeNotificationSkipAvailability(),
        loadingNotification: LoadingNotification? = null,
        isLoadFailed: Boolean = false
    ): PlaybackNotificationState {
        val controller = PlaybackControllerHolder.get(this)
        val audioMode = controller.player.getJukeboxAudioMode()
        val isLoading = loadingNotification != null
        val title = localPlaybackNotificationTitle(
            baseTitle = controller.getTrackTitle(),
            audioMode = audioMode,
            isLoading = isLoading,
            loadingProgressBucket = loadingNotification?.progressBucket,
            isLoadFailed = isLoadFailed
        )
        val artist = localPlaybackNotificationArtist(
            baseArtist = controller.getTrackArtist(),
            isLoading = isLoading,
            isLoadFailed = isLoadFailed
        )
        val positionMs = controller.getPlaybackPositionMs().coerceAtLeast(0L)
        val durationMs = controller.getTrackDurationMs()?.coerceAtLeast(0L)
        return PlaybackNotificationState(
            mode = NotificationMode.Local,
            isPlaying = isPlaying,
            title = title,
            artist = artist,
            castDeviceName = null,
            positionMs = positionMs,
            durationMs = durationMs,
            canSkipPrevious = !isLoading && !isLoadFailed && skipAvailability.canSkipPrevious,
            canSkipNext = !isLoading && !isLoadFailed && skipAvailability.canSkipNext,
            isLoading = isLoading,
            isLoadFailed = isLoadFailed
        )
    }

    private fun activeNotificationSkipAvailability(): NotificationSkipAvailability {
        val active = activeNotificationState
        return NotificationSkipAvailability(
            canSkipPrevious = active?.canSkipPrevious == true,
            canSkipNext = active?.canSkipNext == true
        )
    }

    private fun Intent.notificationSkipAvailability(): NotificationSkipAvailability {
        val active = activeNotificationSkipAvailability()
        return NotificationSkipAvailability(
            canSkipPrevious = if (hasExtra(PlaybackServiceConstants.EXTRA_CAN_SKIP_PREVIOUS)) {
                getBooleanExtra(PlaybackServiceConstants.EXTRA_CAN_SKIP_PREVIOUS, false)
            } else {
                active.canSkipPrevious
            },
            canSkipNext = if (hasExtra(PlaybackServiceConstants.EXTRA_CAN_SKIP_NEXT)) {
                getBooleanExtra(PlaybackServiceConstants.EXTRA_CAN_SKIP_NEXT, false)
            } else {
                active.canSkipNext
            }
        )
    }

    private fun Intent.loadingNotification(): LoadingNotification? {
        if (!getBooleanExtra(PlaybackServiceConstants.EXTRA_IS_LOADING, false)) {
            return null
        }
        val progressBucket = if (hasExtra(PlaybackServiceConstants.EXTRA_LOADING_PROGRESS)) {
            getIntExtra(PlaybackServiceConstants.EXTRA_LOADING_PROGRESS, 0)
        } else {
            null
        }
        return LoadingNotification(progressBucket)
    }

    private fun Intent.isLoadFailedNotification(): Boolean {
        return getBooleanExtra(PlaybackServiceConstants.EXTRA_IS_LOAD_FAILED, false)
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
            castDeviceName = deviceName,
            canSkipPrevious = notificationSkipAvailability().canSkipPrevious,
            canSkipNext = notificationSkipAvailability().canSkipNext
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
        val previousPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(PlaybackServiceConstants.ACTION_PLAYLIST_PREVIOUS).apply {
                setPackage(packageName)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextPendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(PlaybackServiceConstants.ACTION_PLAYLIST_NEXT).apply {
                setPackage(packageName)
            },
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
            NotificationMode.Local -> if (state.isLoadFailed) {
                android.R.drawable.ic_popup_sync
            } else if (state.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        }
        val actionLabel = when (state.mode) {
            NotificationMode.Cast -> if (state.isPlaying) "Pause" else "Play"
            NotificationMode.Local -> if (state.isLoadFailed) {
                "Retry"
            } else if (state.isPlaying) {
                "Pause"
            } else {
                "Play"
            }
        }
        val actionIcon = tintedIcon(actionIconRes, NOTIFICATION_ACCENT.toColorInt())
        val previousIcon = tintedIcon(
            android.R.drawable.ic_media_previous,
            NOTIFICATION_ACCENT.toColorInt()
        )
        val nextIcon = tintedIcon(
            android.R.drawable.ic_media_next,
            NOTIFICATION_ACCENT.toColorInt()
        )
        val actionSlots = playbackNotificationActionSlots(
            canSkipPrevious = state.canSkipPrevious,
            canSkipNext = state.canSkipNext,
            isLoading = state.isLoading,
            isLoadFailed = state.isLoadFailed
        )

        val builder = NotificationCompat.Builder(this, PlaybackServiceConstants.CHANNEL_ID)
            .setContentTitle(state.title)
            .setSmallIcon(R.drawable.ic_all_inclusive)
            .setColor(NOTIFICATION_ACCENT.toColorInt())
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)

        actionSlots.forEach { slot ->
            val action = when (slot) {
                PlaybackNotificationActionSlot.Previous -> NotificationCompat.Action.Builder(
                    previousIcon,
                    "Previous",
                    previousPendingIntent
                ).build()
                PlaybackNotificationActionSlot.Toggle -> NotificationCompat.Action.Builder(
                    actionIcon,
                    actionLabel,
                    togglePendingIntent
                ).build()
                PlaybackNotificationActionSlot.Next -> NotificationCompat.Action.Builder(
                    nextIcon,
                    "Next",
                    nextPendingIntent
                ).build()
            }
            builder.addAction(action)
        }

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
        }

        // Bind the MediaStyle/MediaSession for both local and cast playback so the
        // notification is surfaced in the lock-screen "now playing" media area. Without
        // this, the cast notification was a plain low-importance notification that many
        // lock screens hide.
        val mediaStyle = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
        when (compactActionIndices(actionSlots.size).size) {
            1 -> mediaStyle.setShowActionsInCompactView(0)
            2 -> mediaStyle.setShowActionsInCompactView(0, 1)
            3 -> mediaStyle.setShowActionsInCompactView(0, 1, 2)
        }
        builder.setStyle(mediaStyle)

        val notification: Notification = builder.build()
        if (hasStartedForeground) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(PlaybackServiceConstants.NOTIFICATION_ID, notification)
        } else {
            try {
                startForeground(PlaybackServiceConstants.NOTIFICATION_ID, notification)
                hasStartedForeground = true
            } catch (error: IllegalStateException) {
                if (isForegroundStartDenied(error)) {
                    // Android can reject entering foreground if the app is background-restricted.
                    // Avoid crashing the process; drop this notification update.
                    AppLog.warn(
                        TAG,
                        "Foreground start denied for playback notification update.",
                        error
                    )
                    activeNotificationState = null
                    hasStartedForeground = false
                    stopSelf()
                } else {
                    throw error
                }
            }
        }
    }

    private fun isForegroundStartDenied(error: IllegalStateException): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            error is ForegroundServiceStartNotAllowedException
    }

    private fun tintedIcon(resId: Int, color: Int): IconCompat {
        // BitmapFactory.decodeResource returns null for vector/XML drawables (framework
        // ic_media_* icons are no longer raster bitmaps on newer Android), so render the
        // drawable onto a canvas instead.
        val drawable = AppCompatResources.getDrawable(this, resId)
            ?: return IconCompat.createWithResource(this, resId)
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: DEFAULT_ACTION_ICON_SIZE_PX
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: DEFAULT_ACTION_ICON_SIZE_PX
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.mutate().colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun updateMediaSession(
        notificationState: PlaybackNotificationState,
        artwork: Bitmap?
    ) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(mediaSessionPlaybackActions())
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
        when (
            routeTransportAction(
                isLoading = activeState?.isLoading == true,
                isLoadFailed = activeState?.isLoadFailed == true,
                action = action
            )
        ) {
            TransportActionRoute.Ignore -> return
            TransportActionRoute.BroadcastRetry -> {
                broadcastRetryFailedLoadRequested()
                return
            }
            TransportActionRoute.Handle -> Unit
        }
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
                    val resumed = controller.requestAudioFocusForLocalPlayback() &&
                        autocanonizer.resume()
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
                } else {
                    // Idempotent when already paused/stopped; also cancels a play
                    // request parked on a delayed audio focus grant.
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
                    val resumed = controller.requestAudioFocusForLocalPlayback() &&
                        autocanonizer.resume()
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
        val manager = applicationContext.playbackAttributionContext()
            .getSystemService(AudioManager::class.java) ?: return
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

    private fun broadcastPlaylistPreviousRequested() {
        if (activeNotificationState?.isLoading == true) {
            return
        }
        sendBroadcast(Intent(PlaybackServiceConstants.ACTION_PLAYLIST_PREVIOUS).apply {
            setPackage(packageName)
        })
    }

    private fun broadcastPlaylistNextRequested() {
        if (activeNotificationState?.isLoading == true) {
            return
        }
        sendBroadcast(Intent(PlaybackServiceConstants.ACTION_PLAYLIST_NEXT).apply {
            setPackage(packageName)
        })
    }

    private fun broadcastRetryFailedLoadRequested() {
        sendBroadcast(Intent(PlaybackServiceConstants.ACTION_RETRY_FAILED_LOAD).apply {
            setPackage(packageName)
        })
    }

    private fun clearPlaybackNotificationKeepTimer() {
        activeNotificationState = null
        if (hasStartedForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            hasStartedForeground = false
        } else {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(PlaybackServiceConstants.NOTIFICATION_ID)
        }
        mediaSession.isActive = false
    }

    private fun stopAfterPendingForegroundStart() {
        if (!hasStartedForeground) {
            updateNotification(buildLocalNotificationState(isPlaying = false))
        }
        activeNotificationState = null
        if (hasStartedForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            hasStartedForeground = false
        }
        stopSelf()
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
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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
        notificationArtwork?.let { return it }
        val drawable = AppCompatResources.getDrawable(this, R.drawable.notification_background) ?: return null
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 512
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 512
        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        notificationArtwork = bitmap
        return bitmap
    }

    companion object {
        private const val TAG = "ForegroundPlaybackSvc"
        @Volatile
        private var isRunning: Boolean = false
        @Volatile
        private var pendingForegroundStart: Boolean = false
        private val _sleepTimerState = MutableStateFlow(SleepTimerStatus())
        val sleepTimerState: StateFlow<SleepTimerStatus> = _sleepTimerState
        const val ACTION_SLEEP_TIMER_EXPIRED: String =
            PlaybackServiceConstants.ACTION_SLEEP_TIMER_EXPIRED
        const val ACTION_PLAYBACK_STATE_CHANGED: String =
            PlaybackServiceConstants.ACTION_PLAYBACK_STATE_CHANGED
        const val ACTION_PLAYLIST_PREVIOUS: String =
            PlaybackServiceConstants.ACTION_PLAYLIST_PREVIOUS
        const val ACTION_PLAYLIST_NEXT: String =
            PlaybackServiceConstants.ACTION_PLAYLIST_NEXT
        const val ACTION_CLOSE_FULLSCREEN: String =
            PlaybackServiceConstants.ACTION_CLOSE_FULLSCREEN
        const val ACTION_RETRY_FAILED_LOAD: String =
            PlaybackServiceConstants.ACTION_RETRY_FAILED_LOAD

        fun start(
            context: Context,
            canSkipPrevious: Boolean? = null,
            canSkipNext: Boolean? = null,
            isLoading: Boolean = false,
            loadingProgress: Int? = null,
            isLoadFailed: Boolean = false
        ) {
            val playbackContext = context.playbackServiceContext()
            val intent = Intent(playbackContext, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_START
                putSkipAvailability(canSkipPrevious, canSkipNext)
                putLoadingNotification(isLoading, loadingProgress)
                putLoadFailedNotification(isLoadFailed)
            }
            startOrDeliver(playbackContext, intent)
        }

        fun update(
            context: Context,
            canSkipPrevious: Boolean? = null,
            canSkipNext: Boolean? = null,
            isLoading: Boolean = false,
            loadingProgress: Int? = null,
            isLoadFailed: Boolean = false
        ) {
            val playbackContext = context.playbackServiceContext()
            val intent = Intent(playbackContext, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_UPDATE
                putSkipAvailability(canSkipPrevious, canSkipNext)
                putLoadingNotification(isLoading, loadingProgress)
                putLoadFailedNotification(isLoadFailed)
            }
            startOrDeliver(playbackContext, intent)
        }

        fun setSleepTimer(context: Context, durationMs: Long?) {
            val playbackContext = context.playbackServiceContext()
            val intent = Intent(playbackContext, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_SET_SLEEP_TIMER
                putExtra(
                    PlaybackServiceConstants.EXTRA_SLEEP_TIMER_DURATION_MS,
                    durationMs ?: 0L
                )
            }
            playbackContext.startService(intent)
        }

        fun updateCast(
            context: Context,
            isPlaying: Boolean,
            title: String?,
            artist: String?,
            deviceName: String?,
            canSkipPrevious: Boolean? = null,
            canSkipNext: Boolean? = null
        ) {
            val playbackContext = context.playbackServiceContext()
            val intent = Intent(playbackContext, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_UPDATE
                putExtra(PlaybackServiceConstants.EXTRA_IS_CASTING, true)
                putExtra(PlaybackServiceConstants.EXTRA_CAST_IS_PLAYING, isPlaying)
                putExtra(PlaybackServiceConstants.EXTRA_TRACK_TITLE, title)
                putExtra(PlaybackServiceConstants.EXTRA_TRACK_ARTIST, artist)
                putExtra(PlaybackServiceConstants.EXTRA_CAST_DEVICE_NAME, deviceName)
                putSkipAvailability(canSkipPrevious, canSkipNext)
            }
            startOrDeliver(playbackContext, intent)
        }

        private fun Intent.putSkipAvailability(
            canSkipPrevious: Boolean?,
            canSkipNext: Boolean?
        ) {
            if (canSkipPrevious != null) {
                putExtra(PlaybackServiceConstants.EXTRA_CAN_SKIP_PREVIOUS, canSkipPrevious)
            }
            if (canSkipNext != null) {
                putExtra(PlaybackServiceConstants.EXTRA_CAN_SKIP_NEXT, canSkipNext)
            }
        }

        private fun Intent.putLoadingNotification(
            isLoading: Boolean,
            loadingProgress: Int?
        ) {
            if (!isLoading) {
                return
            }
            putExtra(PlaybackServiceConstants.EXTRA_IS_LOADING, true)
            loadingNotificationProgressBucket(loadingProgress)?.let { progressBucket ->
                putExtra(PlaybackServiceConstants.EXTRA_LOADING_PROGRESS, progressBucket)
            }
        }

        private fun Intent.putLoadFailedNotification(isLoadFailed: Boolean) {
            if (isLoadFailed) {
                putExtra(PlaybackServiceConstants.EXTRA_IS_LOAD_FAILED, true)
            }
        }

        fun stop(context: Context) {
            val playbackContext = context.playbackServiceContext()
            when (resolveForegroundServiceStopCommand(_sleepTimerState.value.isActive)) {
                ForegroundServiceStopCommand.ClearNotificationKeepTimer -> {
                    val intent = Intent(playbackContext, ForegroundPlaybackService::class.java).apply {
                        action = PlaybackServiceConstants.ACTION_CLEAR_NOTIFICATION_KEEP_TIMER
                    }
                    playbackContext.startService(intent)
                }
                ForegroundServiceStopCommand.StopService -> {
                    if (pendingForegroundStart) {
                        val intent = Intent(playbackContext, ForegroundPlaybackService::class.java).apply {
                            action = PlaybackServiceConstants.ACTION_STOP
                        }
                        playbackContext.startForegroundService(intent)
                    } else {
                        playbackContext.stopService(
                            Intent(playbackContext, ForegroundPlaybackService::class.java)
                        )
                    }
                }
            }
        }

        private fun Context.playbackServiceContext(): Context {
            return applicationContext.playbackAttributionContext()
        }

        // Attempts the foreground start and catches denial rather than pre-checking
        // process importance. The distinction matters when the app is backgrounded: a
        // media-button press grants a short OS exemption during which the start
        // succeeds even though an importance check would classify the app as
        // background and skip the attempt. Keeping the foreground service alive is
        // what keeps the audio subsystem (codec included) usable for the load that
        // the button press kicked off.
        private fun startOrDeliver(playbackContext: Context, intent: Intent) {
            if (isRunning) {
                playbackContext.startService(intent)
                return
            }
            pendingForegroundStart = true
            try {
                playbackContext.startForegroundService(intent)
            } catch (error: IllegalStateException) {
                pendingForegroundStart = false
                AppLog.warn(
                    TAG,
                    "Foreground service start denied; continuing without notification.",
                    error
                )
            }
        }
    }
}
