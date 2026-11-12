package com.foreverjukebox.app.ui.onboarding

import com.foreverjukebox.app.ui.TabId

/** A control the onboarding tour can point at. */
enum class OnboardingTarget {
    CompletedAnalysis,
    AddAudio,
    PlaybackMode,
    VisualizationPicker,
    ToolRow,
    Tuning,
    TrackInfo,
    Export,
    TuningThreshold,
    TuningMinJumpDistance,
    TuningBranchProbability,
    TuningAudioModeTab
}

/**
 * The onboarding release this build ships. Raise it when a release adds a tour, and give that tour
 * the new number as its [OnboardingTourId.sinceVersion].
 */
const val ONBOARDING_VERSION = 1

/** Where a tour runs: an app tab, or a dialog that hosts its own tour. */
enum class OnboardingScreen { Home, Jukebox, Dialog }

/**
 * A tour runs on one [screen] and is shown at most once. [storageKey] identifies the tour on disk
 * and never changes, so a tour that gains, loses or rewords steps is not shown again to anyone who
 * has finished it. [sinceVersion] is the [ONBOARDING_VERSION] that introduced the tour.
 */
enum class OnboardingTourId(val storageKey: String, val sinceVersion: Int, val screen: OnboardingScreen) {
    Entry("entry", 1, OnboardingScreen.Home),
    Playback("playback", 1, OnboardingScreen.Jukebox),
    Tuning("tuning", 1, OnboardingScreen.Dialog);

    companion object {
        fun fromStorageKey(key: String?): OnboardingTourId? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * What the user has done with onboarding. [baselineVersion] is the [ONBOARDING_VERSION] of the first
 * run that recorded onboarding, so a tour added in a later release is never pushed on someone who
 * already used the app. Skipping is final and covers tours added later too.
 */
data class OnboardingRecord(
    val completed: Set<OnboardingTourId>,
    val skipped: Boolean,
    val baselineVersion: Int
) {
    fun isDone(tour: OnboardingTourId): Boolean =
        skipped || tour in completed || tour.sinceVersion > baselineVersion
}

/** A control inside a step's target that the step names and explains. */
data class OnboardingCallout(
    val target: OnboardingTarget,
    val label: String,
    val description: String
)

/**
 * One stop on a tour. [touchThrough] leaves the highlighted control usable during the step; a
 * control where a stray tap changes a setting, such as a slider, turns it off.
 */
data class OnboardingStep(
    val target: OnboardingTarget,
    val title: String,
    val body: String,
    val callouts: List<OnboardingCallout> = emptyList(),
    val touchThrough: Boolean = true
)

data class OnboardingProgress(val tour: OnboardingTourId, val stepIndex: Int)

object OnboardingTours {
    private val entry = listOf(
        OnboardingStep(
            target = OnboardingTarget.CompletedAnalysis,
            title = "Your analyzed tracks",
            body = "Every track you analyze is saved here. " +
                "Tap one to play it again, or long-press it to add it to your playlist."
        ),
        OnboardingStep(
            target = OnboardingTarget.AddAudio,
            title = "Load a track",
            body = "Tap Add Audio and pick a song on this device. " +
                "The app finds its beats and builds an endless, branching version of it."
        )
    )

    private val playback = listOf(
        OnboardingStep(
            target = OnboardingTarget.PlaybackMode,
            title = "Switch playback modes",
            body = "Jukebox jumps between similar beats forever. " +
                "Open this menu for Autocanonizer or Wub Machine."
        ),
        OnboardingStep(
            target = OnboardingTarget.VisualizationPicker,
            title = "Change the visualization",
            body = "Pick how the beats and branches are drawn. Tap any beat to jump playback to it."
        ),
        OnboardingStep(
            target = OnboardingTarget.ToolRow,
            title = "Track tools",
            body = "These tools work on the track that is loaded.",
            callouts = listOf(
                OnboardingCallout(
                    target = OnboardingTarget.Tuning,
                    label = "Tune",
                    description = "how often the track jumps, how far, and which branches it takes"
                ),
                OnboardingCallout(
                    target = OnboardingTarget.TrackInfo,
                    label = "Info",
                    description = "track length, and the beats and branches the analysis found"
                ),
                OnboardingCallout(
                    target = OnboardingTarget.Export,
                    label = "Export",
                    description = "save the endless version as an audio file"
                )
            )
        )
    )

    private val tuning = listOf(
        OnboardingStep(
            target = OnboardingTarget.TuningThreshold,
            title = "Branch similarity",
            body = "How alike two beats must sound to branch between them. " +
                "Higher finds more branches; lower keeps only the closest matches.",
            touchThrough = false
        ),
        OnboardingStep(
            target = OnboardingTarget.TuningMinJumpDistance,
            title = "Minimum jump distance",
            body = "Leaves out branches shorter than this share of the track, so every jump travels further.",
            touchThrough = false
        ),
        OnboardingStep(
            target = OnboardingTarget.TuningBranchProbability,
            title = "Branch probability",
            body = "The chance of a jump at each branch point. It starts at the low end after a jump " +
                "and climbs to the high end at the Branch Ramp Speed.",
            touchThrough = false
        ),
        OnboardingStep(
            target = OnboardingTarget.TuningAudioModeTab,
            title = "Audio modes",
            body = "Effects such as Nightcore, Vaporwave and 8D Audio, applied as the track plays."
        )
    )

    fun steps(tour: OnboardingTourId): List<OnboardingStep> = when (tour) {
        OnboardingTourId.Entry -> entry
        OnboardingTourId.Playback -> playback
        OnboardingTourId.Tuning -> tuning
    }
}

/** True when the app tab a tour belongs to is on screen and interactive. Dialog tours are false. */
fun isTourScreenActive(tour: OnboardingTourId, activeTab: TabId, playbackReady: Boolean): Boolean =
    when (tour.screen) {
        OnboardingScreen.Home -> activeTab == TabId.Input
        OnboardingScreen.Jukebox -> activeTab == TabId.Play && playbackReady
        OnboardingScreen.Dialog -> false
    }

/** The first of [candidates] to start automatically, or null when none applies. */
fun resolveTourToStart(
    candidates: Collection<OnboardingTourId>,
    record: OnboardingRecord,
    placed: Set<OnboardingTarget>
): OnboardingTourId? = candidates.firstOrNull { tour ->
    !record.isDone(tour) && firstPlacedStep(tour, 0, placed) != null
}

/** The first step at or after [from] whose target is on screen. */
fun firstPlacedStep(tour: OnboardingTourId, from: Int, placed: Set<OnboardingTarget>): Int? {
    val steps = OnboardingTours.steps(tour)
    return (from until steps.size).firstOrNull { steps[it].target in placed }
}
