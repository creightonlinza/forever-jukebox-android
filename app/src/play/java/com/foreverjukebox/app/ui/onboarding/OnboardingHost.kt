package com.foreverjukebox.app.ui.onboarding

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.foreverjukebox.app.ui.SurfaceShape
import com.foreverjukebox.app.ui.TabId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Lets layout settle (loading fades, tab switches, dialog entry) before a tour starts. */
private const val TOUR_START_DELAY_MS = 600L

/** A step whose target stays off screen this long is passed over. */
private const val MISSING_TARGET_GRACE_MS = 500L

@Stable
internal class OnboardingController(
    private val store: OnboardingStore,
    progressState: MutableState<OnboardingProgress?>
) {
    var progress by progressState
        private set

    /** Null until the store has loaded, so no tour starts on a guess. */
    var storedRecord by mutableStateOf<OnboardingRecord?>(null)
        internal set

    // Covers the gap between finishing or skipping a tour and the store write landing.
    private var finishedThisSession by mutableStateOf(emptySet<OnboardingTourId>())
    private var skippedThisSession by mutableStateOf(false)

    val record: OnboardingRecord?
        get() = storedRecord?.let {
            it.copy(completed = it.completed + finishedThisSession, skipped = it.skipped || skippedThisSession)
        }

    fun start(tour: OnboardingTourId, placed: Set<OnboardingTarget>) {
        val first = firstPlacedStep(tour, 0, placed) ?: return
        progress = OnboardingProgress(tour, first)
    }

    fun next(placed: Set<OnboardingTarget>) {
        val current = progress ?: return
        val nextStep = firstPlacedStep(current.tour, current.stepIndex + 1, placed)
        if (nextStep != null) {
            progress = current.copy(stepIndex = nextStep)
        } else {
            progress = null
            finishedThisSession = finishedThisSession + current.tour
            store.markCompleted(current.tour)
        }
    }

    /** Skipping opts out of onboarding for good: every tour, including ones added later, is done. */
    fun skip() {
        if (progress == null) return
        progress = null
        skippedThisSession = true
        store.markSkipped()
    }

    /** A tour whose dialog closed mid-way starts over the next time the dialog opens. */
    fun abandon(tour: OnboardingTourId) {
        if (progress?.tour == tour) progress = null
    }

    fun replay() {
        progress = null
        finishedThisSession = emptySet()
        skippedThisSession = false
        store.reset()
    }
}

// Dialog tours are not saved: the dialog that hosts one may not come back after a recreation.
private val OnboardingProgressSaver = Saver<MutableState<OnboardingProgress?>, String>(
    save = { state ->
        state.value
            ?.takeIf { it.tour.screen != OnboardingScreen.Dialog }
            ?.let { "${it.tour.storageKey}:${it.stepIndex}" }
    },
    restore = { saved ->
        val tour = OnboardingTourId.fromStorageKey(saved.substringBefore(':'))
        val step = saved.substringAfter(':').toIntOrNull()
        val progress = if (tour != null && step != null && step in OnboardingTours.steps(tour).indices) {
            OnboardingProgress(tour, step)
        } else {
            null
        }
        mutableStateOf(progress)
    }
)

internal val LocalOnboardingController = staticCompositionLocalOf<OnboardingController?> { null }

/**
 * Hosts the first-run tours: owns the onboarding record and the active step, and runs the app tab
 * tours over [content]. [suppressed] hides them while something drawn in the app window, such as
 * the fullscreen visualization, covers the tabs; dialogs hide them by taking the window focus.
 */
@Composable
fun OnboardingHost(
    activeTab: TabId,
    suppressed: Boolean,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { OnboardingStore(context) }
    // Saveable, so a rotation mid-tour stays on the same step.
    val progressState = rememberSaveable(saver = OnboardingProgressSaver) { mutableStateOf(null) }
    val controller = remember(store, progressState) { OnboardingController(store, progressState) }
    val registry = remember { OnboardingRegistry() }

    LaunchedEffect(store, controller) {
        launch { store.recordBaseline() }
        store.record.collect { controller.storedRecord = it }
    }

    CompositionLocalProvider(LocalOnboardingController provides controller) {
        OnboardingSurface(
            controller = controller,
            registry = registry,
            tours = OnboardingTourId.entries.filter { isTourScreenActive(it, activeTab, registry.playbackReady) },
            suppressed = suppressed,
            modifier = Modifier.fillMaxSize(),
            content = content
        )
    }
}

/**
 * A dialog that runs [tour] over its [content]: the dialog window draws above the app's own tour
 * layer, so the tour lives inside it. The tour starts when the dialog opens and starts over if the
 * dialog closes before it ends; while it shows, a tap outside the dialog does not close it.
 */
