package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.engine.JukeboxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TuningCoordinatorCastPolicyTest {

    @Test
    fun buildCastTuningResetParamsResetsGraphAndAudioButOmitsHighlight() {
        val params = buildCastTuningResetParams(
            defaultConfig = JukeboxConfig(),
            randomBranchDeltaPercentScale = 500.0,
            resetThreshold = 34
        )

        assertEquals("jb=0&bl=0&sq=1&thresh=34&bp=18,50,10&d=&am=off", params)
    }

    @Test
    fun buildCastTuningUpdateUsesHighlightOnlyPayloadWhenOnlyHighlightChanges() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequential = true
        )

        val update = buildCastTuningUpdate(
            currentTuning = current,
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = true,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0
        )

        assertEquals(current.copy(highlightAnchorBranch = true), update.nextTuning)
        assertEquals("ah=1", update.castParams)
    }

    @Test
    fun buildCastTuningUpdateUsesAudioOnlyPayloadWhenOnlyAudioModeChanges() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequential = true
        )

        val enabled = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Off,
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Lofi
        )
        val disabled = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Lofi,
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Off
        )

        assertEquals("am=lofi", enabled.castParams)
        assertEquals("am=off", disabled.castParams)
    }

    @Test
    fun buildCastTuningUpdateEmitsLatestAudioModes() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequential = true
        )

        val eightBit = buildAudioOnlyCastUpdate(current, JukeboxAudioMode.EightBit)
        val underwater = buildAudioOnlyCastUpdate(current, JukeboxAudioMode.Underwater)
        val cathedral = buildAudioOnlyCastUpdate(current, JukeboxAudioMode.Cathedral)
        val cowbell = buildAudioOnlyCastUpdate(current, JukeboxAudioMode.Cowbell)

        assertEquals("am=eight_bit", eightBit.castParams)
        assertEquals("am=underwater", underwater.castParams)
        assertEquals("am=cathedral", cathedral.castParams)
        assertEquals("am=cowbell", cowbell.castParams)
    }

    @Test
    fun buildCastTuningUpdateEmitsReceiverOnlyAudioModeWireValue() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequential = true
        )

        val update = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Off,
            currentAudioModeWireValue = "off",
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Off,
            audioModeWireValue = "future_mode"
        )

        assertEquals("am=future_mode", update.castParams)
    }

    @Test
    fun buildCastTuningUpdateUsesChangedThresholdOnly() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequential = true
        )

        val update = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Lofi,
            threshold = 9,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.05,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Lofi
        )

        assertEquals("thresh=9", update.castParams)
    }

    @Test
    fun buildCastTuningUpdateUsesOnlyChangedTuningAndAudioKeys() {
        val current = TuningState(
            threshold = 22,
            minProb = 10,
            maxProb = 40,
            ramp = 25,
            highlightAnchorBranch = false,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequential = true
        )

        val update = buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Off,
            threshold = 22,
            minProb = 0.10,
            maxProb = 0.40,
            ramp = 0.10,
            highlightAnchorBranch = true,
            justBackwards = true,
            minJumpDistancePercent = 0,
            removeSequentialBranches = true,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = JukeboxAudioMode.Vaporwave
        )

        assertEquals("bp=10,40,50&ah=1&am=vaporwave", update.castParams)
    }

    @Test
    fun buildCastTuningUpdateUsesBranchLengthAndExplicitDisableValues() {
        val enabled = buildCastTuningUpdate(
            currentTuning = TuningState(minJumpDistancePercent = 0),
            threshold = 2,
            minProb = 0.18,
            maxProb = 0.50,
            ramp = 0.02,
            highlightAnchorBranch = false,
            justBackwards = false,
            minJumpDistancePercent = 30,
            removeSequentialBranches = false,
            randomBranchDeltaPercentScale = 500.0
        )
        val disabled = buildCastTuningUpdate(
            currentTuning = enabled.nextTuning,
            threshold = 2,
            minProb = 0.18,
            maxProb = 0.50,
            ramp = 0.02,
            highlightAnchorBranch = false,
            justBackwards = false,
            minJumpDistancePercent = 0,
            removeSequentialBranches = false,
            randomBranchDeltaPercentScale = 500.0
        )

        assertEquals("bl=30", enabled.castParams)
        assertEquals("bl=0", disabled.castParams)
    }

    @Test
    fun buildCastTuningUpdateClampsThresholdAndPercents() {
        val update = buildCastTuningUpdate(
            currentTuning = TuningState(),
            threshold = 1,
            minProb = -2.0,
            maxProb = 5.0,
            ramp = 1.0,
            highlightAnchorBranch = false,
            justBackwards = false,
            minJumpDistancePercent = 0,
            removeSequentialBranches = false,
            randomBranchDeltaPercentScale = 500.0
        )

        assertEquals(2, update.nextTuning.threshold)
        assertEquals(0, update.nextTuning.minProb)
        assertEquals(100, update.nextTuning.maxProb)
        assertEquals(100, update.nextTuning.ramp)
    }

    @Test
    fun localTrackTuningIdReturnsPrefixedIdForOnDeviceLocalTrack() {
        val state = UiState(
            appMode = AppMode.Local,
            playback = PlaybackState(lastJobId = "local-0123456789abcdef")
        )

        assertEquals("local-0123456789abcdef", localTrackTuningId(state))
    }

    @Test
    fun localTrackTuningIdRestoresPrefixWhileCastingLocalTrack() {
        // While casting, the receiver reports the bare cache fingerprint as the job id.
        val state = UiState(
            appMode = AppMode.Local,
            playback = PlaybackState(
                isCasting = true,
                lastJobId = "0123456789abcdef",
                localSourceUri = "content://audio/1"
            )
        )

        assertEquals("local-0123456789abcdef", localTrackTuningId(state))
    }

    @Test
    fun localTrackTuningIdReturnsNullForNonLocalPlayback() {
        val castingServerTrack = UiState(
            appMode = AppMode.Server,
            playback = PlaybackState(
                isCasting = true,
                lastJobId = "a3f3c0dc73c6476c9db95c227f9206f2"
            )
        )
        val localModeWithoutLocalSource = UiState(
            appMode = AppMode.Local,
            playback = PlaybackState(isCasting = true, lastJobId = "0123456789abcdef")
        )
        val bareIdWithoutCasting = UiState(
            appMode = AppMode.Local,
            playback = PlaybackState(lastJobId = "0123456789abcdef")
        )

        assertNull(localTrackTuningId(castingServerTrack))
        assertNull(localTrackTuningId(localModeWithoutLocalSource))
        assertNull(localTrackTuningId(bareIdWithoutCasting))
    }

    private fun buildAudioOnlyCastUpdate(
        current: TuningState,
        audioMode: JukeboxAudioMode
    ): CastTuningUpdate {
        return buildCastTuningUpdate(
            currentTuning = current,
            currentAudioMode = JukeboxAudioMode.Off,
            threshold = current.threshold,
            minProb = current.minProb / 100.0,
            maxProb = current.maxProb / 100.0,
            ramp = current.ramp / 500.0,
            highlightAnchorBranch = current.highlightAnchorBranch,
            justBackwards = current.justBackwards,
            minJumpDistancePercent = current.minJumpDistancePercent,
            removeSequentialBranches = current.removeSequential,
            randomBranchDeltaPercentScale = 500.0,
            audioMode = audioMode
        )
    }
}
