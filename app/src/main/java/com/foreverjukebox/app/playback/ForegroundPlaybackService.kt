package com.foreverjukebox.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.foreverjukebox.app.MainActivity
import com.foreverjukebox.app.R

class ForegroundPlaybackService : Service() {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val controller = PlaybackControllerHolder.get(this)
                val isPlaying = controller.togglePlayback()
                updateNotification(isPlaying)
            }
            ACTION_START, ACTION_UPDATE -> {
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

        val toggleIntent = Intent(this, ForegroundPlaybackService::class.java).apply {
            action = ACTION_TOGGLE
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
        }
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag()
        )

        val actionIcon = if (isPlaying) android.R.drawable.ic_media_pause
        else android.R.drawable.ic_media_play
        val actionLabel = if (isPlaying) "Stop" else "Play"

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(activityPendingIntent)
            .setOngoing(isPlaying)
            .addAction(actionIcon, actionLabel, togglePendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
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
        private const val CHANNEL_ID = "fj_playback"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "com.foreverjukebox.app.playback.START"
        private const val ACTION_UPDATE = "com.foreverjukebox.app.playback.UPDATE"
        private const val ACTION_TOGGLE = "com.foreverjukebox.app.playback.TOGGLE"

        fun start(context: Context) {
            val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun update(context: Context) {
            val intent = Intent(context, ForegroundPlaybackService::class.java).apply {
                action = ACTION_UPDATE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ForegroundPlaybackService::class.java))
        }
    }
}
