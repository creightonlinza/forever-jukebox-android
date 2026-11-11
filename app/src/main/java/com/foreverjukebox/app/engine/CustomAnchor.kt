package com.foreverjukebox.app.engine

// Custom anchors: a user-chosen jump between any two beats, carried in the `ab` tuning param as
// "src-dest". Parity with the web's shared selection.ts (custom-anchor-cases.json pins both).

/** Real edge ids are list indexes, so a negative id never collides with one. */
const val CUSTOM_ANCHOR_EDGE_ID = -1

/** Shorter loops sit next to freeze-beat territory and restart the player too often. */
const val MIN_CUSTOM_ANCHOR_GAP = 4

data class CustomAnchor(val src: Int, val dest: Int)

/** The `ab` tuning value: a pinned branch id ("12") or a custom beat pair ("289-23"). */
sealed interface AnchorParam {
    data class EdgeId(val id: Int) : AnchorParam
    data class Custom(val anchor: CustomAnchor) : AnchorParam
}

/** A custom anchor always jumps backward, so it always closes a loop. */
fun isValidCustomAnchor(beatCount: Int, src: Int, dest: Int): Boolean =
    dest >= 0 && src < beatCount && src - dest >= MIN_CUSTOM_ANCHOR_GAP

fun isCustomAnchorEdge(edge: Edge): Boolean = edge.id == CUSTOM_ANCHOR_EDGE_ID

private val EDGE_ID_PATTERN = Regex("^[0-9]{1,9}$")
private val PAIR_PATTERN = Regex("^([0-9]{1,7})-([0-9]{1,7})$")

fun parseAnchorParam(raw: String?): AnchorParam? {
    val value = raw?.trim().orEmpty()
    if (EDGE_ID_PATTERN.matches(value)) {
        return AnchorParam.EdgeId(value.toInt())
    }
    val match = PAIR_PATTERN.matchEntire(value) ?: return null
    val src = match.groupValues[1].toInt()
    val dest = match.groupValues[2].toInt()
    // Beat count is unknown here; the engine checks the range against the loaded track.
    return if (isValidCustomAnchor(Int.MAX_VALUE, src, dest)) {
        AnchorParam.Custom(CustomAnchor(src, dest))
    } else {
        null
    }
}

fun formatAnchorParam(param: AnchorParam): String = when (param) {
    is AnchorParam.EdgeId -> param.id.toString()
    is AnchorParam.Custom -> "${param.anchor.src}-${param.anchor.dest}"
}

/** The synthetic edge for a custom pair; it lives only in its source beat's neighbors. */
fun createCustomAnchorEdge(beats: List<QuantumBase>, anchor: CustomAnchor): Edge? {
    if (!isValidCustomAnchor(beats.size, anchor.src, anchor.dest)) {
        return null
    }
    val src = beats[anchor.src]
    val dest = beats[anchor.dest]
    return Edge(
        id = CUSTOM_ANCHOR_EDGE_ID,
        src = src,
        dest = dest,
        distance = beatPairDistance(src, dest),
        deleted = false
    )
}

/** The user anchor's endpoints for a fresh graph; [custom] marks a pair with no real edge. */
data class AnchorEndpoints(val src: Int, val dest: Int, val custom: Boolean)
