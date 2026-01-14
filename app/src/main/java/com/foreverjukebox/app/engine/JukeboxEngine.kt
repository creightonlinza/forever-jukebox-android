package com.foreverjukebox.app.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import android.os.SystemClock
import kotlin.math.min

interface JukeboxPlayer {
    fun play()
    fun pause()
    fun stop()
    fun seek(time: Double)
    fun scheduleJump(targetTime: Double, transitionTime: Double)
    fun getCurrentTime(): Double
    fun isPlaying(): Boolean
}

class JukeboxEngine(
    private val player: JukeboxPlayer,
    options: JukeboxEngineOptions = JukeboxEngineOptions()
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var tickJob: Job? = null
    private var analysis: TrackAnalysis? = null
    private var graph: JukeboxGraphState? = null
    private var config: JukeboxConfig = JukeboxConfig()
    private var beats: MutableList<QuantumBase> = mutableListOf()
    private var ticking = false
    private var currentBeatIndex = -1
    private var nextTransitionTime = 0.0
    private var beatsPlayed = 0
    private var curRandomBranchChance = 0.0
    private var lastJumped = false
    private var lastJumpTime: Double? = null
    private var lastJumpFromIndex: Int? = null
    private var lastTickTime: Double? = null
    private var forceBranch = false
    private var ignoreResyncUntilMs: Long = 0
    private val deletedEdgeKeys = mutableSetOf<String>()
    private val rng = createRng(options.randomMode, options.seed)
    private val listeners = mutableSetOf<(JukeboxState) -> Unit>()

    init {
        config = config.copy(
            maxBranches = config.maxBranches,
            maxBranchThreshold = config.maxBranchThreshold,
            currentThreshold = config.currentThreshold
        )
        options.config?.let { updateConfig(it) }
    }

    fun onUpdate(callback: (JukeboxState) -> Unit) {
        listeners.add(callback)
    }

    fun removeUpdateListener(callback: (JukeboxState) -> Unit) {
        listeners.remove(callback)
    }

    fun loadAnalysis(data: JsonElement) {
        deletedEdgeKeys.clear()
        analysis = normalizeAnalysis(data)
        val beatsCount = analysis?.beats?.size ?: 0
        config = config.copy(minLongBranch = beatsCount / 5)
        graph = analysis?.let { buildJumpGraph(it, config) }
        applyDeletedEdges()
        beats = analysis?.beats ?: mutableListOf()
        resetState()
    }

    fun clearAnalysis() {
        deletedEdgeKeys.clear()
        analysis = null
        graph = null
        beats = mutableListOf()
        resetState()
    }

    fun getGraphState(): JukeboxGraphState? = graph

    fun getConfig(): JukeboxConfig = config.copy()

    fun updateConfig(partial: JukeboxConfig) {
        config = partial
    }

    fun rebuildGraph() {
        val current = analysis ?: return
        config = config.copy(minLongBranch = current.beats.size / 5)
        graph = buildJumpGraph(current, config)
        curRandomBranchChance = config.minRandomBranchChance
        applyDeletedEdges()
    }

    fun getVisualizationData(): VisualizationData? {
        val current = analysis ?: return null
        graph ?: return null
        val edgeMap = linkedMapOf<String, Edge>()
        for (beat in current.beats) {
            for (edge in beat.neighbors) {
                if (edge.deleted) continue
                val key = "${edge.src.which}-${edge.dest.which}"
                edgeMap.putIfAbsent(key, edge)
            }
        }
        return VisualizationData(current.beats, edgeMap.values.toMutableList())
    }

    fun play() = player.play()

    fun pause() = player.pause()

    fun startJukebox() {
        if (analysis == null || beats.isEmpty()) {
            throw IllegalStateException("Analysis not loaded")
        }
        if (ticking) return
        resetState()
        ticking = true
        tickJob = scope.launch {
            while (ticking) {
                tick()
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    fun stopJukebox() {
        ticking = false
        tickJob?.cancel()
        tickJob = null
        player.stop()
    }

    fun resetStats() {
        resetState()
        emitState(false)
    }

    fun isRunning(): Boolean = ticking

    fun clearDeletedEdges() {
        deletedEdgeKeys.clear()
    }

    fun deleteEdge(edge: Edge) {
        deletedEdgeKeys.add(edgeKey(edge.src.which, edge.dest.which))
        deletedEdgeKeys.add(edgeKey(edge.dest.which, edge.src.which))
        applyDeletedEdges()
    }

    fun setForceBranch(enabled: Boolean) {
        forceBranch = enabled
    }

    fun getBeatAtTime(time: Double): QuantumBase? {
        if (analysis == null || beats.isEmpty()) return null
        val idx = findBeatIndexByTime(time)
        return if (idx >= 0) beats[idx] else null
    }

    private fun resetState() {
        currentBeatIndex = -1
        nextTransitionTime = 0.0
        beatsPlayed = 0
        curRandomBranchChance = config.minRandomBranchChance
        lastJumped = false
        lastJumpTime = null
        lastJumpFromIndex = null
        lastTickTime = null
        ignoreResyncUntilMs = 0
    }

    private fun tick() {
        if (!ticking || analysis == null) return
        if (!player.isPlaying()) {
            emitState(false)
            lastTickTime = null
            return
        }

        val currentTime = player.getCurrentTime()
        val previousTickTime = lastTickTime
        lastTickTime = currentTime
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs >= ignoreResyncUntilMs) {
            if (
                currentBeatIndex < 0 ||
                currentTime < beats[currentBeatIndex].start - RESYNC_TOLERANCE_SECONDS ||
                currentTime > beats[currentBeatIndex].start + beats[currentBeatIndex].duration + RESYNC_TOLERANCE_SECONDS
            ) {
                currentBeatIndex = findBeatIndexByTime(currentTime)
                if (currentBeatIndex >= 0) {
                    nextTransitionTime = beats[currentBeatIndex].start + beats[currentBeatIndex].duration
                }
            }
        }

        if (
            currentBeatIndex >= 0 &&
            previousTickTime != null &&
            previousTickTime < nextTransitionTime &&
            currentTime >= nextTransitionTime
        ) {
            advanceBeat()
        }

        emitState(lastJumped)
        lastJumped = false
    }

    private fun advanceBeat() {
        val currentGraph = graph ?: return
        val nextIndex = currentBeatIndex + 1
        val wrappedIndex = if (nextIndex >= beats.size) 0 else nextIndex
        val seed = beats[wrappedIndex]
        val branchState = BranchState(curRandomBranchChance)
        val selection = selectNextBeatIndex(
            seed,
            currentGraph,
            config,
            rng,
            branchState,
            forceBranch
        )
        curRandomBranchChance = branchState.curRandomBranchChance
        val chosenIndex = selection.first
        if (chosenIndex != wrappedIndex) {
            val targetBeat = beats[chosenIndex]
            val unclampedOffset = targetBeat.duration * JUMP_OFFSET_FRACTION
            val offset = unclampedOffset.coerceIn(MIN_JUMP_OFFSET_SECONDS, MAX_JUMP_OFFSET_SECONDS)
            val maxOffset = (targetBeat.duration - JUMP_OFFSET_EPSILON).coerceAtLeast(0.0)
            val targetTime = targetBeat.start + min(offset, maxOffset)
            player.scheduleJump(targetTime, nextTransitionTime)
            lastJumped = true
            lastJumpTime = targetTime
            lastJumpFromIndex = wrappedIndex
            val holdMs = (beats[chosenIndex].duration * 1000.0)
                .toLong()
                .coerceAtLeast(MIN_JUMP_HOLD_MS)
            ignoreResyncUntilMs = SystemClock.elapsedRealtime() + holdMs
        } else {
            lastJumpFromIndex = null
        }

        currentBeatIndex = chosenIndex
        nextTransitionTime = beats[currentBeatIndex].start + beats[currentBeatIndex].duration
        beatsPlayed += 1
    }

    private fun findBeatIndexByTime(time: Double): Int {
        var low = 0
        var high = beats.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val beat = beats[mid]
            if (time < beat.start) {
                high = mid - 1
            } else if (time >= beat.start + beat.duration) {
                low = mid + 1
            } else {
                return mid
            }
        }
        return (low - 1).coerceIn(0, beats.size - 1)
    }

    private fun applyDeletedEdges() {
        val current = graph ?: return
        if (deletedEdgeKeys.isEmpty()) return
        for (edge in current.allEdges) {
            if (deletedEdgeKeys.contains(edgeKey(edge.src.which, edge.dest.which))) {
                edge.deleted = true
            }
        }
    }

    private fun edgeKey(src: Int, dest: Int): String = "$src-$dest"

    private fun emitState(jumped: Boolean) {
        val currentGraph = graph ?: return
        if (listeners.isEmpty()) return
        val state = JukeboxState(
            currentBeatIndex = currentBeatIndex,
            beatsPlayed = beatsPlayed,
            currentTime = player.getCurrentTime(),
            lastJumped = jumped,
            lastJumpTime = lastJumpTime,
            lastJumpFromIndex = lastJumpFromIndex,
            currentThreshold = currentGraph.currentThreshold,
            lastBranchPoint = currentGraph.lastBranchPoint,
            curRandomBranchChance = curRandomBranchChance
        )
        listeners.forEach { it(state) }
    }
}

data class JukeboxEngineOptions(
    val randomMode: RandomMode = RandomMode.Random,
    val seed: Int? = null,
    val config: JukeboxConfig? = null
)

data class VisualizationData(
    val beats: List<QuantumBase>,
    val edges: MutableList<Edge>
)

private const val TICK_INTERVAL_MS = 50L
private const val RESYNC_TOLERANCE_SECONDS = 0.05
private const val MIN_JUMP_HOLD_MS = 200L
private const val JUMP_OFFSET_FRACTION = 0.06
private const val MIN_JUMP_OFFSET_SECONDS = 0.015
private const val MAX_JUMP_OFFSET_SECONDS = 0.05
private const val JUMP_OFFSET_EPSILON = 0.001
