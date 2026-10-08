package com.foreverjukebox.app.visualization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import com.foreverjukebox.app.ui.LocalThemeTokens
import com.foreverjukebox.app.ui.WubMachineUiState
import com.foreverjukebox.app.wubmachine.DubstepPartKind
import kotlin.math.ceil
import kotlin.math.max

// Matches the web palette (WubMachineViz.ts).
private val IntroColor = Color(0xFF3DD9C1)
private val DropColor = Color(0xFF9B5CFF)
private val BreakColor = Color(0xFFFF4FA3)
private val EndingColor = Color(0xFF8A93A6)

private const val H_PAD = 20f
private const val PART_GAP = 2f
private const val BLOCK_ALPHA = 0.16f
private const val PLAYED_ALPHA = 1f
private const val UNPLAYED_ALPHA = 0.5f
private const val MIN_BLOCK_HEIGHT = 60f
private const val BLOCK_HEIGHT_FRACTION = 0.45f
private const val BLOCK_OFFSET_FRACTION = 0.08f
private const val BAR_SPACING = 2f
private const val BAR_WIDTH = 1.5f
private const val BAR_HEIGHT_FRACTION = 0.92f
private const val PLAYHEAD_WIDTH = 2f
private const val PLAYHEAD_OVERHANG = 6f

internal fun partColor(kind: DubstepPartKind): Color = when (kind) {
    DubstepPartKind.Intro -> IntroColor
    DubstepPartKind.Drop -> DropColor
    DubstepPartKind.Break -> BreakColor
    DubstepPartKind.Ending -> EndingColor
}

/** Seconds a tap at [x] of [width] selects, or null when the remix has no length. */
internal fun wubSecondsAtX(x: Float, width: Float, duration: Double): Double? {
    if (duration <= 0 || width <= 2 * H_PAD) return null
    val ratio = ((x - H_PAD) / (width - 2 * H_PAD)).coerceIn(0f, 1f)
    return ratio * duration
}

/** Linear timeline of the remix: one waveform block per part and a playhead. Tapping selects a time. */
@Composable
fun WubMachineVisualization(
    state: WubMachineUiState,
    onSelectPosition: (seconds: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val playheadColor = LocalThemeTokens.current.onBackground
    val duration = state.durationSeconds
    Canvas(
        modifier = modifier.pointerInput(duration) {
            detectTapGestures { tap ->
                wubSecondsAtX(tap.x, size.width.toFloat(), duration)?.let(onSelectPosition)
            }
        }
    ) {
        if (duration <= 0 || size.width <= 2 * H_PAD || size.height <= 0f) return@Canvas
        drawRemix(state, playheadColor)
    }
}

private fun DrawScope.xOf(seconds: Double, duration: Double): Float =
    H_PAD + ((seconds / duration) * (size.width - 2 * H_PAD)).toFloat()

private fun DrawScope.drawRemix(state: WubMachineUiState, playheadColor: Color) {
    val duration = state.durationSeconds
    val blockHeight = max(MIN_BLOCK_HEIGHT, size.height * BLOCK_HEIGHT_FRACTION)
    val blockTop = max(0f, (size.height - blockHeight) / 2 + size.height * BLOCK_OFFSET_FRACTION)
    val mid = blockTop + blockHeight / 2
    val playheadX = xOf(state.positionSeconds, duration)
    val peaks = state.peaks

    for (part in state.parts) {
        val left = xOf(part.start, duration)
        val right = xOf(part.start + part.duration, duration) - PART_GAP
        val color = partColor(part.kind)
        drawRect(
            color = color,
            topLeft = Offset(left, blockTop),
            size = Size(max(1f, right - left), blockHeight),
            alpha = BLOCK_ALPHA
        )
        if (peaks.isEmpty()) continue
        var x = ceil(left)
        while (x < right) {
            val bin = (((x - H_PAD) / (size.width - 2 * H_PAD)) * peaks.size).toInt()
            val peak = peaks.getOrElse(bin) { 0f }
            val bar = max(1f, peak * blockHeight * BAR_HEIGHT_FRACTION)
            drawRect(
                color = color,
                topLeft = Offset(x, mid - bar / 2),
                size = Size(BAR_WIDTH, bar),
                alpha = if (x <= playheadX) PLAYED_ALPHA else UNPLAYED_ALPHA
            )
            x += BAR_SPACING
        }
    }

    drawRect(
        color = playheadColor,
        topLeft = Offset(playheadX - PLAYHEAD_WIDTH / 2, blockTop - PLAYHEAD_OVERHANG),
        size = Size(PLAYHEAD_WIDTH, blockHeight + 2 * PLAYHEAD_OVERHANG)
    )
}
