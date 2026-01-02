package com.foreverjukebox.app.engine

enum class RandomMode {
    Random,
    Seeded,
    Deterministic
}

fun createRng(mode: RandomMode, seed: Int? = null): () -> Double {
    return when (mode) {
        RandomMode.Random -> { { kotlin.random.Random.nextDouble() } }
        RandomMode.Seeded,
        RandomMode.Deterministic -> {
            var t = seed ?: DEFAULT_SEED
            {
                t += MIX_CONSTANT
                var x = t
                x = (x xor (x ushr 15)) * (x or 1)
                x = x xor (x + (x xor (x ushr 7)) * (x or 61))
                ((x xor (x ushr 14)).toLong() and MASK_32).toDouble() / UINT32_RANGE
            }
        }
    }
}

private const val DEFAULT_SEED = 123456789
private const val MIX_CONSTANT = 0x6d2b79f5
private const val MASK_32 = 0xffffffffL
private const val UINT32_RANGE = 4294967296.0
