package com.foreverjukebox.app.engine

data class BranchState(var curRandomBranchChance: Double)

fun shouldRandomBranch(
    q: QuantumBase,
    graph: JukeboxGraphState,
    config: JukeboxConfig,
    rng: () -> Double,
    state: BranchState
): Boolean {
    if (q.which == graph.lastBranchPoint) {
        return true
    }
    state.curRandomBranchChance += config.randomBranchChanceDelta
    if (state.curRandomBranchChance > config.maxRandomBranchChance) {
        state.curRandomBranchChance = config.maxRandomBranchChance
    }
    val shouldBranch = rng() < state.curRandomBranchChance
    if (shouldBranch) {
        state.curRandomBranchChance = config.minRandomBranchChance
    }
    return shouldBranch
}

fun selectNextBeatIndex(
    seed: QuantumBase,
    graph: JukeboxGraphState,
    config: JukeboxConfig,
    rng: () -> Double,
    state: BranchState,
    forceBranch: Boolean
): Pair<Int, Boolean> {
    if (seed.neighbors.isEmpty()) {
        return seed.which to false
    }
    if (!forceBranch && !shouldRandomBranch(seed, graph, config, rng, state)) {
        return seed.which to false
    }
    val nextEdge = seed.neighbors.removeFirstOrNull() ?: return seed.which to false
    seed.neighbors.add(nextEdge)
    val nextIndex = nextEdge.dest.which
    return nextIndex to (nextIndex != seed.which)
}
