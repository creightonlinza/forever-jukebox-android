package com.foreverjukebox.app.ui

import com.foreverjukebox.app.ui.loading.LoadingStep
import com.foreverjukebox.app.ui.loading.LoadingStepId
import com.foreverjukebox.app.ui.loading.LoadingTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingScreenPolicyTest {
    private val loaded = PlaybackState(
        audioLoaded = true,
        analysisLoaded = true,
        loadingStep = LoadingStep(LoadingTask.ServerAnalysis, LoadingStepId.Track),
        loadSerial = 3
    )

    @Test
    fun aRenderAfterTheLoadIsReportedForTheSameTrack() {
        val swing = liveLoading(loaded.copy(jukeboxAudioMode = JukeboxAudioMode.Swing, swingProgress = 40))!!
        assertEquals(LoadingTask.Swing, swing.task)
        assertEquals("3", swing.key)
        assertEquals(0.4f, swing.progress)
        val done = liveLoading(loaded.copy(jukeboxAudioMode = JukeboxAudioMode.Swing, swingReady = true))!!
        assertEquals(LoadingTask.Swing, done.task)
        assertTrue(done.finished)
    }

    @Test
    fun aFinishedLoadWithNoRenderIsReportedFinished() {
        val live = liveLoading(loaded)!!
        assertEquals(LoadingTask.ServerAnalysis, live.task)
        assertTrue(live.finished)
    }

    @Test
    fun errorsAndCastingHideTheScreen() {
        assertNull(liveLoading(loaded.copy(analysisErrorMessage = "Loading failed.")))
        assertNull(liveLoading(loaded.copy(isCasting = true)))
        assertNull(liveLoading(PlaybackState()))
    }

    @Test
    fun aLoadWaitingForItsFirstReplyShowsThePlaceholder() {
        val pending = liveLoading(PlaybackState(analysisInFlight = true))!!
        assertEquals(LoadingTask.Pending, pending.task)
        assertEquals(LoadingTask.Track, liveLoading(loaded.copy(audioLoaded = false, loadingStep = LoadingStep(LoadingTask.Track, LoadingStepId.Track)))!!.task)
    }

    @Test
    fun audioDecodingAgainForALoadedTrackShowsTheTrackStepWithItsPercent() {
        val reloading = loaded.copy(audioLoading = true, analysisProgress = 30, analysisMessage = "Loading audio")
        val live = liveLoading(reloading)!!
        assertEquals(LoadingTask.Track, live.task)
        assertEquals(0.3f, live.progress)
        assertTrue(!live.finished)
        assertEquals(LoadingTask.Track, liveLoading(PlaybackState(audioLoading = true))!!.task)
    }

    @Test
    fun aFinishedReDecodeEndsTheLoadItRaised() {
        val reloading = loaded.copy(
            loadingStep = null,
            audioLoading = true,
            analysisInFlight = true,
            analysisProgress = 80,
            analysisMessage = "Loading audio"
        )
        val done = reloading.withAudioReloaded()
        assertTrue(!done.isLoading())
        assertNull(done.analysisMessage)
        assertNull(liveLoading(done))
        // Without an analysis in hand, the load in flight is left alone.
        val midLoad = reloading.copy(analysisLoaded = false).withAudioReloaded()
        assertTrue(midLoad.analysisInFlight)
    }

    @Test
    fun theRemixKeepsItsSamplesStepOnceSamplesLoaded() {
        val wub = loaded.copy(playMode = PlaybackMode.WubMachine, wubProgress = 10, wubRenderLoadedSamples = true)
        assertEquals(LoadingTask.WubMachineWithSamples, liveLoading(wub)!!.task)
        assertEquals(LoadingStepId.Remix, liveLoading(wub)!!.activeId)
        assertEquals(LoadingTask.WubMachine, liveLoading(wub.copy(wubRenderLoadedSamples = false))!!.task)
    }
}
