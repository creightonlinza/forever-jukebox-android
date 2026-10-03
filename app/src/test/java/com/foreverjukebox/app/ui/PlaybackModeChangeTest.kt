package com.foreverjukebox.app.ui

import com.foreverjukebox.app.visualization.JumpLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackModeChangeTest {

    @Test
    fun modeChangeResetsTransportStateWhenNotPreserving() {
        val before = PlaybackState(
            isRunning = true,
            isPaused = true,
            beatsPlayed = 42,
            currentBeatIndex = 17,
            canonizerOtherIndex = 12,
            autocanonizer = AutocanonizerUiState(
                mainSeconds = 62.0,
                otherSeconds = 135.0,
                trackDurationSeconds = 223.0
            ),
            lastJumpFromIndex = 3,
            jumpLine = JumpLine(3, 17, 1234L)
        )

        val after = playbackStateAfterModeChange(before, preserveTransportState = false)

        assertFalse(after.isRunning)
        assertFalse(after.isPaused)
        assertEquals(0, after.beatsPlayed)
        assertEquals(-1, after.currentBeatIndex)
        assertNull(after.canonizerOtherIndex)
        assertEquals(0.0, after.autocanonizer.mainSeconds, 0.0)
        assertEquals(0.0, after.autocanonizer.otherSeconds, 0.0)
        assertEquals(223.0, after.autocanonizer.trackDurationSeconds, 0.0)
        assertNull(after.lastJumpFromIndex)
        assertNull(after.jumpLine)
    }

    @Test
    fun modeChangePreservesTransportStateWhenRequested() {
        val before = PlaybackState(
            isRunning = true,
            isPaused = true,
            beatsPlayed = 42,
            currentBeatIndex = 17,
            canonizerOtherIndex = 12,
            autocanonizer = AutocanonizerUiState(
                mainSeconds = 62.0,
                otherSeconds = 135.0,
                trackDurationSeconds = 223.0
            ),
            lastJumpFromIndex = 3,
            jumpLine = JumpLine(3, 17, 1234L)
        )

        val after = playbackStateAfterModeChange(before, preserveTransportState = true)

        assertEquals(before.isRunning, after.isRunning)
        assertEquals(before.isPaused, after.isPaused)
        assertEquals(before.beatsPlayed, after.beatsPlayed)
        assertEquals(before.currentBeatIndex, after.currentBeatIndex)
        assertEquals(before.canonizerOtherIndex, after.canonizerOtherIndex)
        assertEquals(0.0, after.autocanonizer.mainSeconds, 0.0)
        assertEquals(0.0, after.autocanonizer.otherSeconds, 0.0)
        assertEquals(223.0, after.autocanonizer.trackDurationSeconds, 0.0)
        assertEquals(before.lastJumpFromIndex, after.lastJumpFromIndex)
        assertEquals(before.jumpLine?.from, after.jumpLine?.from)
        assertEquals(before.jumpLine?.to, after.jumpLine?.to)
        assertEquals(before.jumpLine?.startedAt, after.jumpLine?.startedAt)
    }

    @Test
    fun playModeRoundTripKeepsJukeboxAudioModeSelection() {
        val jukebox = PlaybackState(
            playMode = PlaybackMode.Jukebox,
            trackTitle = "Song",
            trackArtist = "Artist",
            jukeboxAudioMode = JukeboxAudioMode.Cowbell,
            jukeboxAudioModeIntensity = 70
        )

        val autocanonizer =
            playbackStateAfterPlayModeApplied(jukebox, PlaybackMode.Autocanonizer)
        val backToJukebox =
            playbackStateAfterPlayModeApplied(autocanonizer, PlaybackMode.Jukebox)

        assertEquals(JukeboxAudioMode.Cowbell, autocanonizer.jukeboxAudioMode)
        assertEquals(70, autocanonizer.jukeboxAudioModeIntensity)
        assertEquals(JukeboxAudioMode.Cowbell, backToJukebox.jukeboxAudioMode)
        assertEquals(70, backToJukebox.jukeboxAudioModeIntensity)
    }

    @Test
    fun playTitleShowsAutocanonizedOverRetainedAudioMode() {
        val jukebox = PlaybackState(
            playMode = PlaybackMode.Jukebox,
            trackTitle = "Song",
            trackArtist = "Artist",
            jukeboxAudioMode = JukeboxAudioMode.Cowbell
        )

        val autocanonizer =
            playbackStateAfterPlayModeApplied(jukebox, PlaybackMode.Autocanonizer)
        val backToJukebox =
            playbackStateAfterPlayModeApplied(autocanonizer, PlaybackMode.Jukebox)

        assertEquals("Song (autocanonized) — Artist", autocanonizer.playTitle)
        assertEquals("Song (cowbell) — Artist", backToJukebox.playTitle)
    }

    @Test
    fun playTitleShowsWubMachineRemixOverRetainedAudioMode() {
        val jukebox = PlaybackState(
            playMode = PlaybackMode.Jukebox,
            trackTitle = "Song",
            trackArtist = "Artist",
            jukeboxAudioMode = JukeboxAudioMode.Swing
        )

        val wub = playbackStateAfterPlayModeApplied(jukebox, PlaybackMode.WubMachine)
        val backToJukebox = playbackStateAfterPlayModeApplied(wub, PlaybackMode.Jukebox)

        assertEquals("Song (wub machine remix) — Artist", wub.playTitle)
        assertEquals(JukeboxAudioMode.Swing, wub.jukeboxAudioMode)
        assertEquals("Song (swing) — Artist", backToJukebox.playTitle)
    }

    @Test
    fun modeChangeRewindsTheRemixPlayhead() {
        val before = PlaybackState(
            wubMachine = WubMachineUiState(durationSeconds = 90.0, positionSeconds = 42.0, loop = true)
        )

        val after = playbackStateAfterModeChange(before, preserveTransportState = false)

        assertEquals(0.0, after.wubMachine.positionSeconds, 0.0)
        assertEquals(90.0, after.wubMachine.durationSeconds, 0.0)
        assertEquals(true, after.wubMachine.loop)
    }
}
