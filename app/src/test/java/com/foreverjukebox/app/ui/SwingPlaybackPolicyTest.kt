package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwingPlaybackPolicyTest {

    private val loadedSwing = PlaybackState(
        jukeboxAudioMode = JukeboxAudioMode.Swing,
        audioLoaded = true,
        analysisLoaded = true,
        lastJobId = "job"
    )

    @Test
    fun swingWireValueMatchesWeb() {
        assertEquals(JukeboxAudioMode.Swing, JukeboxAudioMode.fromWireValue("swing"))
        assertEquals(JukeboxAudioMode.Swing, TuningParamsCodec.parse("am=swing")?.audioMode)
    }

    @Test
    fun preparingOnlyWhileLoadedJukeboxTrackLacksItsSwungCopy() {
        assertTrue(loadedSwing.isPreparingSwing())
        assertFalse(loadedSwing.copy(swingReady = true).isPreparingSwing())
        assertFalse(loadedSwing.copy(audioLoaded = false).isPreparingSwing())
        assertFalse(loadedSwing.copy(analysisLoaded = false).isPreparingSwing())
        assertFalse(loadedSwing.copy(isCasting = true).isPreparingSwing())
        assertFalse(loadedSwing.copy(playMode = PlaybackMode.Autocanonizer).isPreparingSwing())
        assertFalse(loadedSwing.copy(jukeboxAudioMode = JukeboxAudioMode.Off).isPreparingSwing())
    }

    @Test
    fun newTrackDropsSwingReadiness() {
        val reset = loadedSwing.copy(swingReady = true, swingProgress = 40)
            .resetForNewTrack(keepLoadVisible = false)

        assertFalse(reset.swingReady)
        assertNull(reset.swingProgress)
    }

    @Test
    fun playAfterLoadedWaitsForSwing() {
        val waiting = loadedSwing.copy(playAfterLoaded = true)

        assertFalse(shouldStartPlayAfterLoaded(waiting))
        assertTrue(shouldStartPlayAfterLoaded(waiting.copy(swingReady = true)))
    }

    @Test
    fun preparingShowsAsLoadingEverywhere() {
        val preparing = loadedSwing.copy(swingProgress = 40, isPaused = true)

        assertEquals(PREPARING_SWING_LABEL, playbackTransportContentDescription(preparing))
        assertEquals(ListenContentMode.None, resolveListenContentMode(preparing))
        assertEquals(
            PlaybackServiceSession.LocalLoading(40),
            resolvePlaybackServiceSession(preparing)
        )
        assertEquals(
            PlaybackServiceSession.LocalPaused,
            resolvePlaybackServiceSession(preparing.copy(swingReady = true))
        )
    }

    @Test
    fun swingRenderKeyFollowsTrackIdentity() {
        assertNull(loadedSwing.copy(swingReady = true).swingRenderKey())
        assertEquals(loadedSwing.swingRenderKey(), loadedSwing.copy(swingProgress = 10).swingRenderKey())
        assertTrue(loadedSwing.swingRenderKey() != loadedSwing.copy(lastJobId = "other").swingRenderKey())
    }
}
