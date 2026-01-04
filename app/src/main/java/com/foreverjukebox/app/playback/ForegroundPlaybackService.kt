package com.foreverjukebox.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.foreverjukebox.app.MainActivity
import com.foreverjukebox.app.R

private object PlaybackServiceConstants {
    const val CHANNEL_ID = "fj_playback"
    const val NOTIFICATION_ID = 2001
    const val ACTION_START = "com.foreverjukebox.app.playback.START"
    const val ACTION_UPDATE = "com.foreverjukebox.app.playback.UPDATE"
    const val ACTION_TOGGLE = "com.foreverjukebox.app.playback.TOGGLE"
}

private const val NOTIFICATION_ACCENT = "#4AC7FF"

class ForegroundPlaybackService : Service() {
    private lateinit var mediaSession: MediaSessionCompat

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "ForeverJukeboxPlayback").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    handlePlayPause(shouldPlay = true)
                }

                override fun onPause() {
                    handlePlayPause(shouldPlay = false)
                }

                override fun onStop() {
                    handlePlayPause(shouldPlay = false)
                }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            PlaybackServiceConstants.ACTION_TOGGLE -> {
                val controller = PlaybackControllerHolder.get(this)
                val isPlaying = controller.togglePlayback()
                updateNotification(isPlaying)
            }
            PlaybackServiceConstants.ACTION_START, PlaybackServiceConstants.ACTION_UPDATE -> {
                val controller = PlaybackControllerHolder.get(this)
                val isPlaying = controller.isPlaying()
                updateNotification(isPlaying)
            }
        }
        return START_STICKY
    }

    private fun updateNotification(isPlaying: Boolean) {
        if (!isPlaying) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        createChannel()
        val controller = PlaybackControllerHolder.get(this)
        val title = controller.getTrackTitle().orEmpty().ifBlank { "The Forever Jukebox" }
        val artist = controller.getTrackArtist().orEmpty()
        updateMediaSession(title, artist, isPlaying)

        val toggleIntent = Intent(this, ForegroundPlaybackService::class.java).apply {
            action = PlaybackServiceConstants.ACTION_TOGGLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
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
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        val actionIconRes = if (isPlaying) android.R.drawable.ic_media_pause
        else android.R.drawable.ic_media_play
        val actionLabel = if (isPlaying) "Stop" else "Play"
        val actionIcon = tintedIcon(actionIconRes, Color.parseColor(NOTIFICATION_ACCENT))

        val notification: Notification = NotificationCompat.Builder(this, PlaybackServiceConstants.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Color.parseColor(NOTIFICATION_ACCENT))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityPendingIntent)
            .setOngoing(isPlaying)
            .addAction(
                NotificationCompat.Action.Builder(
                    actionIcon,
                    actionLabel,
                    togglePendingIntent
                ).build()
            )
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .build()

        startForeground(PlaybackServiceConstants.NOTIFICATION_ID, notification)
    }

    private fun tintedIcon(resId: Int, color: Int): IconCompat {
        val source = BitmapFactory.decodeResource(resources, resId)
        val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return IconCompat.createWithBitmap(bitmap)
    }

    private fun updateMediaSession(title: String, artist: String, isPlaying: Boolean) {
        val state = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
            .setState(
                if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1f
            )
            .build()
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .build()
        mediaSession.setPlaybackState(state)
        mediaSession.setMetadata(metadata)
        mediaSession.isActive = isPlaying
    }

    private fun handlePlayPause(shouldPlay: Boolean) {
        val controller = PlaybackControllerHolder.get(this)
        val isPlaying = controller.isPlaying()
        if (shouldPlay && !isPlaying) {
            updateNotification(controller.togglePlayback())
        } else if (!shouldPlay && isPlaying) {
            controller.stopPlayback()
            updateNotification(false)
        } else {
            updateNotification(isPlaying)
        }
    }

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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

    private fun pendingIntentImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context) {
            val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                action = PlaybackServiceConstants.ACTION_UPDATE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundPlaybackService::class.java))
        }
    }
}
