package com.foreverjukebox.app.ui

import com.foreverjukebox.app.engine.DEFAULT_JUKEBOX_CONFIG
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.JukeboxEngine
import com.foreverjukebox.app.engine.JukeboxPlayer
import com.foreverjukebox.app.engine.withMinimumJumpDistancePercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The playback controller, and the engine inside it, outlive the view model: a view model built
 * while the playback service keeps the process alive meets an engine still tuned for the last
 * track. These tests pin that a track's tuning comes only from its own saved params laid over
 * constant defaults, never from what the engine was left holding.
 */
class TrackTuningDefaultsTest {

    @Test
    fun defaultsAreWhatAFreshEngineStartsWith() {
        assertEquals(DEFAULT_JUKEBOX_CONFIG, JukeboxEngine(IdlePlayer()).getConfig())
        assertEquals(JukeboxConfig(), DEFAULT_JUKEBOX_CONFIG)
    }

    @Test
    fun defaultsReadAsAnUntunedTuningState() {
        assertEquals(TuningState(), engineTuningState(DEFAULT_JUKEBOX_CONFIG, computedThreshold = null))
    }

    @Test
    fun engineLeftTunedByAPreviousTrackDoesNotReachTheNextTrack() {
        val engine = JukeboxEngine(IdlePlayer())
        engine.updateConfig(tunedConfig(minJumpDistancePercent = 5, threshold = 40))
        assertNotEquals(DEFAULT_JUKEBOX_CONFIG, engine.getConfig())

        val nextTrack = trackTuningConfig(TuningParamsCodec.parse(null))

        assertEquals(DEFAULT_JUKEBOX_CONFIG, nextTrack)
        val tuning = engineTuningState(nextTrack, computedThreshold = 31)
        assertEquals(0, tuning.minJumpDistancePercent)
        assertEquals(null, tuning.threshold)
    }

    @Test
    fun trackWithoutBranchTuningStartsFromDefaults() {
        for (raw in listOf(null, "", "am=lofi", "ah=1", "d=3,9", "ab=7")) {
            assertEquals(raw.toString(), DEFAULT_JUKEBOX_CONFIG, trackTuningConfig(TuningParamsCodec.parse(raw)))
        }
    }

    @Test
    fun savedMinJumpDistanceRestoresEveryStop() {
        for (percent in MIN_JUMP_DISTANCE_OPTIONS) {
            val tuning = engineTuningState(
                trackTuningConfig(TuningParamsCodec.parse("bl=$percent")),
                computedThreshold = null
            )
            assertEquals(percent, tuning.minJumpDistancePercent)
        }
    }

    @Test
    fun unsupportedMinJumpDistanceFallsBackToAnyDistance() {
        for (raw in listOf("bl=7", "bl=-5", "bl=abc", "bl=100")) {
            val tuning = engineTuningState(trackTuningConfig(TuningParamsCodec.parse(raw)), null)
            assertEquals(raw, 0, tuning.minJumpDistancePercent)
        }
    }

    @Test
    fun savedParamsChangeOnlyWhatTheyCarry() {
        val tuning = engineTuningState(trackTuningConfig(TuningParamsCodec.parse("bl=5")), null)

        assertEquals(TuningState(minJumpDistancePercent = 5), tuning)
    }

    @Test
    fun savedThresholdDoesNotTurnOnTheDistanceFilter() {
        val tuning = engineTuningState(trackTuningConfig(TuningParamsCodec.parse("thresh=40")), null)

        assertEquals(TuningState(threshold = 40), tuning)
    }

    @Test
    fun playlistEntriesEachPlayWithTheirOwnTuning() {
        // The engine sequence a load runs: reset to defaults, then lay the entry's params over them.
        val engine = JukeboxEngine(IdlePlayer())
        val entries = listOf("bl=5&thresh=40", null, "bl=10", "jb=1", "")
        val expected = listOf(
            TuningState(minJumpDistancePercent = 5, threshold = 40),
            TuningState(),
            TuningState(minJumpDistancePercent = 10),
            TuningState(justBackwards = true),
            TuningState()
        )

        val played = entries.map { raw ->
            engine.updateConfig(DEFAULT_JUKEBOX_CONFIG)
            val config = trackTuningConfig(TuningParamsCodec.parse(raw))
            if (config != DEFAULT_JUKEBOX_CONFIG) {
                engine.updateConfig(config)
            }
            engineTuningState(engine.getConfig(), computedThreshold = null)
        }

        assertEquals(expected, played)
    }

    @Test
    fun capturedTuningRoundTripsWithoutDrift() {
        val tuned = tunedConfig(minJumpDistancePercent = 5, threshold = 40)
        val captured = TuningParamsCodec.buildSavedTuningParams(engineTuningState(tuned, null))

        val restored = trackTuningConfig(TuningParamsCodec.parse(captured))

        assertEquals(engineTuningState(tuned, null), engineTuningState(restored, null))
    }

    @Test
    fun castResetSendsTheConstantDefaults() {
        val params = buildCastTuningResetParams(randomBranchDeltaPercentScale = 500.0)

        assertEquals("jb=0&bl=0&sq=1&thresh=0&bp=18,50,10&d=&am=off", params)
    }

    private fun tunedConfig(minJumpDistancePercent: Int, threshold: Int): JukeboxConfig {
        return JukeboxConfig(currentThreshold = threshold)
            .withMinimumJumpDistancePercent(minJumpDistancePercent)
    }

    private class IdlePlayer : JukeboxPlayer {
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun seek(time: Double) = Unit
        override fun scheduleJump(targetTime: Double, sourceStartTime: Double): Boolean = false
        override fun cancelScheduledJump() = Unit
        override fun getCurrentTime(): Double = 0.0
        override fun getAudioTime(): Double = 0.0
        override fun getPlaybackRate(): Double = 1.0
        override fun isPlaying(): Boolean = false
    }
}
