package com.foreverjukebox.app.visualization

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.engine.Edge
import com.foreverjukebox.app.engine.VisualizationData
import com.foreverjukebox.app.ui.LocalThemeTokens
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val EDGE_SAMPLE_LIMIT = 300
private const val EDGE_AVOID_RADIUS = 6f
private const val BEAT_SELECT_THRESHOLD = 16f
private const val ARC_VERTICAL_GUTTER = 4f
private val TILE_DOT_RADIUS = 2.dp
private val TILE_MARKER_MIN_WIDTH = 3.dp
private val TILE_SELECT_MARGIN = 8.dp

class JumpLine(val from: Int, val to: Int, val startedAt: Long)

@Composable
fun JukeboxVisualization(
    data: VisualizationData?,
    currentIndex: Int,
    jumpLine: JumpLine?,
    positioner: Positioner,
    onSelectBeat: (Int) -> Unit,
    modifier: Modifier = Modifier,
    edgeRouting: EdgeRouting = EdgeRouting.Auto,
    highlightAnchorBranch: Boolean = false,
    beatTiles: Boolean = false,
    beatVisits: List<Int> = emptyList()
) {
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    val positions = remember(data?.beats?.size, layoutSize, positioner) {
        if (data == null || layoutSize.width == 0 || layoutSize.height == 0) {
            emptyList()
        } else {
            positioner(data, layoutSize.width.toFloat(), layoutSize.height.toFloat())
        }
    }

    val center = Offset(layoutSize.width / 2f, layoutSize.height / 2f)
    val themeTokens = LocalThemeTokens.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { layoutSize = it }
            .pointerInput(data, positions, beatTiles) {
                detectTapGestures { tap ->
                    if (data == null || positions.isEmpty()) return@detectTapGestures
                    val beatIndex = if (beatTiles) {
                        BeatTileGeometry(
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            count = positions.size
                        ).tileIndexAt(tap.x, tap.y, TILE_SELECT_MARGIN.toPx())
                    } else {
                        findNearestBeat(tap, positions)
                    }
                    if (beatIndex != null) {
                        onSelectBeat(beatIndex)
                        return@detectTapGestures
                    }
                    // Branch selection disabled.
                }
            }
    ) {
        BaseLayer(
            data = data,
            positions = positions,
            center = center,
            edgeStroke = themeTokens.edgeStroke,
            anchorHighlight = themeTokens.danger,
            beatFill = themeTokens.beatFill,
            showBeatDots = !beatTiles,
            edgeRouting = edgeRouting,
            highlightAnchorBranch = highlightAnchorBranch
        )
        if (beatTiles) {
            TileLayer(
                beatColors = data?.beatColors.orEmpty(),
                beatVisits = beatVisits,
                beatCount = positions.size,
                fallbackColor = themeTokens.beatFill
            )
        }
        OverlayLayer(
            positions = positions,
            currentIndex = currentIndex,
            jumpLine = jumpLine,
            center = center,
            beatHighlight = themeTokens.beatHighlight,
            beatTiles = beatTiles,
            edgeRouting = edgeRouting
        )
    }
}

// The base and tile layers each record into their own graphics layer, so a sibling's
// invalidation replays them instead of re-running their draw code.
@Composable
private fun BaseLayer(
    data: VisualizationData?,
    positions: List<VizPoint>,
    center: Offset,
    edgeStroke: Color,
    anchorHighlight: Color,
    beatFill: Color,
    showBeatDots: Boolean,
    edgeRouting: EdgeRouting,
    highlightAnchorBranch: Boolean
) {
    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { }) {
        if (data == null || positions.isEmpty()) return@Canvas

        val edges = data.edges
        val maxEdges = 2500
        val step = if (edges.size > maxEdges) {
            kotlin.math.ceil(edges.size / maxEdges.toDouble()).toInt()
        } else {
            1
        }

        for (i in edges.indices step step) {
            val edge = edges[i]
            if (edge.deleted) continue
            drawEdge(edge, positions, center, edgeStroke, 1.0f, edgeRouting)
        }

        if (highlightAnchorBranch && data.anchorEdgeId != null) {
            for (edge in edges) {
                if (edge.deleted || edge.id != data.anchorEdgeId) {
                    continue
                }
                drawEdge(edge, positions, center, anchorHighlight, 1.8f, edgeRouting)
                break
            }
        }

        if (showBeatDots) {
            for (p in positions) {
                drawCircle(beatFill, radius = 2f, center = Offset(p.x, p.y))
            }
        }
    }
}

