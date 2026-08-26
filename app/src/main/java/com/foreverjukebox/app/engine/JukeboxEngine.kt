package com.foreverjukebox.app.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

interface JukeboxPlayer {
    fun play()
    fun pause()
    fun stop()
    fun seek(time: Double)
    fun scheduleJump(targetTime: Double, sourceStartTime: Double): Boolean
    fun cancelScheduledJump()
    fun setAnchorJump(targetTime: Double, sourceStartTime: Double): Boolean = false
    fun clearAnchorJump() = Unit
    fun consumeJumpEvent(): JumpEvent? = null
    fun getCurrentTime(): Double
    fun getAudioTime(): Double
    fun getPlaybackRate(): Double
    fun isPlaying(): Boolean
}

class JukeboxEngine(
    private val player: JukeboxPlayer,
    options: JukeboxEngineOptions = JukeboxEngineOptions(),
    private val graphBuilder: (TrackAnalysis, JukeboxConfig) -> JukeboxGraphState = ::buildJumpGraph
) {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var tickJob: Job? = null
    private var analysis: TrackAnalysis? = null
    private var graph: JukeboxGraphState? = null
    private var config: JukeboxConfig = JukeboxConfig()
    private var beats: MutableList<QuantumBase> = mutableListOf()
    private var ticking = false
    private var currentBeatIndex = -1
    private var nextAudioTime = 0.0
    private var beatsPlayed = 0
    private var curRandomBranchChance = 0.0
    private var lastJumped = false
    private var lastJumpTime: Double? = null
    private var lastJumpFromIndex: Int? = null
    private var lastJumpToIndex: Int? = null
    private var lastTickTime: Double? = null
    private var forceBranch = false
    private var pendingAdvance: PendingAdvance? = null
    private val deletedEdgeKeys = mutableSetOf<String>()
    private var userAnchorEdgeId: Int? = null
    private val rng = createRng(options.randomMode, options.seed)
    private val listeners = CopyOnWriteArraySet<(JukeboxState) -> Unit>()
    private val branchState = BranchState(0.0)

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

    fun loadAnalysis(data: JsonElement) {
        deletedEdgeKeys.clear()
        userAnchorEdgeId = null
        analysis = normalizeAnalysis(data)
        val beatsCount = analysis?.beats?.size ?: 0
        config = config.copy(
            minLongBranch = minimumBranchBeats(
                totalBeats = beatsCount,
                percent = config.minLongBranchPercent
            )
        )
        graph = analysis?.let { graphBuilder(it, config) }
        applyDeletedEdges()
        beats = analysis?.beats ?: mutableListOf()
        resetState()
        syncAnchorJump()
    }

    fun clearAnalysis() {
        deletedEdgeKeys.clear()
        userAnchorEdgeId = null
        analysis = null
        graph = null
        beats = mutableListOf()
        resetState()
        syncAnchorJump()
    }

    fun getGraphState(): JukeboxGraphState? = graph

    fun getConfig(): JukeboxConfig = config.copy()

    fun refreshAnchorJump() {
        syncAnchorJump()
    }

    fun setUserAnchorEdge(edge: Edge?) {
        userAnchorEdgeId = edge?.id
        clearPendingAdvance(cancelScheduledJump = true)
        syncAnchorJump()
    }

    fun getUserAnchorEdgeId(): Int? = getUserAnchorEdge()?.id

    /**
     * The user anchor as (source, dest) beat indices, reading only
     * structurally stable graph state — unlike [getUserAnchorEdgeId], it skips
     * the playability check that iterates per-beat neighbor lists the tick
     * loop mutates, so it is safe to call from any thread while the jukebox
     * runs. Callers that rebuild a fresh graph re-validate playability there.
     */
    fun getUserAnchorEdgeEndpoints(): Pair<Int, Int>? {
        val currentGraph = graph ?: return null
        val anchorId = userAnchorEdgeId ?: return null
        val edge = currentGraph.allEdges.firstOrNull { it.id == anchorId } ?: return null
        if (edge.deleted) return null
        return edge.src.which to edge.dest.which
    }

    fun updateConfig(partial: JukeboxConfigUpdate) {
        config = config.applyUpdate(partial)
    }

    fun updateConfig(partial: JukeboxConfig) {
        config = partial
    }

    fun rebuildGraph() {
        val current = analysis ?: return
        clearPendingAdvance(cancelScheduledJump = true)
        clearEdgeDeletionFlags()
        config = config.copy(
            minLongBranch = minimumBranchBeats(
                totalBeats = current.beats.size,
                percent = config.minLongBranchPercent
            )
        )
        graph = graphBuilder(current, config)
        curRandomBranchChance = config.minRandomBranchChance
        branchState.curRandomBranchChance = curRandomBranchChance
        applyDeletedEdges()
        syncAnchorJump()
    }

    fun getVisualizationData(): VisualizationData? {
        val current = analysis ?: return null
        val currentGraph = graph ?: return null
        val edgeMap = linkedMapOf<String, Edge>()
        for (beat in current.beats) {
            for (edge in beat.neighbors) {
                if (edge.deleted) continue
                val key = "${edge.src.which}-${edge.dest.which}"
                edgeMap.putIfAbsent(key, edge)
            }
        }
        val userAnchorEdge = getUserAnchorEdge()
        val defaultAnchorEdge = getDefaultAnchorEdge()
        val anchorEdgeId = userAnchorEdge?.id ?: defaultAnchorEdge?.id
        val userAnchorEdgeId = userAnchorEdge?.id?.takeIf { it != defaultAnchorEdge?.id }
        return VisualizationData(
            beats = current.beats,
            edges = edgeMap.values.toMutableList(),
            lastBranchPoint = currentGraph.lastBranchPoint,
            anchorEdgeId = anchorEdgeId,
            userAnchorEdgeId = userAnchorEdgeId
        )
    }

    fun play() = player.play()

    fun pause() = player.pause()

    fun startJukebox(resetState: Boolean = true) {
        if (analysis == null || beats.isEmpty()) {
            throw IllegalStateException("Analysis not loaded")
        }
        if (ticking) return
        if (resetState) {
            resetState()
        }
        ticking = true
        tickJob = scope.launch {
            while (ticking) {
                val delayMs = tick()
                delay(delayMs)
            }
        }
    }

    fun pauseJukebox() {
        if (!ticking) {
            player.pause()
            return
        }
        ticking = false
        tickJob?.cancel()
        tickJob = null
        player.pause()
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
        clearPendingAdvance(cancelScheduledJump = true)
        clearEdgeDeletionFlags()
        syncAnchorJump()
    }

    fun deleteEdge(edge: Edge) {
        deletedEdgeKeys.add(edgeKey(edge.src.which, edge.dest.which))
        clearPendingAdvance(cancelScheduledJump = true)
        applyDeletedEdges()
        syncAnchorJump()
    }

    fun setForceBranch(enabled: Boolean) {
        forceBranch = enabled
    }

    fun getBeatAtTime(time: Double): QuantumBase? {
        if (analysis == null || beats.isEmpty()) return null
        val idx = findBeatIndexByTime(time)
        return if (idx >= 0) beats[idx] else null
    }

    fun getSectionStartBeatIndices(): List<Int> {
        val currentAnalysis = analysis ?: return emptyList()
        if (beats.isEmpty()) return emptyList()
        val indices = sortedSetOf<Int>()
        for (section in currentAnalysis.sections.drop(1)) {
            val index = findBeatIndexAtOrAfterTime(section.start)
            if (index > 0) {
                indices += index
            }
        }
        return indices.toList()
    }

    fun seekToBeat(index: Int) {
        if (analysis == null || beats.isEmpty()) return
        val clamped = index.coerceIn(0, beats.size - 1)
        val beat = beats[clamped]
        val audioNow = player.getAudioTime()
        val playbackRate = getPlaybackRate()
        currentBeatIndex = clamped
        nextAudioTime = audioNow + beat.duration / playbackRate
        curRandomBranchChance = config.minRandomBranchChance
        branchState.curRandomBranchChance = curRandomBranchChance
        lastJumped = false
        lastJumpTime = null
        lastJumpFromIndex = null
        lastJumpToIndex = null
        clearPendingAdvance(cancelScheduledJump = false)
    }

    fun syncToPlaybackPosition() {
        snapToPlaybackPosition()
    }

    private fun resetState() {
        currentBeatIndex = -1
        nextAudioTime = 0.0
        beatsPlayed = 0
        curRandomBranchChance = config.minRandomBranchChance
        branchState.curRandomBranchChance = curRandomBranchChance
        branchState.lastDestBySource = null
        lastJumped = false
        lastJumpTime = null
        lastJumpFromIndex = null
        lastJumpToIndex = null
        lastTickTime = null
        clearPendingAdvance(cancelScheduledJump = true)
    }

    private fun minimumBranchBeats(totalBeats: Int, percent: Int): Int {
        val safePercent = percent.takeIf { it > 0 } ?: DEFAULT_MIN_LONG_BRANCH_PERCENT
        return totalBeats * safePercent.coerceAtMost(100) / 100
    }

    private fun tick(): Long {
        if (!ticking || analysis == null) return TICK_INTERVAL_MS
        if (!player.isPlaying()) {
            emitState(false)
            lastTickTime = null
            return TICK_INTERVAL_MS
        }

        val audioTime = player.getAudioTime()
        lastTickTime = audioTime
        if (hasReachedFinalBeatBoundary(player.getCurrentTime())) {
            wrapPlaybackToStart(audioTime)
            emitState(true)
            lastJumped = false
            return TICK_INTERVAL_MS
        }
        if (nextAudioTime == 0.0) {
            nextAudioTime = audioTime
        }
        var guard = beats.size
        preparePendingAdvance(nextAudioTime)
        while (guard > 0 && audioTime >= nextAudioTime) {
            advanceBeat(nextAudioTime)
            preparePendingAdvance(nextAudioTime)
            guard -= 1
        }

        verifyPlaybackSync()
        consumePromotedJumpEvent()
        emitState(lastJumped)
        lastJumped = false
        val remainingMs = ((nextAudioTime - player.getAudioTime()) * 1000.0 - 10.0)
            .coerceAtLeast(0.0)
        return remainingMs.toLong()
    }

    private fun hasReachedFinalBeatBoundary(trackTime: Double): Boolean {
        if (beats.isEmpty() || !trackTime.isFinite()) return false
        val lastBeat = beats.last()
        val finalBoundary = lastBeat.start + lastBeat.duration
        return finalBoundary.isFinite() && trackTime >= finalBoundary
    }

    private fun wrapPlaybackToStart(audioTime: Double) {
        val firstBeat = beats.firstOrNull() ?: return
        clearPendingAdvance(cancelScheduledJump = true)
        player.seek(firstBeat.start)
        currentBeatIndex = 0
        nextAudioTime = audioTime + firstBeat.duration / getPlaybackRate()
        beatsPlayed += 1
        lastJumped = true
        lastJumpTime = firstBeat.start
        lastJumpFromIndex = beats.lastIndex
        lastJumpToIndex = 0
    }

    private fun advanceBeat(audioTime: Double) {
        val advance = pendingAdvance?.takeIf { it.boundaryAudioTime == audioTime }
            ?: createPendingAdvance(audioTime, scheduleJump = true)
            ?: return
        pendingAdvance = null
        commitAdvance(advance)
    }

    private fun preparePendingAdvance(boundaryAudioTime: Double) {
        if (currentBeatIndex < 0 || boundaryAudioTime <= 0.0) {
            return
        }
        val current = pendingAdvance
        if (current != null && current.boundaryAudioTime == boundaryAudioTime) {
            return
        }
        pendingAdvance = createPendingAdvance(boundaryAudioTime, scheduleJump = true)
    }

    private fun createPendingAdvance(
        boundaryAudioTime: Double,
        scheduleJump: Boolean
    ): PendingAdvance? {
        val currentGraph = graph ?: return null
        val currentIndex = currentBeatIndex
        val beatsCount = beats.size
        var chosenIndex = 0
        var shouldJump = false
        var jumpFromIndex: Int? = null
        var sourceBoundaryTime: Double? = null
        var selectedSeed: QuantumBase? = null
        var previousRandomBranchChance = curRandomBranchChance
        var previousLastDestBySource: MutableMap<Int, Int>? = null
        var previousNeighbors: List<Edge> = emptyList()

        if (currentIndex >= 0) {
            val nextIndex = currentIndex + 1
            val wrappedIndex = if (nextIndex >= beatsCount) 0 else nextIndex
            val seed = beats[wrappedIndex]
            val wrappedToStart = wrappedIndex == 0 && currentIndex == beatsCount - 1
            val fallbackIndex = if (wrappedToStart) currentIndex else wrappedIndex
            selectedSeed = seed
            sourceBoundaryTime = if (wrappedToStart) {
                beats[currentIndex].start + beats[currentIndex].duration
            } else {
                seed.start
            }
            if (!wrappedToStart && !hasJumpScheduleLead(sourceBoundaryTime)) {
                return PendingAdvance(
                    boundaryAudioTime = boundaryAudioTime,
                    chosenIndex = fallbackIndex,
                    shouldJump = false,
                    targetTime = null,
                    jumpFromIndex = null,
                    sourceBoundaryTime = null
                )
            }
            previousRandomBranchChance = curRandomBranchChance
            previousLastDestBySource = branchState.lastDestBySource?.toMutableMap()
            previousNeighbors = seed.neighbors.toList()
            branchState.curRandomBranchChance = curRandomBranchChance
            val selection = selectNextBeatIndex(
                seed,
                currentGraph,
                config,
                rng,
                branchState,
                forceBranch,
                getActiveUserAnchorSelection()
            )
            curRandomBranchChance = branchState.curRandomBranchChance
            shouldJump = selection.second
            chosenIndex = if (shouldJump) selection.first else wrappedIndex
            if (wrappedToStart) {
                shouldJump = true
            }
            jumpFromIndex = if (shouldJump) {
                if (selection.second) seed.which else currentIndex
            } else {
                null
            }
        }

        val targetBeat = beats.getOrNull(chosenIndex) ?: return null
        val targetTime = if (shouldJump) targetBeat.start else null
        if (scheduleJump && targetTime != null && sourceBoundaryTime != null) {
            if (!player.scheduleJump(targetTime, sourceBoundaryTime)) {
                selectedSeed?.let { seed ->
                    restoreRejectedBranchSelection(
                        seed = seed,
                        previousNeighbors = previousNeighbors,
                        previousLastDestBySource = previousLastDestBySource,
                        previousRandomBranchChance = previousRandomBranchChance
                    )
                }
                val fallbackIndex = if (currentIndex == beats.lastIndex && chosenIndex == 0) {
                    currentIndex
                } else {
                    (currentIndex + 1).coerceAtMost(beats.lastIndex)
                }
                return PendingAdvance(
                    boundaryAudioTime = boundaryAudioTime,
                    chosenIndex = fallbackIndex,
                    shouldJump = false,
                    targetTime = null,
                    jumpFromIndex = null,
                    sourceBoundaryTime = null
                )
            }
        }
        return PendingAdvance(
            boundaryAudioTime = boundaryAudioTime,
            chosenIndex = chosenIndex,
            shouldJump = shouldJump,
            targetTime = targetTime,
            jumpFromIndex = jumpFromIndex,
            sourceBoundaryTime = sourceBoundaryTime
        )
    }

    private fun restoreRejectedBranchSelection(
        seed: QuantumBase,
        previousNeighbors: List<Edge>,
        previousLastDestBySource: MutableMap<Int, Int>?,
        previousRandomBranchChance: Double
    ) {
        seed.neighbors = previousNeighbors.toMutableList()
        branchState.lastDestBySource = previousLastDestBySource
        curRandomBranchChance = previousRandomBranchChance
        branchState.curRandomBranchChance = previousRandomBranchChance
    }

    private fun commitAdvance(advance: PendingAdvance) {
        val targetBeat = beats[advance.chosenIndex]
        if (advance.shouldJump) {
            lastJumped = true
            lastJumpTime = advance.targetTime
            lastJumpFromIndex = advance.jumpFromIndex
            lastJumpToIndex = advance.chosenIndex
        } else {
            lastJumped = false
            lastJumpTime = null
            lastJumpFromIndex = null
            lastJumpToIndex = null
        }

        currentBeatIndex = advance.chosenIndex
        nextAudioTime = advance.boundaryAudioTime + targetBeat.duration / getPlaybackRate()
        beatsPlayed += 1
    }

    private fun clearPendingAdvance(cancelScheduledJump: Boolean) {
        if (cancelScheduledJump && pendingAdvance?.targetTime != null) {
            player.cancelScheduledJump()
        }
        pendingAdvance = null
    }

    private fun hasJumpScheduleLead(sourceBoundaryTime: Double): Boolean {
        return sourceBoundaryTime - player.getCurrentTime() >= MIN_JUMP_SCHEDULE_LEAD_SECONDS
    }

    private fun getPlaybackRate(): Double {
        val rate = player.getPlaybackRate()
        return if (rate.isFinite() && rate > 0.0) rate else 1.0
    }

    private fun verifyPlaybackSync(): Boolean {
        if (analysis == null || beats.isEmpty()) return false
        val trackTime = player.getCurrentTime()
        val beatIndex = findBeatIndexByTime(trackTime)
        if (beatIndex !in beats.indices) return false
        val expectedNextAudioTime = nextAudioTimeForBeatAt(
            beatIndex = beatIndex,
            trackTime = trackTime,
            audioTime = player.getAudioTime()
        )
        val beatMismatch = currentBeatIndex != beatIndex
        val boundaryMismatch = abs(nextAudioTime - expectedNextAudioTime) > SYNC_TOLERANCE_SECONDS
        if (!beatMismatch && !boundaryMismatch) return false
        snapToPlaybackPosition(
            beatIndex = beatIndex,
            nextBoundaryAudioTime = expectedNextAudioTime
        )
        return true
    }

    private fun snapToPlaybackPosition() {
        if (analysis == null || beats.isEmpty()) return
        val trackTime = player.getCurrentTime()
        val beatIndex = findBeatIndexByTime(trackTime)
        if (beatIndex !in beats.indices) return
        snapToPlaybackPosition(
            beatIndex = beatIndex,
            nextBoundaryAudioTime = nextAudioTimeForBeatAt(
                beatIndex = beatIndex,
                trackTime = trackTime,
                audioTime = player.getAudioTime()
            )
        )
    }

    private fun snapToPlaybackPosition(
        beatIndex: Int,
        nextBoundaryAudioTime: Double
    ) {
        currentBeatIndex = beatIndex
        nextAudioTime = nextBoundaryAudioTime
        lastJumped = false
        lastJumpTime = null
        lastJumpFromIndex = null
        lastJumpToIndex = null
        clearPendingAdvance(cancelScheduledJump = true)
    }

    private fun consumePromotedJumpEvent() {
        val event = player.consumeJumpEvent() ?: return
        if (!event.sourceStartTime.isFinite() || !event.targetTime.isFinite()) return
        val sourceIndex = findBeatIndexByTime(event.sourceStartTime)
        val targetIndex = findBeatIndexByTime(event.targetTime)
        if (sourceIndex !in beats.indices || targetIndex !in beats.indices) return
        lastJumped = true
        lastJumpTime = event.targetTime
        lastJumpFromIndex = sourceIndex
        lastJumpToIndex = targetIndex
    }

    private fun nextAudioTimeForBeatAt(
        beatIndex: Int,
        trackTime: Double,
        audioTime: Double
    ): Double {
        val beat = beats[beatIndex]
        val beatEnd = beat.start + beat.duration
        val remainingInBeat = (beatEnd - trackTime).coerceAtLeast(0.0)
        return audioTime + remainingInBeat / getPlaybackRate()
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

    private fun findBeatIndexAtOrAfterTime(time: Double): Int {
        var low = 0
        var high = beats.size - 1
        var result = beats.size - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val beat = beats[mid]
            if (beat.start >= time) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    private fun applyDeletedEdges() {
        val current = graph ?: return
        val currentAnalysis = analysis ?: return
        if (deletedEdgeKeys.isEmpty()) return
        for (edge in current.allEdges) {
            if (deletedEdgeKeys.contains(edgeKey(edge.src.which, edge.dest.which))) {
                edge.deleted = true
            }
        }
        for (beat in currentAnalysis.beats) {
            for (edge in beat.allNeighbors) {
                if (deletedEdgeKeys.contains(edgeKey(edge.src.which, edge.dest.which))) {
                    edge.deleted = true
                }
            }
            beat.neighbors = beat.neighbors.filter { !it.deleted }.toMutableList()
        }
        ensureAnchorSourceHasNeighbors()
    }

    private fun ensureAnchorSourceHasNeighbors() {
        val current = graph ?: return
        val currentAnalysis = analysis ?: return
        if (current.lastBranchPoint < 0) {
            return
        }
        val refreshedAnchorSource = selectExistingAnchorSource(
            currentAnalysis.beats,
            config.minLongBranch
        )
        graph = current.copy(lastBranchPoint = refreshedAnchorSource ?: -1)
    }

    private fun getUserAnchorEdge(): Edge? {
        val currentGraph = graph ?: return null
        val anchorId = userAnchorEdgeId ?: return null
        val edge = currentGraph.allEdges.firstOrNull { it.id == anchorId } ?: return null
        if (edge.deleted) {
            return null
        }
        // The anchor must have survived branch filtering to be playable.
        return edge.takeIf { candidate ->
            candidate.src.neighbors.any { it.id == candidate.id }
        }
    }

    private fun getDefaultAnchorEdge(): Edge? {
        val currentGraph = graph ?: return null
        val anchorSource = beats.getOrNull(currentGraph.lastBranchPoint) ?: return null
        if (anchorSource.neighbors.isEmpty()) {
            return null
        }
        val bestIndex = getBestLastBranchNeighborIndex(anchorSource)
        val bestEdge = anchorSource.neighbors.getOrNull(bestIndex)
        return bestEdge?.takeIf { !it.deleted }
    }

    private fun getActiveAnchorEdge(): Edge? {
        return getUserAnchorEdge() ?: getDefaultAnchorEdge()
    }

    private fun getActiveUserAnchorSelection(): UserAnchorSelection? {
        val edge = getUserAnchorEdge() ?: return null
        return UserAnchorSelection(edgeId = edge.id, sourceIndex = edge.src.which)
    }

    private fun syncAnchorJump() {
        val edge = getActiveAnchorEdge() ?: run {
            player.clearAnchorJump()
            return
        }
        val targetTime = edge.dest.start
        val sourceStartTime = edge.src.start
        if (!targetTime.isFinite() || !sourceStartTime.isFinite()) {
            player.clearAnchorJump()
            return
        }
        if (!player.setAnchorJump(targetTime, sourceStartTime)) {
            player.clearAnchorJump()
        }
    }

    private fun clearEdgeDeletionFlags() {
        val currentAnalysis = analysis ?: return
        graph?.allEdges?.forEach { edge ->
            edge.deleted = false
        }
        for (beat in currentAnalysis.beats) {
            for (edge in beat.allNeighbors) {
                edge.deleted = false
            }
            for (edge in beat.neighbors) {
                edge.deleted = false
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
            lastJumpToIndex = lastJumpToIndex,
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
    val config: JukeboxConfigUpdate? = null
)

data class VisualizationData(
    val beats: List<QuantumBase>,
    val edges: MutableList<Edge>,
    val lastBranchPoint: Int = -1,
    val anchorEdgeId: Int? = null,
    val userAnchorEdgeId: Int? = null
)

private data class PendingAdvance(
    val boundaryAudioTime: Double,
    val chosenIndex: Int,
    val shouldJump: Boolean,
    val targetTime: Double?,
    val jumpFromIndex: Int?,
    val sourceBoundaryTime: Double?
)

private const val MIN_JUMP_SCHEDULE_LEAD_SECONDS = 0.08
private const val SYNC_TOLERANCE_SECONDS = 0.075
private const val TICK_INTERVAL_MS = 50L
