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

internal enum class AudioFocusRequestResult {
    Granted,
    Delayed,
    Denied
}

internal fun audioFocusRequestResultFor(systemResult: Int): AudioFocusRequestResult {
    return when (systemResult) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusRequestResult.Granted
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusRequestResult.Delayed
        else -> AudioFocusRequestResult.Denied
    }
}

internal interface PlaybackAudioFocusController {
    fun requestAudioFocus(): AudioFocusRequestResult
    fun abandonAudioFocus()
}

internal object NoOpPlaybackAudioFocusController : PlaybackAudioFocusController {
    override fun requestAudioFocus(): AudioFocusRequestResult = AudioFocusRequestResult.Granted

    override fun abandonAudioFocus() = Unit
}

internal class AndroidPlaybackAudioFocusController(
    context: Context,
    private val onDuckingChanged: (Boolean) -> Unit,
    private val onPlaybackFocusLost: () -> Unit,
    private val onPlaybackFocusGained: () -> Unit = {}
) : PlaybackAudioFocusController {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (audioFocusActionForChange(focusChange)) {
            AudioFocusAction.Duck -> onDuckingChanged(true)
            AudioFocusAction.Unduck -> {
                onDuckingChanged(false)
                onPlaybackFocusGained()
            }
            AudioFocusAction.Pause -> {
                onDuckingChanged(false)
                onPlaybackFocusLost()
            }
            AudioFocusAction.Ignore -> Unit
        }
    }

    override fun requestAudioFocus(): AudioFocusRequestResult {
        val request = focusRequest ?: buildFocusRequest().also { focusRequest = it }
        val result = audioFocusRequestResultFor(audioManager.requestAudioFocus(request))
        if (result == AudioFocusRequestResult.Granted) {
            onDuckingChanged(false)
        }
        return result
    }

    override fun abandonAudioFocus() {
        onDuckingChanged(false)
        focusRequest?.let(audioManager::abandonAudioFocusRequest)
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
