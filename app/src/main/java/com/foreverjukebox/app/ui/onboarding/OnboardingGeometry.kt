package com.foreverjukebox.app.ui.onboarding

import androidx.compose.ui.geometry.Rect

/** Top-left of a callout next to its anchor, and where its caret meets the callout edge. */
data class CalloutPlacement(
    val x: Float,
    val y: Float,
    val below: Boolean,
    val caretX: Float
)

/**
 * Puts the callout below the anchor when it fits there or when there is more room below than
 * above, otherwise above. The callout stays [margin] inside the container and the caret stays
 * [caretInset] inside the callout's corners.
 */
@Suppress("LongParameterList")
fun placeCallout(
    containerWidth: Float,
    containerHeight: Float,
    anchor: Rect,
    calloutWidth: Float,
    calloutHeight: Float,
    margin: Float,
    gap: Float,
    caretInset: Float
): CalloutPlacement {
    val spaceBelow = containerHeight - margin - anchor.bottom - gap
    val spaceAbove = anchor.top - gap - margin
    val below = calloutHeight <= spaceBelow || spaceBelow >= spaceAbove
    val preferredY = if (below) anchor.bottom + gap else anchor.top - gap - calloutHeight
    val maxY = (containerHeight - margin - calloutHeight).coerceAtLeast(margin)
    val maxX = (containerWidth - margin - calloutWidth).coerceAtLeast(margin)
    val x = (anchor.center.x - calloutWidth / 2f).coerceIn(margin, maxX)
    val caretMax = (calloutWidth - caretInset).coerceAtLeast(caretInset)
    return CalloutPlacement(
        x = x,
        y = preferredY.coerceIn(margin, maxY),
        below = below,
        caretX = (anchor.center.x - x).coerceIn(caretInset, caretMax)
    )
}

/**
 * Left edges for labels centered under controls in a row. Labels keep [gap] between them and stay
 * inside the container; a label pushed in from an edge moves its neighbors along with it.
 */
fun spreadTags(centers: List<Float>, widths: List<Float>, containerWidth: Float, gap: Float): List<Float> {
    val order = centers.indices.sortedBy { centers[it] }
    val lefts = FloatArray(centers.size) { centers[it] - widths[it] / 2f }
    var minLeft = 0f
    for (index in order) {
        lefts[index] = maxOf(lefts[index], minLeft)
        minLeft = lefts[index] + widths[index] + gap
    }
    var maxRight = containerWidth
    for (index in order.asReversed()) {
        lefts[index] = minOf(lefts[index], maxRight - widths[index])
        maxRight = lefts[index] - gap
    }
    return lefts.toList()
}
