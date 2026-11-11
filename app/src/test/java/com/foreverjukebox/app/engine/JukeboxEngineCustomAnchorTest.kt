package com.foreverjukebox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors JukeboxEngine.customAnchor.test.ts in the web repo's shared engine.
class JukeboxEngineCustomAnchorTest {

    @Test
    fun rejectsInvalidPairsWithoutChangingTheAnchor() {
        val engine = loadedEngine()
        assertTrue(engine.setCustomAnchor(20, 5))
        assertFalse(engine.setCustomAnchor(5, 20))
        assertFalse(engine.setCustomAnchor(99, 5))
        assertFalse(engine.setCustomAnchor(20, 20 - MIN_CUSTOM_ANCHOR_GAP + 1))
        assertEquals(CustomAnchor(20, 5), engine.getCustomAnchor())
    }

    @Test
    fun refusesACustomAnchorBeforeAnAnalysisIsLoaded() {
        assertFalse(JukeboxEngine(AnchorPlayer()).setCustomAnchor(20, 5))
    }

    @Test
    fun armsThePlayerJumpAtTheCustomPair() {
        val player = AnchorPlayer()
        val engine = loadedEngine(player)
        engine.setCustomAnchor(20, 5)
        assertEquals(5.0 to 20.0, player.lastAnchorJump)
    }

    @Test
    fun exposesTheCustomEdgeAsTheUserAnchorInVisualizationData() {
        val engine = loadedEngine()
        engine.setCustomAnchor(20, 5)
        val data = engine.getVisualizationData()!!
        assertEquals(CUSTOM_ANCHOR_EDGE_ID, data.userAnchorEdgeId)
        assertEquals(CUSTOM_ANCHOR_EDGE_ID, data.anchorEdgeId)
        val edge = data.edges.single { it.id == CUSTOM_ANCHOR_EDGE_ID }
        assertEquals(20, edge.src.which)
        assertEquals(5, edge.dest.which)
    }

    @Test
    fun survivesAGraphRebuildAndStaysOutOfAllEdges() {
        val engine = loadedEngine()
        engine.setCustomAnchor(20, 5)
        engine.updateConfig(engine.getConfig().copy(justBackwards = true, currentThreshold = 1))
        engine.rebuildGraph()
        assertEquals(CUSTOM_ANCHOR_EDGE_ID, engine.getUserAnchorEdgeId())
        val beats = engine.getVisualizationData()!!.beats
        assertEquals(1, beats[20].neighbors.count { it.id == CUSTOM_ANCHOR_EDGE_ID })
        assertTrue(engine.getGraphState()!!.allEdges.none { it.id == CUSTOM_ANCHOR_EDGE_ID })
    }

    @Test
    fun replacesThePreviousCustomEdgeInsteadOfStackingIt() {
        val engine = loadedEngine()
        engine.setCustomAnchor(20, 5)
        engine.setCustomAnchor(24, 8)
        val beats = engine.getVisualizationData()!!.beats
        assertTrue(beats[20].neighbors.none { it.id == CUSTOM_ANCHOR_EDGE_ID })
        assertTrue(beats[24].neighbors.any { it.id == CUSTOM_ANCHOR_EDGE_ID })
    }

    @Test
    fun clearsOnDeletePinnedAnchorTuningResetAndNewAnalysis() {
        val engine = loadedEngine()
        engine.setCustomAnchor(20, 5)
        engine.deleteEdge(engine.getVisualizationData()!!.edges.single { isCustomAnchorEdge(it) })
        assertNull(engine.getCustomAnchor())
        assertTrue(engine.getGraphState()!!.allEdges.none { it.deleted })

        engine.setCustomAnchor(20, 5)
        engine.setUserAnchorEdge(null)
        assertNull(engine.getCustomAnchor())

        engine.setCustomAnchor(20, 5)
        engine.clearDeletedEdges()
        assertNull(engine.getCustomAnchor())

        engine.setCustomAnchor(20, 5)
        engine.loadAnalysis(makeAnalysisPayload(BEATS))
        assertNull(engine.getCustomAnchor())
    }

