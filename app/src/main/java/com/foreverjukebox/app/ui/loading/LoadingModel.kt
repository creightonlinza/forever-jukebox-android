package com.foreverjukebox.app.ui.loading

import com.foreverjukebox.app.engine.RandomMode
import com.foreverjukebox.app.local.LocalAnalysisStage
import com.foreverjukebox.app.engine.createRng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Port of forever-jukebox packages/shared/src/ui/LoadingProgress.tsx and jobProgress.ts: keep the
// constants, step lists and rules in step with the web.

enum class LoadingStepId(val label: String, val weight: Int) {
    Pending("Loading\u2026", 1),
    Fetch("Fetching audio", 10),
    Queued("Queued", 0),
    Beats("Detecting beats", 55),
    Features("Extracting features", 25),
    Track("Spinning up the track", 10),
    Swing("Adding swing", 1),
    Samples("Loading samples", 1),
    Remix("Building remix", 1)
}

// Beats include decoding the audio. Track builds the analysis output and cues up the track; it ends
// every analysis and is the whole task for an already analyzed track. Only an analysis shows the ring;
// mode renders queue behind the load of the same track.
enum class LoadingTask(
    val title: String,
    val steps: List<LoadingStepId>,
    val ring: Boolean = false,
    val mode: Boolean = false
) {
    ServerAnalysis(
        "Analyzing track",
        listOf(LoadingStepId.Fetch, LoadingStepId.Queued, LoadingStepId.Beats, LoadingStepId.Features, LoadingStepId.Track),
        ring = true
    ),
    LocalAnalysis(
        "Analyzing track",
        listOf(LoadingStepId.Beats, LoadingStepId.Features, LoadingStepId.Track),
        ring = true
    ),
    Track("Loading track", listOf(LoadingStepId.Track)),

    // A load waiting for its first reply, before the app knows which steps it has.
    Pending("Loading track", listOf(LoadingStepId.Pending)),
    Swing("Preparing Swing mode", listOf(LoadingStepId.Swing), mode = true),
    WubMachine("Building the Wub Machine remix", listOf(LoadingStepId.Remix), mode = true),

    // The samples step shows only when the render really loads them.
    WubMachineWithSamples(
        "Building the Wub Machine remix",
        listOf(LoadingStepId.Samples, LoadingStepId.Remix),
        mode = true
    )
}

// The active step of a track load as the coordinators record it; null when no load is in progress.
data class LoadingStep(
    val task: LoadingTask,
    val activeId: LoadingStepId,
    // 0..1 within the step; null when the step reports no progress.
    val progress: Float? = null,
    val message: String? = null
)

// What the screen reports about one load. `key` identifies the track; `finished` means the real work is done.
data class LiveLoading(
    val task: LoadingTask,
    val key: String,
    val activeId: LoadingStepId,
    val progress: Float?,
    val message: String?,
    val finished: Boolean
)

object LoadingTimings {
    const val TICK_MS = 50L

    // Time to fill a step's bar once its work has ended.
    const val FILL_MS = 300f

    // Shortest time on screen for a step that really ran, and for one that ended between two updates.
    const val MIN_STEP_MS = 500L
    const val SKIPPED_STEP_MS = 450L

    // The last step of an analysis shows the finished ring at least this long; a short hold then shows its check.
    const val FINAL_STEP_MS = 800L
    const val DONE_HOLD_MS = 300L

    // A finished step shows its check, then slides out while the next one slides in.
    const val CHECK_MS = 120L
    const val SLIDE_MS = 300
    const val ARC_DRAW_MS = 450
}

data class PacedView(val index: Int, val fill: Float, val sinceMs: Long, val doneAtMs: Long?)

data class RealStep(val index: Int, val progress: Float?, val finished: Boolean, val seen: Set<Int>)

// The on-screen step trails the real one so that every step stays visible long enough to read.
fun advanceView(view: PacedView, real: RealStep, count: Int, nowMs: Long, dtMs: Long, finalDwellMs: Long): PacedView {
    if (view.doneAtMs != null) {
        return view
    }
    if (view.index >= real.index && !real.finished) {
        val target = real.progress ?: return view
        if (target <= view.fill) {
            return view
        }
        return view.copy(fill = view.fill + (target - view.fill) * min(1f, dtMs / 250f))
    }
    val fill = min(1f, view.fill + dtMs / LoadingTimings.FILL_MS)
    val baseDwell = if (view.index in real.seen) LoadingTimings.MIN_STEP_MS else LoadingTimings.SKIPPED_STEP_MS
    val dwell = max(baseDwell, if (view.index == count - 1) finalDwellMs else 0L)
    if (fill < 1f || nowMs - view.sinceMs < dwell) {
        return view.copy(fill = fill)
    }
    if (view.index >= count - 1) {
        return view.copy(fill = 1f, doneAtMs = nowMs)
    }
    return PacedView(index = view.index + 1, fill = 0f, sinceMs = nowMs, doneAtMs = null)
}

data class ShownLoading(val task: LoadingTask, val key: String)

