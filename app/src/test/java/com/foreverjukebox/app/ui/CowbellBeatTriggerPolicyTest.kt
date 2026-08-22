package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CowbellBeatTriggerPolicyTest {

    @Test
    fun schedulesOnNewBeatInLocalCowbellJukebox() {
        assertTrue(trigger())
    }

    @Test
    fun ignoresRepeatedTicksWithinTheSameBeat() {
        assertFalse(trigger(beatsPlayed = 7, lastScheduledBeatsPlayed = 7))
    }

    @Test
    fun schedulesAgainAfterTheSentinelReset() {
        assertTrue(trigger(beatsPlayed = 0, lastScheduledBeatsPlayed = -1))
    }

    @Test
    fun staysSilentOutsideLocalCowbellJukebox() {
        assertFalse(trigger(playMode = PlaybackMode.Autocanonizer))
        assertFalse(trigger(audioMode = JukeboxAudioMode.Off))
        assertFalse(trigger(audioMode = JukeboxAudioMode.Nightcore))
        assertFalse(trigger(isCasting = true))
        assertFalse(trigger(currentBeatIndex = -1))
    }

    private fun trigger(
        playMode: PlaybackMode = PlaybackMode.Jukebox,
        audioMode: JukeboxAudioMode = JukeboxAudioMode.Cowbell,
        isCasting: Boolean = false,
        currentBeatIndex: Int = 3,
        beatsPlayed: Int = 8,
        lastScheduledBeatsPlayed: Int = 7
    ): Boolean {
        return shouldScheduleCowbellBeat(
            playMode = playMode,
            audioMode = audioMode,
            isCasting = isCasting,
            currentBeatIndex = currentBeatIndex,
            beatsPlayed = beatsPlayed,
            lastScheduledBeatsPlayed = lastScheduledBeatsPlayed
        )
    }
}