    @Test
    fun tuningResetClearsAPinnedBranchAnchor() {
        val engine = loadedEngine()
        val backward = engine.getGraphState()!!.allEdges.first { edge ->
            edge.dest.which < edge.src.which && edge.src.neighbors.any { it === edge }
        }
        assertTrue(engine.applyAnchorParam(AnchorParam.EdgeId(backward.id)))
        engine.clearDeletedEdges()
        assertNull(engine.getAnchorParam())
    }

    @Test
    fun scoresTheCustomEdgeWithTheDistanceARealBranchGets() {
        val engine = loadedEngine()
        val real = engine.getGraphState()!!.allEdges.first {
            it.dest.which < it.src.which - MIN_CUSTOM_ANCHOR_GAP
        }
        engine.setCustomAnchor(real.src.which, real.dest.which)
        val custom = engine.getVisualizationData()!!.edges.single { isCustomAnchorEdge(it) }
        assertEquals(real.distance, custom.distance, 0.0)
    }

    @Test
    fun roundTripsTheAnchorThroughAb() {
        val engine = loadedEngine()
        assertNull(engine.getAnchorParam())
        assertTrue(engine.applyAnchorParam(parseAnchorParam("20-5")))
        assertEquals(AnchorParam.Custom(CustomAnchor(20, 5)), engine.getAnchorParam())
        assertEquals(AnchorEndpoints(20, 5, custom = true), engine.getUserAnchorEdgeEndpoints())

        val backward = engine.getGraphState()!!.allEdges.first { edge ->
            edge.dest.which < edge.src.which && edge.src.neighbors.any { it === edge }
        }
        assertTrue(engine.applyAnchorParam(AnchorParam.EdgeId(backward.id)))
        assertEquals(AnchorParam.EdgeId(backward.id), engine.getAnchorParam())
        assertNull(engine.getCustomAnchor())
        assertEquals(
            AnchorEndpoints(backward.src.which, backward.dest.which, custom = false),
            engine.getUserAnchorEdgeEndpoints()
        )
    }

    @Test
    fun refusesAnAbValueThatDoesNotFitThisTrack() {
        val engine = loadedEngine()
        assertFalse(engine.applyAnchorParam(AnchorParam.Custom(CustomAnchor(BEATS + 10, 5))))
        assertFalse(engine.applyAnchorParam(AnchorParam.EdgeId(999_999)))
        val forward = engine.getGraphState()!!.allEdges.first { it.dest.which > it.src.which }
        assertFalse(engine.applyAnchorParam(AnchorParam.EdgeId(forward.id)))
        assertNull(engine.getAnchorParam())
    }

    @Test
    fun forcesTheCustomJumpWhenPlaybackReachesItsSource() {
        val engine = loadedEngine()
        engine.setCustomAnchor(20, 5)
        val graph = engine.getGraphState()
        assertNotNull(graph)
        val seed = engine.getVisualizationData()!!.beats[20]
        val (index, jumped) = selectNextBeatIndex(
            seed = seed,
            graph = graph!!,
            config = engine.getConfig(),
            rng = { 0.99 },
            state = BranchState(curRandomBranchChance = 0.0),
            forceBranch = false,
            userAnchor = UserAnchorSelection(edgeId = CUSTOM_ANCHOR_EDGE_ID, sourceIndex = 20)
        )
        assertEquals(5, index)
        assertTrue(jumped)
    }

    private fun loadedEngine(player: AnchorPlayer = AnchorPlayer()): JukeboxEngine {
        val engine = JukeboxEngine(player)
        engine.loadAnalysis(makeAnalysisPayload(BEATS))
        return engine
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
        const val BEATS = 32
    }
}
