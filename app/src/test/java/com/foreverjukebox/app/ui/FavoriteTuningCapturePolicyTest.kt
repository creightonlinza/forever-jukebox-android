package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteTuningCapturePolicyTest {

    @Test
    fun capturesEngineTuningWhenPlayingJukeboxOnDevice() {
        val state = UiState(
            playback = PlaybackState(playMode = PlaybackMode.Jukebox, isCasting = false)
        )
        var engineCalls = 0

        val params = favoriteTuningParamsForCurrentTrack(state) {
            engineCalls++
            "jb=1&thresh=45"
        }

        assertEquals("jb=1&thresh=45", params)
        assertEquals(1, engineCalls)
    }

    @Test
    fun capturesNoTuningForAutocanonizerTracks() {
        val state = UiState(
            playback = PlaybackState(playMode = PlaybackMode.Autocanonizer, isCasting = false)
        )
        var engineCalls = 0

        val params = favoriteTuningParamsForCurrentTrack(state) {
            engineCalls++
            "jb=1"
        }

        assertNull(params)
        assertEquals(0, engineCalls)
    }

    @Test
    fun capturesReceiverTuningInsteadOfEngineWhileCasting() {
        // While casting the local engine is reset and never sees cast tuning edits; the
        // favorite must be built from the receiver-reported tuning state.
        val state = UiState(
            playback = PlaybackState(
                playMode = PlaybackMode.Jukebox,
                isCasting = true,
                castAudioModeWireValue = "daycore"
            ),
            tuning = TuningState(
                threshold = 31,
                computedThreshold = 29,
                justBackwards = true,
                deletedEdgeIds = listOf(4, 9),
                anchorBranchId = 12
            )
        )
        var engineCalls = 0

        val params = favoriteTuningParamsForCurrentTrack(state) {
            engineCalls++
            "stale-engine-params"
        }

        assertEquals("jb=1&thresh=31&d=4,9&ab=12&am=daycore", params)
        assertEquals(0, engineCalls)
    }

    @Test
    fun capturesNullWhileCastingWithDefaultTuning() {
        val state = UiState(
            playback = PlaybackState(
                playMode = PlaybackMode.Jukebox,
                isCasting = true,
                castAudioModeWireValue = JukeboxAudioMode.Off.wireValue
            ),
            tuning = TuningState()
        )

        val params = favoriteTuningParamsForCurrentTrack(state) { "stale-engine-params" }

        assertNull(params)
    }

    @Test
    fun capturesNoTuningForAutocanonizerTracksWhileCasting() {
        val state = UiState(
            playback = PlaybackState(playMode = PlaybackMode.Autocanonizer, isCasting = true),
            tuning = TuningState(justBackwards = true)
        )

        val params = favoriteTuningParamsForCurrentTrack(state) { "stale-engine-params" }

        assertNull(params)
    }
}
