package com.foreverjukebox.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.foreverjukebox.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val LOADING_AUDIO_FEEDBACK_REPEAT_DELAY_MS = 15_000L

interface LoadingAudioFeedbackPlayer {
    fun startLoadingPulse()
    fun stopLoadingPulse()
    fun playFailure()
    fun release()
}

class LoadingAudioFeedbackController(
    private val player: LoadingAudioFeedbackPlayer
) {
    private var hadActiveLoading = false
    private var failurePlayedForMessage: String? = null

    fun update(
        enabled: Boolean,
        loading: Boolean,
        failureMessage: String?
    ) {
        if (!enabled) {
            hadActiveLoading = false
            failurePlayedForMessage = failureMessage
            player.stopLoadingPulse()
            return
        }

        if (loading) {
            hadActiveLoading = true
            failurePlayedForMessage = null
            player.startLoadingPulse()
            return
        }

        player.stopLoadingPulse()
        val resolvedFailure = failureMessage?.trim()?.takeIf { it.isNotBlank() }
        if (
            hadActiveLoading &&
            resolvedFailure != null &&
            resolvedFailure != LOCAL_ANALYSIS_CANCELLED_MESSAGE &&
            resolvedFailure != failurePlayedForMessage
        ) {
            player.playFailure()
            failurePlayedForMessage = resolvedFailure
        }
        hadActiveLoading = false
    }

    fun release() {
        hadActiveLoading = false
        player.release()
    }

    companion object {
        const val LOCAL_ANALYSIS_CANCELLED_MESSAGE = "Analysis cancelled."
    }
}

class SoundPoolLoadingAudioFeedbackPlayer(
    context: Context,
    private val scope: CoroutineScope
) : LoadingAudioFeedbackPlayer {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .build()
    private var loadingSoundId = UNLOADED_SOUND_ID
    private var failureSoundId = UNLOADED_SOUND_ID
    private var loadingPulseJob: Job? = null
    private var failureJob: Job? = null
    private var loadingSoundLoaded = false
    private var failureSoundLoaded = false
    private var released = false

    init {
        val appContext = context.applicationContext
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != SOUND_LOAD_SUCCESS) return@setOnLoadCompleteListener
            when (sampleId) {
                loadingSoundId -> loadingSoundLoaded = true
                failureSoundId -> failureSoundLoaded = true
            }
        }
        loadingSoundId = soundPool.load(appContext, R.raw.loading_slot_fall, SOUND_LOAD_PRIORITY)
        failureSoundId = soundPool.load(appContext, R.raw.loading_error, SOUND_LOAD_PRIORITY)
    }

    override fun startLoadingPulse() {
        if (released) return
        if (loadingPulseJob?.isActive == true) return
        loadingPulseJob = scope.launch {
            delay(LOADING_INITIAL_DELAY_MS)
            while (isActive) {
                playLoadingSound()
                delay(LOADING_AUDIO_FEEDBACK_REPEAT_DELAY_MS)
            }
        }
    }

    override fun stopLoadingPulse() {
        loadingPulseJob?.cancel()
        loadingPulseJob = null
    }

    override fun playFailure() {
        if (released) return
        failureJob?.cancel()
        failureJob = scope.launch {
            playFailureSound()
        }
    }

    override fun release() {
        if (released) return
        released = true
        stopLoadingPulse()
        failureJob?.cancel()
        failureJob = null
        soundPool.release()
    }

    private fun playLoadingSound() {
        if (loadingSoundLoaded) {
            playSound(loadingSoundId)
        }
    }

    private fun playFailureSound() {
        if (failureSoundLoaded) {
            playSound(failureSoundId)
        }
    }

    private fun playSound(soundId: Int) {
        soundPool.play(
            soundId,
            FEEDBACK_VOLUME,
            FEEDBACK_VOLUME,
            PLAY_PRIORITY,
            NO_LOOP,
            PLAYBACK_RATE_NORMAL
        )
    }

    private companion object {
        const val MAX_STREAMS = 2
        const val UNLOADED_SOUND_ID = 0
        const val SOUND_LOAD_PRIORITY = 1
        const val SOUND_LOAD_SUCCESS = 0
        const val PLAY_PRIORITY = 1
        const val NO_LOOP = 0
        const val PLAYBACK_RATE_NORMAL = 1.0f
        const val FEEDBACK_VOLUME = 0.55f
        const val LOADING_INITIAL_DELAY_MS = 1_200L
    }
}
