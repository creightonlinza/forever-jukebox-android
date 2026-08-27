package com.foreverjukebox.app.export

import com.foreverjukebox.app.engine.BranchState
import com.foreverjukebox.app.engine.JukeboxConfig
import com.foreverjukebox.app.engine.JukeboxGraphState
import com.foreverjukebox.app.engine.QuantumBase
import com.foreverjukebox.app.engine.TrackAnalysis
import com.foreverjukebox.app.engine.UserAnchorSelection
import com.foreverjukebox.app.engine.buildJumpGraph
import com.foreverjukebox.app.engine.selectExistingAnchorSource
import com.foreverjukebox.app.engine.selectNextBeatIndex

/**
 * Precomputes a jukebox branch path for offline export.
 *
 * Runs the same selection primitives as [com.foreverjukebox.app.engine.JukeboxEngine]
 * against its own freshly built graph (selection mutates graph edge order, so the
 * live graph must not be shared). The [analysis] must therefore be a fresh parse —
 * [buildJumpGraph] mutates the analysis's beats. Real-time-only concerns (schedule
 * lead, rejection rollback, drift resync) have no offline equivalent and are omitted.
 */
class JukeboxPathGenerator(
    analysis: TrackAnalysis,
    private val config: JukeboxConfig,
    deletedEdgePairs: Set<Pair<Int, Int>>,
    userAnchorPair: Pair<Int, Int>?,
    private val rng: () -> Double
) {
    data class PlannedJump(
        val sourceBoundaryTime: Double,
        val targetTime: Double,
        val targetIndex: Int
    )

    /**
     * One beat of playback: [beat] plays until [boundarySourceTime] (source-time
     * seconds), where the path either continues linearly or takes [jump].
     * [nextBeatInTimeline] is the timeline successor (for cowbell planning),
     * not the path successor.
     */
    data class Step(
        val beatIndex: Int,
        val beat: QuantumBase,
        val nextBeatInTimeline: QuantumBase?,
        val boundarySourceTime: Double,
        val jump: PlannedJump?
    )

    private val beats: List<QuantumBase> = analysis.beats
    private var graph: JukeboxGraphState = buildJumpGraph(analysis, config)
    private val branchState = BranchState(config.minRandomBranchChance)
    private val userAnchor: UserAnchorSelection?
    private var currentIndex = -1

    init {
        applyDeletions(analysis, deletedEdgePairs)
        userAnchor = resolveUserAnchor(userAnchorPair)
    }

    /** The beat the path starts on; the wrap target when playback passes the last beat. */
    fun firstBeat(): QuantumBase? = beats.firstOrNull()

    fun nextStep(): Step? {
        if (beats.isEmpty()) return null
        if (currentIndex < 0) currentIndex = 0
        val playingIndex = currentIndex
        val advance = selectAdvance(playingIndex)
        currentIndex = advance.chosenIndex
        return Step(
            beatIndex = playingIndex,
            beat = beats[playingIndex],
            nextBeatInTimeline = beats.getOrNull(playingIndex + 1),
            boundarySourceTime = advance.sourceBoundaryTime,
            jump = if (advance.shouldJump) {
                PlannedJump(
                    sourceBoundaryTime = advance.sourceBoundaryTime,
                    targetTime = beats[advance.chosenIndex].start,
                    targetIndex = advance.chosenIndex
                )
            } else {
                null
            }
        )
    }

    private data class Advance(
        val chosenIndex: Int,
        val shouldJump: Boolean,
        val sourceBoundaryTime: Double
    )

    // Mirrors JukeboxEngine.createPendingAdvance: the seed is the timeline
    // successor, a branch fires at the seed's start, and wrapping past the last
    // beat is a forced jump whose boundary is the final beat's end.
    private fun selectAdvance(currentIndex: Int): Advance {
        val nextIndex = currentIndex + 1
        val wrappedIndex = if (nextIndex >= beats.size) 0 else nextIndex
        val seed = beats[wrappedIndex]
        val wrappedToStart = wrappedIndex == 0 && currentIndex == beats.size - 1
        val sourceBoundaryTime = if (wrappedToStart) {
            beats[currentIndex].start + beats[currentIndex].duration
        } else {
            seed.start
        }
        val selection = selectNextBeatIndex(
            seed = seed,
            graph = graph,
            config = config,
            rng = rng,
            state = branchState,
            forceBranch = false,
            userAnchor = userAnchor
        )
        val branched = selection.second
        val chosenIndex = if (branched) selection.first else wrappedIndex
        return Advance(
            chosenIndex = chosenIndex,
            shouldJump = branched || wrappedToStart,
            sourceBoundaryTime = sourceBoundaryTime
        )
    }

    // Mirrors JukeboxEngine.applyDeletedEdges + ensureAnchorSourceHasNeighbors,
    // keyed by (src, dest) beat pairs so live deletions carry into the fresh graph.
    private fun applyDeletions(analysis: TrackAnalysis, pairs: Set<Pair<Int, Int>>) {
        if (pairs.isEmpty()) return
        for (edge in graph.allEdges) {
            if (edge.src.which to edge.dest.which in pairs) {
                edge.deleted = true
            }
        }
        for (beat in analysis.beats) {
            for (edge in beat.allNeighbors) {
                if (edge.src.which to edge.dest.which in pairs) {
                    edge.deleted = true
                }
            }
            beat.neighbors = beat.neighbors.filter { !it.deleted }.toMutableList()
        }
        if (graph.lastBranchPoint >= 0) {
            val refreshed = selectExistingAnchorSource(analysis.beats, config.minLongBranch)
            graph = graph.copy(lastBranchPoint = refreshed ?: -1)
        }
    }

    // Mirrors JukeboxEngine.getUserAnchorEdge: the anchor must exist in the fresh
    // graph, not be deleted, and have survived branch filtering.
    private fun resolveUserAnchor(pair: Pair<Int, Int>?): UserAnchorSelection? {
        if (pair == null) return null
        val edge = graph.allEdges.firstOrNull {
            !it.deleted && it.src.which == pair.first && it.dest.which == pair.second
        } ?: return null
        if (edge.src.neighbors.none { it.id == edge.id }) return null
        return UserAnchorSelection(edgeId = edge.id, sourceIndex = edge.src.which)
    }
}
