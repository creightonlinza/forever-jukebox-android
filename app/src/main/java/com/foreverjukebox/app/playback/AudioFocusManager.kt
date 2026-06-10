package com.foreverjukebox.app.playback

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

internal const val PLAYBACK_ATTRIBUTION_TAG = "audio_playback"

internal fun Context.playbackAttributionContext(): Context {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        createAttributionContext(PLAYBACK_ATTRIBUTION_TAG)
    } else {
        this
    }
}

internal enum class AudioFocusAction {
    Duck,
    Unduck,
    Pause,
    Ignore
}

internal fun audioFocusActionForChange(focusChange: Int): AudioFocusAction {
    return when (focusChange) {
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusAction.Duck
        AudioManager.AUDIOFOCUS_GAIN -> AudioFocusAction.Unduck
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusAction.Pause
        else -> AudioFocusAction.Ignore
    }
}

internal interface PlaybackAudioFocusController {
    fun requestAudioFocus(): Boolean
    fun abandonAudioFocus()
}

internal object NoOpPlaybackAudioFocusController : PlaybackAudioFocusController {
    override fun requestAudioFocus(): Boolean = true

    override fun abandonAudioFocus() = Unit
}

internal class AndroidPlaybackAudioFocusController(
    private val context: Context,
    private val onDuckingChanged: (Boolean) -> Unit,
    private val onPlaybackFocusLost: () -> Unit
) : PlaybackAudioFocusController {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (audioFocusActionForChange(focusChange)) {
            AudioFocusAction.Duck -> onDuckingChanged(true)
            AudioFocusAction.Unduck -> onDuckingChanged(false)
            AudioFocusAction.Pause -> {
                onDuckingChanged(false)
                onPlaybackFocusLost()
            }
            AudioFocusAction.Ignore -> Unit
        }
    }

    override fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: buildFocusRequest().also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            onDuckingChanged(false)
        }
        return granted
    }

    override fun abandonAudioFocus() {
        onDuckingChanged(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    private fun buildFocusRequest(): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
    }
}

internal fun broadcastLocalPlaybackStateChanged(context: Context) {
    context.sendBroadcast(Intent(ForegroundPlaybackService.ACTION_PLAYBACK_STATE_CHANGED).apply {
        setPackage(context.packageName)
    })
}
