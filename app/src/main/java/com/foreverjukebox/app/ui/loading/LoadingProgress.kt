package com.foreverjukebox.app.ui.loading

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foreverjukebox.app.ui.LocalThemeTokens
import com.foreverjukebox.app.ui.neonFontFamily
import kotlinx.coroutines.delay

// `presentation` changes each time a shown task plays out, so the same task shown again starts fresh.
data class PresentedLoading(val shown: LiveLoading?, val onFinished: () -> Unit, val presentation: Int)

// Plays out one task at a time (see presentNext); the shown load keeps its last state once replaced.
@Composable
fun rememberPresentedLoading(live: LiveLoading?): PresentedLoading {
    var shown by remember { mutableStateOf<ShownLoading?>(null) }
    val lastShown = remember { arrayOfNulls<LiveLoading>(1) }
    val latest by rememberUpdatedState(live)
    val next = presentNext(shown, live)
    SideEffect { shown = next }
    val showingLive = live != null && next != null && next.task == live.task && next.key == live.key
    if (showingLive) {
        lastShown[0] = live
    }
    var presentation by remember { mutableIntStateOf(0) }
    val onFinished = remember {
        {
            presentation += 1
            shown = afterPresentation(latest)
        }
    }
    val snapshot = lastShown[0]
    val result = when {
        next == null || snapshot == null -> null
        showingLive -> snapshot
        else -> snapshot.copy(finished = true)
    }
    return PresentedLoading(result, onFinished, presentation)
}

private class StepTimes {
    val start = mutableMapOf<LoadingStepId, Long>()
    val end = mutableMapOf<LoadingStepId, Long>()
    var previous: LoadingStepId? = null

    // Real start and end of each step, for the elapsed timer and the step durations.
    fun record(activeId: LoadingStepId, finished: Boolean, nowMs: Long) {
        val current = if (finished) null else activeId
        if (previous == current) return
        previous?.let { end.putIfAbsent(it, nowMs) }
        current?.let { start.putIfAbsent(it, nowMs) }
        previous = current
    }

    fun duration(id: LoadingStepId): String {
        val s = start[id] ?: return ""
        val e = end[id] ?: return ""
        return "%.1fs".format((e - s) / 1000.0)
    }
}

private val slideEasing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

@Composable
fun LoadingProgress(
    loading: LiveLoading,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    // Shown above the step; the task title ("Loading track") when there is none.
    heading: String? = null
) {
    val steps = loading.task.steps
    val progress = loading.progress?.coerceIn(0f, 1f)
    val realIndex = steps.indexOf(loading.activeId).coerceAtLeast(0)
    val ring = loading.task.ring
    // A cached track and a load still waiting for its first reply skip the pacing.
    val paced = loading.task != LoadingTask.Track && loading.task != LoadingTask.Pending
    val real = RealStep(realIndex, progress, loading.finished, emptySet())
    val realState by rememberUpdatedState(real)
    val finishedCallback by rememberUpdatedState(onFinished)
    val seen = remember { mutableSetOf<Int>() }
    seen += realIndex
    var view by remember { mutableStateOf(PacedView(0, 0f, SystemClock.uptimeMillis(), null)) }
    val settled = !paced || view.doneAtMs != null
    LaunchedEffect(settled) {
        if (settled) return@LaunchedEffect
        var last = SystemClock.uptimeMillis()
        while (true) {
            delay(LoadingTimings.TICK_MS)
            val now = SystemClock.uptimeMillis()
            val finalDwell = if (ring) LoadingTimings.FINAL_STEP_MS else 0L
            view = advanceView(view, realState.copy(seen = seen), steps.size, now, now - last, finalDwell)
            last = now
        }
    }
    LaunchedEffect(view.doneAtMs) {
        if (view.doneAtMs == null) return@LaunchedEffect
        delay(if (ring) LoadingTimings.DONE_HOLD_MS else 0L)
        finishedCallback()
    }
    // An already analyzed track skips the pacing and ends as soon as its work does.
    LaunchedEffect(paced, loading.finished) {
        if (!paced && loading.finished) finishedCallback()
    }
    val times = remember { StepTimes() }
    times.record(loading.activeId, loading.finished, SystemClock.uptimeMillis())

    val done = view.doneAtMs != null
    val viewIndex = view.index.coerceIn(0, steps.lastIndex)
    // Without pacing the view never advances, so its bar follows the real progress directly.
    val fill = if (paced) view.fill else progress ?: 0f
    val sweeping = viewIndex == realIndex && progress == null && !loading.finished
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(sweeping) {
        while (sweeping) {
            delay(1000L)
            tick += 1
        }
    }
    // Keyed on the tick, so the timer refreshes once a second.
    val nowMs = remember(tick, sweeping) { SystemClock.uptimeMillis() }
    val activeStart = times.start[steps[viewIndex]]
    val elapsed = if (sweeping && activeStart != null) "${(nowMs - activeStart) / 1000}s" else ""
    val percent = overallPercent(steps, view.copy(fill = fill))
    val activeLabel = steps[viewIndex].label
    val slidePx = with(LocalDensity.current) { 56.dp.roundToPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = loading.task.title
                progressBarRangeInfo = ProgressBarRangeInfo(percent / 100f, 0f..1f)
                stateDescription = activeLabel
            },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (ring) {
            val lead = if (done) RING_DOTS else (percent / 100f * RING_DOTS).toInt()
            val count = "${viewIndex + 1} / ${steps.size}" + if (elapsed.isNotEmpty()) " · $elapsed" else ""
            BeatRing(
                percent = percent,
                lead = lead,
                arcsEndPercent = lastStepStartPercent(steps),
                count = count,
                label = activeLabel,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        TitleRow(heading ?: loading.task.title, if (!ring && steps.size > 1) "${viewIndex + 1} / ${steps.size}" else null)
        AnimatedContent(
            targetState = viewIndex,
            transitionSpec = {
                // The finished row shows its check first, then hands off to the next one.
                val delay = LoadingTimings.CHECK_MS.toInt()
                val spec = tween<androidx.compose.ui.unit.IntOffset>(LoadingTimings.SLIDE_MS, delay, slideEasing)
                val fade = tween<Float>(LoadingTimings.SLIDE_MS, delay, slideEasing)
                (slideInHorizontally(spec) { slidePx } + fadeIn(fade))
                    .togetherWith(slideOutHorizontally(spec) { -slidePx } + fadeOut(fade))
            },
            label = "loading step"
        ) { index ->
            val step = steps[index]
            val rowDone = index < viewIndex || done
            StepRow(
                label = step.label,
                done = rowDone,
                meta = when {
                    rowDone -> times.duration(step)
                    sweeping -> elapsed
                    else -> "${(fill * 100).toInt()}%"
                },
                message = loading.message?.takeIf { index == realIndex && !rowDone },
                fill = if (rowDone) 1f else fill,
                sweeping = sweeping && !rowDone
            )
        }
    }
}

@Composable
private fun TitleRow(title: String, count: String?) {
    val tokens = LocalThemeTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = tokens.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = count, style = MaterialTheme.typography.labelMedium, color = tokens.muted)
        }
    }
}

