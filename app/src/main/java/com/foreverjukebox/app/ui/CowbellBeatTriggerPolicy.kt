package com.foreverjukebox.app.ui

/**
 * Whether an engine update should schedule cowbell hits for the beat it reports.
 *
 * Hits are keyed on [beatsPlayed] rather than the beat index so a branch that lands on
 * the same index still counts as a new beat, while repeated ticks inside one beat do not.
 * The cast receiver renders its own cowbell, so the local overlay stays silent while casting.
 */
internal fun shouldScheduleCowbellBeat(
    playMode: PlaybackMode,
    audioMode: JukeboxAudioMode,
    isCasting: Boolean,
    currentBeatIndex: Int,
    beatsPlayed: Int,
    lastScheduledBeatsPlayed: Int
): Boolean {
    return playMode == PlaybackMode.Jukebox &&
        audioMode == JukeboxAudioMode.Cowbell &&
        !isCasting &&
        currentBeatIndex >= 0 &&
        beatsPlayed != lastScheduledBeatsPlayed
}
