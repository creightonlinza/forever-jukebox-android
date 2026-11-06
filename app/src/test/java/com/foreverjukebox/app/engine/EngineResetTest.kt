package com.foreverjukebox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineResetTest {

    @Test
    fun resetReturnsAPlayingTunedEngineToItsBuiltState() {
        val player = RecordingPlayer()
        val engine = JukeboxEngine(player)
        engine.updateConfig(tunedConfig())
        engine.loadAnalysis(makeAnalysisPayload(8))
        engine.getGraphState()?.allEdges?.firstOrNull()?.let(engine::deleteEdge)
        engine.startJukebox()
        assertTrue(engine.isRunning())

        engine.reset()

        assertFalse(engine.isRunning())
        assertTrue(player.stops > 0)
        assertEquals(DEFAULT_JUKEBOX_CONFIG, engine.getConfig())
        assertNull(engine.getAnalysis())
        assertNull(engine.getGraphState())
        assertNull(engine.getVisualizationData())
        assertNull(engine.getUserAnchorEdgeId())
        assertEquals(emptyList<Int>(), engine.getBeatVisits())
    }

    @Test
    fun resetReturnsToTheConfigTheEngineWasBuiltWith() {
        val built = JukeboxConfigUpdate(minRandomBranchChance = 0.0, maxRandomBranchChance = 0.0)
        val engine = JukeboxEngine(RecordingPlayer(), JukeboxEngineOptions(config = built))
        val initial = engine.getConfig()
        engine.updateConfig(tunedConfig())

        engine.reset()

        assertEquals(initial, engine.getConfig())
    }

    @Test
    fun nextTrackAfterResetLoadsUntuned() {
        val engine = JukeboxEngine(RecordingPlayer())
        engine.updateConfig(tunedConfig())
        engine.loadAnalysis(makeAnalysisPayload(8))
        assertNotEquals(DEFAULT_JUKEBOX_CONFIG, engine.getConfig())

        engine.reset()
        engine.loadAnalysis(makeAnalysisPayload(8))

        val config = engine.getConfig()
        assertNotNull(engine.getGraphState())
        assertFalse(config.justLongBranches)
        assertEquals(DEFAULT_MIN_LONG_BRANCH_PERCENT, config.minLongBranchPercent)
        assertEquals(0, config.currentThreshold)
        assertEquals(DEFAULT_JUKEBOX_CONFIG, config.copy(minLongBranch = DEFAULT_JUKEBOX_CONFIG.minLongBranch))
    }

    @Test
    fun resetOnAnIdleEngineIsHarmless() {
        val engine = JukeboxEngine(RecordingPlayer())

        engine.reset()
        engine.reset()

        assertEquals(DEFAULT_JUKEBOX_CONFIG, engine.getConfig())
        assertFalse(engine.isRunning())
    }

    private fun tunedConfig(): JukeboxConfig =
        JukeboxConfig(currentThreshold = 40, justBackwards = true, removeSequentialBranches = true)
            .withMinimumJumpDistancePercent(5)

    private class RecordingPlayer : JukeboxPlayer {
        var stops = 0
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() {
            stops += 1
        }
        override fun seek(time: Double) = Unit
        override fun scheduleJump(targetTime: Double, sourceStartTime: Double): Boolean = false
        override fun cancelScheduledJump() = Unit
        override fun getCurrentTime(): Double = 0.0
        override fun getAudioTime(): Double = 0.0
        override fun getPlaybackRate(): Double = 1.0
        override fun isPlaying(): Boolean = false
    }
}