@Composable
private fun StepRow(
    label: String,
    done: Boolean,
    meta: String,
    message: String?,
    fill: Float,
    sweeping: Boolean
) {
    val tokens = LocalThemeTokens.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepIcon(done)
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (done) FontWeight.Normal else FontWeight.SemiBold),
                color = tokens.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(text = meta, style = MaterialTheme.typography.labelSmall, color = tokens.muted)
        }
        Column(Modifier.padding(start = 34.dp, top = 4.dp)) {
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.muted,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            val barModifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
            if (sweeping) {
                LinearProgressIndicator(
                    modifier = barModifier,
                    color = tokens.accent,
                    trackColor = tokens.controlBorder,
                    strokeCap = StrokeCap.Round
                )
            } else {
                val shownFill by animateFloatAsState(fill, tween(100, easing = LinearEasing), label = "step fill")
                LinearProgressIndicator(
                    progress = { shownFill },
                    modifier = barModifier,
                    color = tokens.accent,
                    trackColor = tokens.controlBorder,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
        }
    }
}

@Composable
private fun StepIcon(done: Boolean) {
    val tokens = LocalThemeTokens.current
    if (!done) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = tokens.accent,
                trackColor = tokens.controlBorder,
                strokeWidth = 2.dp
            )
        }
        return
    }
    val pop = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        pop.animateTo(1.15f, tween(210))
        pop.animateTo(1f, tween(140))
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .scale(pop.value)
            .background(tokens.accent, CircleShape)
            .border(2.dp, tokens.accent, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = tokens.panelSurface, modifier = Modifier.size(14.dp))
    }
}

// Beat dots light up around the circle; decorative arcs draw before the last step starts.
@Composable
private fun BeatRing(
    percent: Int,
    lead: Int,
    arcsEndPercent: Float,
    count: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val tokens = LocalThemeTokens.current
    val arcs = RING_BRANCHES.map { branch ->
        val target = if (percent >= branch.showAt * arcsEndPercent) 1f else 0f
        animateFloatAsState(target, tween(LoadingTimings.ARC_DRAW_MS), label = "ring arc").value
    }
    val sizeDp = 200.dp
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }
    Box(modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(sizeDp)) {
            // The web draws in a 208-unit square centred on the ring.
            val scale = sizePx / 208f
            val center = Offset(size.width / 2, size.height / 2)
            fun point(p: RingPoint) = Offset(center.x + p.x * scale, center.y + p.y * scale)
            val measure = PathMeasure()
            RING_BRANCHES.forEachIndexed { i, branch ->
                if (arcs[i] <= 0f) return@forEachIndexed
                val path = Path().apply {
                    val start = point(branch.start)
                    val control = point(branch.control)
                    val end = point(branch.end)
                    moveTo(start.x, start.y)
                    quadraticTo(control.x, control.y, end.x, end.y)
                }
                measure.setPath(path, false)
                val drawn = Path()
                measure.getSegment(0f, measure.length * arcs[i], drawn, true)
                drawPath(
                    drawn,
                    color = if (branch.selected) tokens.titleAccent else tokens.edgeStroke,
                    style = Stroke(width = (if (branch.selected) 2.2f else 1.5f) * scale)
                )
            }
            for (i in 0 until RING_DOTS) {
                val on = i <= lead
                drawCircle(
                    color = if (on) tokens.beatFill else tokens.controlBorder,
                    radius = 3.2f * scale * if (i == lead) 1.6f else 1f,
                    center = point(ringPoint(i))
                )
            }
            drawCircle(color = tokens.panelSurface.copy(alpha = 0.88f), radius = 50f * scale, center = center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = count, style = MaterialTheme.typography.labelSmall, color = tokens.accent)
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = neonFontFamily),
                color = tokens.onBackground
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // As on the web, the label may run past the hub but stays inside the dots.
                modifier = Modifier.widthIn(max = 160.dp)
            )
        }
    }
}