// Plays out one task at a time: a finished load stays on screen until its steps have played out, a mode
// render for the same track waits behind a track load, and any other new task or track replaces it.
// Null live hides.
fun presentNext(shown: ShownLoading?, live: LiveLoading?): ShownLoading? {
    if (live == null) {
        return null
    }
    val waits = live.task.mode && shown?.task?.mode != true
    val replaces = shown == null || shown.key != live.key || (shown.task != live.task && !waits)
    if (!replaces) {
        return shown
    }
    return if (live.finished) null else ShownLoading(live.task, live.key)
}

// What to show once the shown task has played out.
fun afterPresentation(live: LiveLoading?): ShownLoading? {
    return live?.takeIf { !it.finished }?.let { ShownLoading(it.task, it.key) }
}

// Overall percent from the on-screen step and fill; it never moves backward.
fun overallPercent(steps: List<LoadingStepId>, view: PacedView): Int {
    val total = steps.sumOf { it.weight }.takeIf { it > 0 } ?: 1
    val weighted = steps.withIndex().sumOf { (i, step) ->
        val fraction = when {
            i < view.index -> 1.0
            i == view.index -> view.fill.toDouble()
            else -> 0.0
        }
        step.weight * fraction
    }
    return floor(weighted / total * 100).toInt()
}

// Percent at which the last step starts; the ring's arcs finish drawing before it.
fun lastStepStartPercent(steps: List<LoadingStepId>): Float {
    val total = steps.sumOf { it.weight }.takeIf { it > 0 } ?: 1
    return (total - steps.last().weight).toFloat() / total * 100f
}

data class JobStep(val activeId: LoadingStepId, val progress: Float?, val queuedAhead: Int?)

private val queuedAheadPattern = Regex("""(\d{1,9})\s+ahead""", RegexOption.IGNORE_CASE)

// Maps a server job's status and progress to a server-analysis step. Progress comes from the API download
// (0-25), then the engine: decode 0 and beats 10-85 (one step), features 85-95, building the output from 95.
fun jobStep(status: String, progress: Double?, serverMessage: String?): JobStep {
    val p = progress ?: 0.0
    if (status == "queued") {
        val ahead = serverMessage?.let { queuedAheadPattern.find(it) }?.groupValues?.get(1)?.toInt()
        return JobStep(LoadingStepId.Queued, null, ahead)
    }
    if (status != "processing") {
        return JobStep(LoadingStepId.Fetch, if (p > 0) min(1.0, p / 25).toFloat() else null, null)
    }
    if (p < 85) {
        return JobStep(LoadingStepId.Beats, if (p < 10) null else ((p - 10) / 75).toFloat(), null)
    }
    return if (p < 95) {
        JobStep(LoadingStepId.Features, ((p - 85) / 10).toFloat(), null)
    } else {
        JobStep(LoadingStepId.Track, null, null)
    }
}

// Maps an on-device analysis stage to its step; a stored analysis is the single cached-track step.
fun localLoadingStep(stage: LocalAnalysisStage, progress: Float?): LoadingStep {
    return when (stage) {
        LocalAnalysisStage.Decode -> LoadingStep(LoadingTask.LocalAnalysis, LoadingStepId.Beats)
        LocalAnalysisStage.Beats -> LoadingStep(LoadingTask.LocalAnalysis, LoadingStepId.Beats, progress)
        LocalAnalysisStage.Features -> LoadingStep(LoadingTask.LocalAnalysis, LoadingStepId.Features, progress)
        LocalAnalysisStage.WrapUp -> LoadingStep(LoadingTask.LocalAnalysis, LoadingStepId.Track)
        LocalAnalysisStage.Cached -> LoadingStep(LoadingTask.Track, LoadingStepId.Track)
    }
}

fun queuedMessage(ahead: Int?): String {
    return if (ahead == null) "Queued • Next in line" else "Queued • $ahead ahead of you"
}

fun serverJobLoadingStep(status: String, progress: Double?, serverMessage: String?): LoadingStep {
    val step = jobStep(status, progress, serverMessage)
    val message = if (step.activeId == LoadingStepId.Queued) queuedMessage(step.queuedAhead) else null
    return LoadingStep(LoadingTask.ServerAnalysis, step.activeId, step.progress, message)
}

const val RING_DOTS = 72
const val RING_RADIUS = 92f

data class RingPoint(val x: Float, val y: Float)

fun ringPoint(index: Int): RingPoint {
    val angle = index.toDouble() / RING_DOTS * 2 * PI - PI / 2
    return RingPoint((cos(angle) * RING_RADIUS).toFloat(), (sin(angle) * RING_RADIUS).toFloat())
}

data class RingBranch(
    val start: RingPoint,
    val control: RingPoint,
    val end: RingPoint,
    // Fraction of the way to the last step at which this arc starts to draw.
    val showAt: Float,
    val selected: Boolean
)

// Decoration only: the web's fixed seed gives the same arcs on every platform.
val RING_BRANCHES: List<RingBranch> = run {
    val rng = createRng(RandomMode.Seeded, 11)
    List(12) { k ->
        val a = floor(rng() * RING_DOTS).toInt()
        val b = (a + 10 + floor(rng() * (RING_DOTS - 20)).toInt()) % RING_DOTS
        val start = ringPoint(a)
        val end = ringPoint(b)
        RingBranch(
            start = start,
            control = RingPoint((start.x + end.x) * 0.18f, (start.y + end.y) * 0.18f),
            end = end,
            showAt = 0.6f + k * 0.03f,
            selected = k == 7
        )
    }
}
