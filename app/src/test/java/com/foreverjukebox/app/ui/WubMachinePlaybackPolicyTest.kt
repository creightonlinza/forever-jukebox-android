package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoritePlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WubMachinePlaybackPolicyTest {

    private val loadedWub = PlaybackState(
        playMode = PlaybackMode.WubMachine,
        audioLoaded = true,
        analysisLoaded = true,
        lastJobId = "job"
    )

    @Test
    fun favoritePlayModeRoundTrips() {
        assertEquals(FavoritePlayMode.WubMachine, PlaybackMode.WubMachine.toFavoritePlayModeOrNull())
        assertEquals(PlaybackMode.WubMachine, FavoritePlayMode.WubMachine.toPlaybackMode())
        assertEquals("wubmachine", analyticsPlayMode(PlaybackMode.WubMachine))
    }

    @Test
    fun preparingOnlyWhileLoadedTrackLacksItsRemix() {
        assertTrue(loadedWub.isPreparingWubMachine())
        assertTrue(loadedWub.isPreparingRenderedAudio())
        assertFalse(loadedWub.copy(wubReady = true).isPreparingWubMachine())
        assertFalse(loadedWub.copy(wubRenderFailed = true).isPreparingWubMachine())
        assertFalse(loadedWub.copy(audioLoaded = false).isPreparingWubMachine())
        assertFalse(loadedWub.copy(analysisLoaded = false).isPreparingWubMachine())
        assertFalse(loadedWub.copy(isCasting = true).isPreparingWubMachine())
        assertFalse(loadedWub.copy(playMode = PlaybackMode.Jukebox).isPreparingWubMachine())
    }

    @Test
    fun newTrackDropsTheRemixButKeepsTheLoopPreference() {
        val reset = loadedWub.copy(
            wubReady = true,
            wubProgress = 40,
            wubRenderFailed = true,
            wubMachine = WubMachineUiState(durationSeconds = 90.0, positionSeconds = 12.0, loop = true)
        ).resetForNewTrack(keepLoadVisible = false)

        assertFalse(reset.wubReady)
        assertNull(reset.wubProgress)
        assertFalse(reset.wubRenderFailed)
        assertEquals(WubMachineUiState(loop = true), reset.wubMachine)
    }

    @Test
    fun playAfterLoadedWaitsForTheRemix() {
        val waiting = loadedWub.copy(playAfterLoaded = true)

        assertFalse(shouldStartPlayAfterLoaded(waiting))
        assertTrue(shouldStartPlayAfterLoaded(waiting.copy(wubReady = true)))
    }

    @Test
    fun preparingShowsAsLoadingEverywhere() {
        val preparing = loadedWub.copy(wubProgress = 40, isPaused = true)

        assertEquals(PREPARING_WUB_MACHINE_LABEL, playbackTransportContentDescription(preparing))
        assertEquals(ListenContentMode.None, resolveListenContentMode(preparing))
        assertEquals(
            PlaybackServiceSession.LocalLoading(40),
            resolvePlaybackServiceSession(preparing)
        )
        assertEquals(
            PlaybackServiceSession.LocalPaused,
            resolvePlaybackServiceSession(preparing.copy(wubReady = true))
        )
    }

    @Test
    fun renderKeyFollowsTrackIdentity() {
        assertNull(loadedWub.copy(wubReady = true).wubRenderKey())
        assertEquals(loadedWub.wubRenderKey(), loadedWub.copy(wubProgress = 10).wubRenderKey())
        assertTrue(loadedWub.wubRenderKey() != loadedWub.copy(lastJobId = "other").wubRenderKey())
    }

    @Test
    fun playTitleAndSummaryDescribeTheRemix() {
        assertEquals(
            "Song (wub machine remix) — Artist",
            buildPlayTitle("Song", "Artist", PlaybackMode.WubMachine, JukeboxAudioMode.Nightcore)
        )
        val summary = playbackSummaryLine(loadedWub.copy(listenTime = "00:01:00", beatsPlayed = 7))
        assertEquals("Listen Time: 00:01:00", summary)
    }

    @Test
    fun cursorTimeRowShowsOnlyForARenderedLocalRemix() {
        assertFalse(shouldShowWubMachineCursorTime(loadedWub))
        assertTrue(shouldShowWubMachineCursorTime(loadedWub.copy(wubReady = true)))
        assertFalse(shouldShowWubMachineCursorTime(loadedWub.copy(wubReady = true, isCasting = true)))
    }
}
