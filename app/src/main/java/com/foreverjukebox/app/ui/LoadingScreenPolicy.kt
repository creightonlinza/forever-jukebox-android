package com.foreverjukebox.app.ui

import com.foreverjukebox.app.ui.loading.LiveLoading
import com.foreverjukebox.app.ui.loading.LoadingStepId
import com.foreverjukebox.app.ui.loading.LoadingTask

// The one load the loading screen reports: a track load in progress, else a mode render, else the
// finished load (so a shown screen can play out). Null hides the screen at once (error, cast, cancel).
internal fun liveLoading(playback: PlaybackState): LiveLoading? {
    if (playback.isCasting || !playback.analysisErrorMessage.isNullOrBlank()) {
        return null
    }
    val key = playback.loadSerial.toString()
    val load = playback.loadingStep?.let { step ->
        LiveLoading(
            task = step.task,
            key = key,
            activeId = step.activeId,
            progress = step.progress,
            message = step.message,
            finished = playback.audioLoaded && playback.analysisLoaded
        )
    }
    if (load != null && !load.finished) {
        return load
    }
    // Audio decoding again for a loaded track: the single cached-track step with the decode percent.
    if (playback.audioLoading) {
        val progress = playback.analysisProgress?.takeIf { playback.analysisMessage == AUDIO_LOADING_MESSAGE }
        return LiveLoading(LoadingTask.Track, key, LoadingStepId.Track, progress?.let { it / 100f }, null, finished = false)
    }
    if (load == null && playback.isLoading()) {
        return LiveLoading(LoadingTask.Pending, key, LoadingStepId.Pending, null, null, finished = false)
    }
    return swingLoading(playback, key) ?: wubMachineLoading(playback, key) ?: load
}

private fun swingLoading(playback: PlaybackState, key: String): LiveLoading? {
    val finished = playback.swingReady &&
        playback.playMode == PlaybackMode.Jukebox &&
        playback.jukeboxAudioMode == JukeboxAudioMode.Swing
    if (!playback.isPreparingSwing() && !finished) {
        return null
    }
    val progress = playback.swingProgress?.let { it / 100f }
    return LiveLoading(LoadingTask.Swing, key, LoadingStepId.Swing, progress, null, finished)
}

private fun wubMachineLoading(playback: PlaybackState, key: String): LiveLoading? {
    val finished = playback.wubReady && playback.playMode == PlaybackMode.WubMachine
    if (!playback.isPreparingWubMachine() && !finished) {
        return null
    }
    val task = if (playback.wubRenderLoadedSamples) LoadingTask.WubMachineWithSamples else LoadingTask.WubMachine
    val activeId = if (playback.wubLoadingSamples && !finished) LoadingStepId.Samples else LoadingStepId.Remix
    val progress = playback.wubProgress?.let { it / 100f }
    return LiveLoading(task, key, activeId, progress, null, finished)
}
