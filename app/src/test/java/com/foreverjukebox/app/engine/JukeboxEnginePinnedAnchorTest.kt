package com.foreverjukebox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pinned `ab` branch id stays the anchor whatever the tuning filters drop. */
class JukeboxEnginePinnedAnchorTest {

    @Test
    fun keepsAPinAppliedBelowItsThresholdAsTheAnchor() {
        val (engine, player, pin) = loadWithPinFilteredOut()

        assertTrue(engine.applyAnchorParam(AnchorParam.EdgeId(pin.id)))

        assertEquals(AnchorParam.EdgeId(pin.id), engine.getAnchorParam())
        assertEquals(pin.id, engine.getVisualizationData()?.userAnchorEdgeId)
        assertEquals(1, offered(engine, pin.id))
        assertEquals(pin.dest.start to pin.src.start, player.lastAnchorJump)
    }

    @Test
    fun keepsThePinThroughARebuildWithoutASecondCopy() {
        val (engine, _, pin) = loadWithPinFilteredOut()
        engine.applyAnchorParam(AnchorParam.EdgeId(pin.id))

        engine.updateConfig(JukeboxConfigUpdate(currentThreshold = 0))
        engine.rebuildGraph()
        engine.updateConfig(JukeboxConfigUpdate(currentThreshold = LOW_THRESHOLD))
        engine.rebuildGraph()

        assertEquals(AnchorParam.EdgeId(pin.id), engine.getAnchorParam())
        assertEquals(1, offered(engine, pin.id))
    }

    @Test
    fun takesThePutBackEdgeOutWhenTheAnchorIsCleared() {
        val (engine, _, pin) = loadWithPinFilteredOut()
        engine.applyAnchorParam(AnchorParam.EdgeId(pin.id))

        engine.setUserAnchorEdge(null)
        assertEquals(0, offered(engine, pin.id))

        engine.applyAnchorParam(AnchorParam.EdgeId(pin.id))
        engine.clearDeletedEdges()
        assertEquals(0, offered(engine, pin.id))
        assertNull(engine.getAnchorParam())
    }

    @Test
    fun unpinsADeletedPin() {
        val (engine, _, pin) = loadWithPinFilteredOut()
        engine.applyAnchorParam(AnchorParam.EdgeId(pin.id))

        engine.deleteEdge(pin)
        engine.rebuildGraph()

        assertEquals(0, offered(engine, pin.id))
        assertNull(engine.getAnchorParam())
    }

    // The real CC0 analysis at a threshold below the pinned branch's distance.
    private fun loadWithPinFilteredOut(): Triple<JukeboxEngine, AnchorPlayer, Edge> {
        val player = AnchorPlayer()
        val engine = JukeboxEngine(player)
        engine.loadAnalysis(loadEngineParityFixture("real-analysis-cases.json")["analysis"]!!)
        engine.updateConfig(JukeboxConfigUpdate(currentThreshold = LOW_THRESHOLD))
        engine.rebuildGraph()
        val pin = engine.getGraphState()!!.allEdges.first {
            it.src.which == PIN_SRC && it.dest.which == PIN_DEST
        }
        assertTrue(pin.distance > LOW_THRESHOLD)
        assertEquals(0, offered(engine, pin.id))
        return Triple(engine, player, pin)
    }

    private fun offered(engine: JukeboxEngine, edgeId: Int): Int {
        val edge = engine.getGraphState()!!.allEdges.first { it.id == edgeId }
        return edge.src.neighbors.count { it.id == edgeId }
    }

    private class AnchorPlayer : JukeboxPlayer {
        var lastAnchorJump: Pair<Double, Double>? = null
        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun seek(time: Double) = Unit
        override fun scheduleJump(targetTime: Double, sourceStartTime: Double): Boolean = false
        override fun cancelScheduledJump() = Unit
        override fun setAnchorJump(targetTime: Double, sourceStartTime: Double): Boolean {
            lastAnchorJump = targetTime to sourceStartTime
            return true
        }
        override fun getCurrentTime(): Double = 0.0
        override fun getAudioTime(): Double = 0.0
        override fun getPlaybackRate(): Double = 1.0
        override fun isPlaying(): Boolean = false
    }

    private companion object {
        const val LOW_THRESHOLD = 19
        const val PIN_SRC = 254
        const val PIN_DEST = 174
    }
}
