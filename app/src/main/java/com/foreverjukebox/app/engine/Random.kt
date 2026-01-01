package com.foreverjukebox.app.engine

enum class RandomMode {
    Random,
    Seeded,
    Deterministic
}

fun createRng(mode: RandomMode, seed: Int? = null): () -> Double {
    if (mode == RandomMode.Random) {
        return { kotlin.random.Random.nextDouble() }
    }
    var t = seed ?: 123456789
    return {
        t += 0x6d2b79f5
        var x = t
        x = (x xor (x ushr 15)) * (x or 1)
        x = x xor (x + (x xor (x ushr 7)) * (x or 61))
        ((x xor (x ushr 14)).toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }
}
