package com.foreverjukebox.app.visualization

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Classic layout proportions, as fractions of min(width, height).
internal const val CLASSIC_RADIUS_RATIO = 0.4f
private const val TILE_MAX_HEIGHT_RATIO = 0.1f
private const val TILE_MAX_WIDTH_RATIO = 0.03f

// A tile with n visits is n + TILE_BASE_STEPS steps long, of TILE_MAX_STEPS at full height.
private const val TILE_BASE_STEPS = 4
private const val TILE_MAX_STEPS = 12

// Zero-length lines can vanish along with their round caps.
private const val TILE_MIN_LINE_LENGTH = 0.01f

/** Tiles hold their size as counts rise, halving together when one would pass full height. */
internal fun tileScale(maxVisits: Int): Float {
    var scale = 1f
    while ((maxVisits + TILE_BASE_STEPS) * scale > TILE_MAX_STEPS) {
        scale /= 2f
    }
    return scale
}

/** A tile's length as a fraction of full height; 0 for a beat that has not played. */
internal fun tileLevel(visits: Int, scale: Float): Float {
    if (visits <= 0) return 0f
    return (visits + TILE_BASE_STEPS) * scale / TILE_MAX_STEPS
}

internal data class TileLineEnds(val start: Float, val end: Float)

/** Radial tile layout for [count] beats around the Classic ring, in pixels. */
internal class BeatTileGeometry(
    width: Float,
    height: Float,
    private val count: Int
) {
    private val side = min(width, height)
    private val centerX = width / 2f
    private val centerY = height / 2f
    private val radius = side * CLASSIC_RADIUS_RATIO

    val fullHeight: Float = side * TILE_MAX_HEIGHT_RATIO

    fun tileHeight(level: Float): Float = fullHeight * level

    fun tileWidth(minWidth: Float = 0f): Float {
        val slot = (2f * PI.toFloat()) / count
        return min(max(slot * radius, minWidth), side * TILE_MAX_WIDTH_RATIO)
    }

    /**
     * Distances from the center. Tiles span at least the beat dot's diameter, centered on the
     * ring, or their own width when wider. Round caps extend past the line ends, so the line
     * is inset to stay within that span.
     */
    fun tileLineEnds(height: Float, strokeWidth: Float, dotRadius: Float): TileLineEnds {
        val cap = strokeWidth / 2f
        val start = min(radius, radius - dotRadius + cap)
        val end = max(start + TILE_MIN_LINE_LENGTH, radius + max(height, dotRadius) - cap)
        return TileLineEnds(start, end)
    }

    fun tileAngle(index: Int): Double = index.toDouble() / count * PI * 2 - PI / 2

    /** The band runs from the beat ring to full tile height, so short tiles stay easy to hit. */
    fun tileIndexAt(x: Float, y: Float, selectMargin: Float): Int? {
        if (count <= 0) return null
        val dx = x - centerX
        val dy = y - centerY
        val distance = hypot(dx, dy)
        if (distance < radius - selectMargin || distance > radius + fullHeight) return null
        val turn = (atan2(dy.toDouble(), dx.toDouble()) + PI / 2) / (PI * 2)
        return ((turn + 1) * count).roundToInt() % count
    }
}