@Composable
private fun TileLayer(
    beatColors: List<String>,
    beatVisits: List<Int>,
    beatCount: Int,
    fallbackColor: Color
) {
    val colors = remember(beatColors, fallbackColor) {
        beatColors.map { parseColor(it, fallbackColor) }
    }
    Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { }) {
        if (beatCount == 0) return@Canvas
        val geometry = BeatTileGeometry(size.width, size.height, beatCount)
        val scale = tileScale(beatVisits.maxOrNull() ?: 0)
        for (i in 0 until beatCount) {
            val level = tileLevel(beatVisits.getOrElse(i) { 0 }, scale)
            drawTile(geometry, i, geometry.tileHeight(level), colors.getOrNull(i) ?: fallbackColor)
        }
    }
}

@Composable
private fun OverlayLayer(
    positions: List<VizPoint>,
    currentIndex: Int,
    jumpLine: JumpLine?,
    center: Offset,
    beatHighlight: Color,
    beatTiles: Boolean,
    edgeRouting: EdgeRouting
) {
    var nowState by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(jumpLine) {
        if (jumpLine == null) return@LaunchedEffect
        while (SystemClock.elapsedRealtime() - jumpLine.startedAt < 1100) {
            nowState = SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(16)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        if (positions.isEmpty()) return@Canvas

        if (currentIndex >= 0 && currentIndex < positions.size) {
            if (beatTiles) {
                val geometry = BeatTileGeometry(size.width, size.height, positions.size)
                drawTile(
                    geometry = geometry,
                    index = currentIndex,
                    height = geometry.fullHeight,
                    color = beatHighlight,
                    minWidth = TILE_MARKER_MIN_WIDTH.toPx()
                )
            } else {
                val p = positions[currentIndex]
                drawCircle(beatHighlight, radius = 10f, center = Offset(p.x, p.y))
            }
        }

        if (jumpLine != null) {
            val age = (nowState - jumpLine.startedAt).coerceAtLeast(0L)
            if (age < 1000) {
                val from = positions.getOrNull(jumpLine.from)
                val to = positions.getOrNull(jumpLine.to)
                if (from != null && to != null) {
                    val alpha = (1f - age / 1000f).coerceIn(0f, 1f)
                    val color = beatHighlight.copy(alpha = alpha)
                    drawJumpLine(
                        from = from,
                        to = to,
                        positions = positions,
                        center = center,
                        color = color,
                        edgeRouting = edgeRouting,
                        fromIndex = jumpLine.from,
                        toIndex = jumpLine.to
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTile(
    geometry: BeatTileGeometry,
    index: Int,
    height: Float,
    color: Color,
    minWidth: Float = 0f
) {
    val strokeWidth = geometry.tileWidth(minWidth)
    val ends = geometry.tileLineEnds(height, strokeWidth, TILE_DOT_RADIUS.toPx())
    val angle = geometry.tileAngle(index)
    val cosine = cos(angle).toFloat()
    val sine = sin(angle).toFloat()
    drawLine(
        color = color,
        start = center + Offset(cosine * ends.start, sine * ends.start),
        end = center + Offset(cosine * ends.end, sine * ends.end),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round
    )
}

private fun findNearestBeat(tap: Offset, positions: List<VizPoint>): Int? {
    var bestIndex = -1
    var bestDist = Float.MAX_VALUE
    for (i in positions.indices) {
        val p = positions[i]
        val dist = hypot(p.x - tap.x, p.y - tap.y)
        if (dist < bestDist) {
            bestDist = dist
            bestIndex = i
        }
    }
    return if (bestIndex >= 0 && bestDist <= BEAT_SELECT_THRESHOLD) bestIndex else null
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEdge(
    edge: Edge,
    positions: List<VizPoint>,
    center: Offset,
    color: Color,
    strokeWidth: Float,
    edgeRouting: EdgeRouting
) {
    val from = positions.getOrNull(edge.src.which) ?: return
    val to = positions.getOrNull(edge.dest.which) ?: return
    val geometry = resolveEdgeGeometry(
        from = from,
        to = to,
        positions = positions,
        center = center,
        edgeRouting = edgeRouting,
        fromIndex = edge.src.which,
        toIndex = edge.dest.which
    )
    if (geometry.bend && geometry.control != null) {
        val (cx, cy) = geometry.control
        val path = Path().apply {
            moveTo(from.x, from.y)
            quadraticTo(cx, cy, to.x, to.y)
        }
        drawPath(path, color = color, style = Stroke(width = strokeWidth))
    } else {
        drawLine(color, Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = strokeWidth)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawJumpLine(
    from: VizPoint,
    to: VizPoint,
    positions: List<VizPoint>,
    center: Offset,
    color: Color,
    edgeRouting: EdgeRouting,
    fromIndex: Int,
    toIndex: Int
) {
    val geometry = resolveEdgeGeometry(
        from = from,
        to = to,
        positions = positions,
        center = center,
        edgeRouting = edgeRouting,
        fromIndex = fromIndex,
        toIndex = toIndex
    )
    if (geometry.bend && geometry.control != null) {
        val (cx, cy) = geometry.control
        val path = Path().apply {
            moveTo(from.x, from.y)
            quadraticTo(cx, cy, to.x, to.y)
        }
        drawPath(path, color = color, style = Stroke(width = 2f))
    } else {
        drawLine(color, Offset(from.x, from.y), Offset(to.x, to.y), strokeWidth = 2f)
    }
}

private data class EdgeGeometry(
    val bend: Boolean,
    val control: Pair<Float, Float>?
)

private fun resolveEdgeGeometry(
    from: VizPoint,
    to: VizPoint,
    positions: List<VizPoint>,
    center: Offset,
    edgeRouting: EdgeRouting,
    fromIndex: Int,
    toIndex: Int
): EdgeGeometry {
    if (edgeRouting == EdgeRouting.ArcDiagram) {
        return EdgeGeometry(
            bend = true,
            control = arcDiagramControlPoint(from, to, center, fromIndex, toIndex)
        )
    }
    val bend = shouldBendEdge(from, to, positions)
    return EdgeGeometry(
        bend = bend,
        control = if (bend) bendControlPoint(from, to, center) else null
    )
}

private fun arcDiagramControlPoint(
    from: VizPoint,
    to: VizPoint,
    center: Offset,
    fromIndex: Int,
    toIndex: Int
): Pair<Float, Float> {
    val midX = (from.x + to.x) / 2f
    val baseY = (from.y + to.y) / 2f
    val span = abs(to.x - from.x)
    val forward = toIndex >= fromIndex
    val direction = if (forward) -1f else 1f
    val canvasHeight = center.y * 2f
    val availableLift = if (direction < 0f) {
        baseY - ARC_VERTICAL_GUTTER
    } else {
        canvasHeight - baseY - ARC_VERTICAL_GUTTER
    }
    val maxLift = max(4f, availableLift)
    val minLift = min(18f, maxLift)
    val desiredLift = max(minLift, span * 0.95f)
    val lift = min(maxLift, desiredLift)
    return midX to (baseY + direction * lift)
}

private fun shouldBendEdge(
    from: VizPoint,
    to: VizPoint,
    positions: List<VizPoint>
): Boolean {
    if (positions.isEmpty()) return false
    val step = max(1, kotlin.math.ceil(positions.size / EDGE_SAMPLE_LIMIT.toDouble()).toInt())
    for (i in positions.indices step step) {
        val p = positions[i]
        if ((p.x == from.x && p.y == from.y) || (p.x == to.x && p.y == to.y)) {
            continue
        }
        val dist = distanceToSegment(p.x, p.y, from.x, from.y, to.x, to.y)
        if (dist <= EDGE_AVOID_RADIUS) return true
    }
    return false
}

private fun bendControlPoint(from: VizPoint, to: VizPoint, center: Offset): Pair<Float, Float> {
    val midX = (from.x + to.x) / 2f
    val midY = (from.y + to.y) / 2f
    val dirX = center.x - midX
    val dirY = center.y - midY
    val dirLen = hypot(dirX, dirY)
    if (dirLen == 0f) return midX to midY
    val normX = dirX / dirLen
    val normY = dirY / dirLen
    val centerDist = hypot(center.x - midX, center.y - midY)
    return (midX + normX * (centerDist * 0.5f)) to (midY + normY * (centerDist * 0.5f))
}

private fun distanceToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x2 - x1
    val dy = y2 - y1
    if (dx == 0f && dy == 0f) {
        return hypot(px - x1, py - y1)
    }
    val t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)
    val clamped = max(0f, min(1f, t))
    val cx = x1 + clamped * dx
    val cy = y1 + clamped * dy
    return hypot(px - cx, py - cy)
}