@Composable
fun OnboardingDialog(
    tour: OnboardingTourId,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    val controller = LocalOnboardingController.current
    val touring = controller?.progress?.tour == tour
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = !touring)
    ) {
        if (controller == null) {
            content()
        } else {
            val registry = remember { OnboardingRegistry() }
            DisposableEffect(controller, tour) {
                onDispose { controller.abandon(tour) }
            }
            OnboardingSurface(
                controller = controller,
                registry = registry,
                tours = listOf(tour),
                suppressed = false,
                // The overlay stays inside the dialog's rounded surface.
                modifier = Modifier.clip(SurfaceShape),
                content = content
            )
        }
    }
}

/**
 * Collects target bounds from [content], starts the first of [tours] not yet done, and draws its
 * active step above [content]. [tours] lists the tours whose screen is on display. Nothing shows
 * while this window lacks focus, which is how a dialog above it hides its tour.
 */
@Composable
private fun OnboardingSurface(
    controller: OnboardingController,
    registry: OnboardingRegistry,
    tours: List<OnboardingTourId>,
    suppressed: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    val hidden = suppressed || !LocalWindowInfo.current.isWindowFocused
    val currentTours by rememberUpdatedState(tours)
    val currentHidden by rememberUpdatedState(hidden)

    LaunchedEffect(controller, registry) {
        snapshotFlow {
            val record = controller.record
            // A tour left part-way on another screen does not hold back the tours on this one.
            val busy = controller.progress?.tour in currentTours
            if (record == null || busy || currentHidden) {
                null
            } else {
                resolveTourToStart(currentTours, record, registry.placedTargets)
            }
        }.collectLatest { tour ->
            if (tour != null) {
                delay(TOUR_START_DELAY_MS)
                controller.start(tour, registry.placedTargets)
            }
        }
    }

    val progress = controller.progress?.takeIf { it.tour in tours }
    val steps = progress?.let { OnboardingTours.steps(it.tour) }.orEmpty()
    val step = progress?.let { steps.getOrNull(it.stepIndex) }
    val targetWindowBounds = step?.let { registry.boundsOf(it.target) }

    // A mode switch can remove a later target (Tune and Info show in Jukebox mode only).
    LaunchedEffect(progress, targetWindowBounds == null) {
        if (progress != null && targetWindowBounds == null) {
            delay(MISSING_TARGET_GRACE_MS)
            controller.next(registry.placedTargets)
        }
    }

    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    var calloutWindowBounds by remember { mutableStateOf<Rect?>(null) }
    val shown = if (!hidden && step != null && targetWindowBounds != null) step to targetWindowBounds else null
    val currentVisible by rememberUpdatedState(shown != null)

    CompositionLocalProvider(LocalOnboardingRegistry provides registry) {
        Box(
            modifier = modifier
                .onGloballyPositioned { hostOrigin = it.positionInWindow() }
                // Observes every tap before the app sees it, consuming nothing: a tap anywhere
                // but the callout moves the tour on, and still reaches whatever it landed on.
                .pointerInput(controller, registry) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        // Only a tap on a step already showing moves the tour on; a tap that began
                        // before the step appeared (or before the window regained focus) does not.
                        val stepAtDown = controller.progress.takeIf { currentVisible }
                        // Tracks the finger by hand: the tour's touch blocker consumes every move,
                        // which the stock tap helpers would read as a cancelled tap.
                        var moved = false
                        while (true) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes
                                .firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                            }
                            if (!change.pressed) break
                        }
                        val inCallout = calloutWindowBounds?.contains(down.position + hostOrigin) == true
                        val sameStep = stepAtDown != null && stepAtDown == controller.progress
                        if (sameStep && currentVisible && !moved && !inCallout) {
                            controller.next(registry.placedTargets)
                        }
                    }
                }
        ) {
            content()
            shown?.let { (shownStep, shownBounds) ->
                val placedSteps = steps.filter { it.target in registry.placedTargets }
                val callouts = shownStep.callouts.mapNotNull { callout ->
                    registry.boundsOf(callout.target)?.let { callout to it.translate(-hostOrigin) }
                }
                OnboardingOverlay(
                    step = shownStep,
                    callouts = callouts,
                    stepNumber = placedSteps.indexOf(shownStep) + 1,
                    stepCount = placedSteps.size,
                    target = shownBounds.translate(-hostOrigin),
                    onNext = { controller.next(registry.placedTargets) },
                    onSkip = controller::skip,
                    onCalloutPlaced = { calloutWindowBounds = it },
                    modifier = Modifier.matchParentSize()
                )
            }
        }
    }
}
