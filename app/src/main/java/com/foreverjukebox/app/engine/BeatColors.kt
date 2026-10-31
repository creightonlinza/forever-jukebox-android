package com.foreverjukebox.app.engine

import kotlin.math.roundToInt

private const val NO_SEGMENT_COLOR = "#333333"

/**
 * One hex color per beat, from timbre coefficients 1-3 normalized across the track.
 * [beatSegments] holds each beat's overlapping segments; the first one sets its color.
 */
fun computeBeatColors(beatSegments: List<List<Segment>>, segments: List<Segment>): List<String> {
    val minValues = doubleArrayOf(100.0, 100.0, 100.0)
    val maxValues = doubleArrayOf(-100.0, -100.0, -100.0)

    for (segment in segments) {
        for (i in 0..2) {
            val value = segment.timbre.getOrNull(i + 1) ?: continue
            if (value < minValues[i]) {
                minValues[i] = value
            }
            if (value > maxValues[i]) {
                maxValues[i] = value
            }
        }
    }

    return beatSegments.map { overlapping ->
        val segment = overlapping.firstOrNull() ?: return@map NO_SEGMENT_COLOR
        val rgb = IntArray(3)
        for (i in 0..2) {
            val value = segment.timbre.getOrNull(i + 1) ?: 0.0
            val range = maxValues[i] - minValues[i]
            val norm = if (range == 0.0) 0.5 else (value - minValues[i]) / range
            rgb[i] = (norm * 255.0).roundToInt().coerceIn(0, 255)
        }
        toHex(rgb[1], rgb[2], rgb[0])
    }
}

private fun toHex(r: Int, g: Int, b: Int): String {
    return "#${toHexPart(r)}${toHexPart(g)}${toHexPart(b)}"
}

private fun toHexPart(value: Int): String {
    return value.toString(16).padStart(2, '0')
}
