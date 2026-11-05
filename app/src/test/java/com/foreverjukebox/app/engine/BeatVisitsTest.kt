package com.foreverjukebox.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BeatVisitsTest {

    private val player = ClockPlayer()
    private val engine = JukeboxEngine(
        player,
        JukeboxEngineOptions(
            randomMode = RandomMode.Deterministic,
            config = JukeboxConfigUpdate(
                minRandomBranchChance = 0.0,
                maxRandomBranchChance = 0.0,
                randomBranchChanceDelta = 0.0
            )
        )
    ).also { it.loadAnalysis(makeAnalysisPayload(3)) }

    @Test
    fun startsAtZeroForEveryBeat() {
        assertEquals(listOf(0, 0, 0), engine.getBeatVisits())
    }

    @Test
    fun countsFirstBeatAndAdvances() {
        invokeAdvanceBeat(0.0)
        assertEquals(listOf(1, 0, 0), engine.getBeatVisits())

        invokeAdvanceBeat(1.0)
        assertEquals(listOf(1, 1, 0), engine.getBeatVisits())
    }

    @Test
    fun countsSeeks() {
        engine.seekToBeat(2)
        engine.seekToBeat(2)
        assertEquals(listOf(0, 0, 2), engine.getBeatVisits())
    }

    @Test
    fun countsWrapToStart() {
        engine.seekToBeat(2)
        val wrap = JukeboxEngine::class.java.getDeclaredMethod(
            "wrapPlaybackToStart",
            Double::class.javaPrimitiveType
        )
        wrap.isAccessible = true
        wrap.invoke(engine, 3.0)
        assertEquals(listOf(1, 0, 1), engine.getBeatVisits())
    }

    @Test
    fun resyncCountsOnlyWhenTheBeatChanges() {
        player.trackTime = 1.5
        engine.syncToPlaybackPosition()
        assertEquals(listOf(0, 1, 0), engine.getBeatVisits())

        engine.syncToPlaybackPosition()
        assertEquals(listOf(0, 1, 0), engine.getBeatVisits())

        player.trackTime = 2.5
        engine.syncToPlaybackPosition()
        assertEquals(listOf(0, 1, 1), engine.getBeatVisits())
    }

    @Test
    fun resetClearsCounts() {
        engine.seekToBeat(1)
        engine.resetStats()
        assertEquals(listOf(0, 0, 0), engine.getBeatVisits())
    }

    @Test
    fun clearAnalysisEmptiesCounts() {
        engine.seekToBeat(1)
        engine.clearAnalysis()
        assertEquals(emptyList<Int>(), engine.getBeatVisits())
    }

    @Test
    fun publishedSnapshotsDoNotChangeAfterwards() {
        engine.seekToBeat(1)
        val published = engine.getBeatVisits()
        engine.seekToBeat(1)
        assertEquals(listOf(0, 1, 0), published)
        assertEquals(listOf(0, 2, 0), engine.getBeatVisits())
    }

    @Test
    fun snapshotIsReusedUntilACountChanges() {
        engine.seekToBeat(1)
        assertSame(engine.getBeatVisits(), engine.getBeatVisits())
    }

    @Test
    fun emittedStateCarriesTheCounts() {
        var emitted: JukeboxState? = null
        engine.onUpdate { emitted = it }
        engine.seekToBeat(1)
        engine.resetStats()
        assertEquals(listOf(0, 0, 0), emitted?.beatVisits)
    }

    @Test
    fun visualizationDataCarriesOneColorPerBeat() {
        assertEquals(3, engine.getVisualizationData()?.beatColors?.size)
    }

    @Test
    fun waitsOutAShortLeadInWithoutDoubleCounting() {
        assertFirstBeatWaitsOutLeadIn(leadIn = 0.05)
    }

    @Test
    fun waitsOutALongLeadInWithoutDoubleCounting() {
        assertFirstBeatWaitsOutLeadIn(leadIn = 0.5)
    }

    // startJukebox launches the real tick loop on Dispatchers.Default, which can tick
    // before stopJukebox. The player sits on the start beat, as the controller's seek
    // leaves it, so a racing tick's playback resync finds no mismatch to record.
    @Test
    fun startAtBeatFromStoppedCountsOnlyThatBeat() {
        engine.seekToBeat(1)
        player.trackTime = 2.0
        engine.startJukebox(resetState = true, startBeat = 2)
        engine.stopJukebox()
        assertEquals(listOf(0, 0, 1), engine.getBeatVisits())
    }

    @Test
    fun startAtBeatFromPausedAddsOneVisit() {
        engine.seekToBeat(1)
        player.trackTime = 2.0
        engine.startJukebox(resetState = false, startBeat = 2)
        engine.stopJukebox()
        assertEquals(listOf(0, 1, 1), engine.getBeatVisits())
    }

    @Test
    fun lateFirstTickCountsOnlyTheBeatThePlayerIsOn() {
        val latePlayer = ClockPlayer()
        val lateEngine = JukeboxEngine(latePlayer)
        lateEngine.loadAnalysis(makeAnalysisPayload(4))
        val ticking = JukeboxEngine::class.java.getDeclaredField("ticking")
        ticking.isAccessible = true
        ticking.setBoolean(lateEngine, true)
        val tick = JukeboxEngine::class.java.getDeclaredMethod("tick")
        tick.isAccessible = true
        latePlayer.trackTime = 2.5
        latePlayer.audioClock = 5.0

        tick.invoke(lateEngine)

        assertEquals(listOf(0, 0, 1, 0), lateEngine.getBeatVisits())
    }

    private fun assertFirstBeatWaitsOutLeadIn(leadIn: Double) {
        val leadInPlayer = ClockPlayer()
        val leadInEngine = JukeboxEngine(
            leadInPlayer,
            JukeboxEngineOptions(
                randomMode = RandomMode.Deterministic,
                config = JukeboxConfigUpdate(
                    minRandomBranchChance = 0.0,
                    maxRandomBranchChance = 0.0,
                    randomBranchChanceDelta = 0.0
                )
            )
        ).also { it.loadAnalysis(makeAnalysisPayload(3, leadIn = leadIn)) }
        val ticking = JukeboxEngine::class.java.getDeclaredField("ticking")
        ticking.isAccessible = true
        ticking.setBoolean(leadInEngine, true)
        val tick = JukeboxEngine::class.java.getDeclaredMethod("tick")
        tick.isAccessible = true
        val tickAt = { time: Double ->
            leadInPlayer.trackTime = time
            leadInPlayer.audioClock = time
            tick.invoke(leadInEngine)
        }

        tickAt(0.0)
        assertEquals(listOf(1, 0, 0), leadInEngine.getBeatVisits())

        // One beat length in, the audio is still in the first beat.
        tickAt(1.0)
        assertEquals(listOf(1, 0, 0), leadInEngine.getBeatVisits())

        tickAt(leadIn + 1.05)
        assertEquals(listOf(1, 1, 0), leadInEngine.getBeatVisits())
    }

    private fun invokeAdvanceBeat(audioTime: Double) {
        val method = JukeboxEngine::class.java.getDeclaredMethod(
            "advanceBeat",
            Double::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(engine, audioTime)
    }

    private class ClockPlayer : JukeboxPlayer {
        var trackTime = 0.0
        var audioClock = 0.0

        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun seek(time: Double) = Unit
        override fun scheduleJump(targetTime: Double, sourceStartTime: Double): Boolean = true
        override fun cancelScheduledJump() = Unit
        override fun getCurrentTime(): Double = trackTime
        override fun getAudioTime(): Double = audioClock
        override fun getPlaybackRate(): Double = 1.0
        override fun isPlaying(): Boolean = true
    }
}
