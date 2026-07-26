package com.foreverjukebox.app.ui

/**
 * Usage analytics events shared with the web app's GA4 event dictionary: play,
 * search, select_track, favorite, share, upload, audio_mode, tune. Event names,
 * parameter names, and string values must stay identical to the web app so both
 * platforms aggregate under the same GA4 property. Parameter values are strings
 * with one exception — audio_intensity is numeric so GA4 aggregates it as a
 * metric — and null/blank parameters are omitted (e.g. Spotify search picks have
 * no track id yet).
 *
 * cast_start has no web counterpart; the web app has no Cast support.
 */
interface AnalyticsGateway {
    fun logPlay(mode: String, trackId: String, trackTitle: String?)
    fun logSearch(searchTerm: String)
    fun logSelectTrack(source: String, trackId: String?, trackTitle: String?)
    fun logFavorite(trackId: String, trackTitle: String?)
    fun logShare(trackId: String)
    fun logUpload(method: String)
    fun logAudioMode(audioMode: String, intensity: Int?)
    fun logTune(control: String)
    fun logCastStart(mode: String)
}

fun analyticsPlayMode(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Jukebox -> "jukebox"
    PlaybackMode.Autocanonizer -> "autocanonizer"
}

fun analyticsSelectSource(tab: TopSongsTab): String = when (tab) {
    TopSongsTab.TopSongs -> "top"
    TopSongsTab.Trending -> "trending"
    TopSongsTab.Recent -> "recent"
    TopSongsTab.Favorites -> "favorites"
}

// Search results are titled "Name — Artist" on web; list picks use the plain title.
fun analyticsSearchResultTitle(name: String?, artist: String?): String? {
    val trimmedName = name?.trim().orEmpty()
    val trimmedArtist = artist?.trim().orEmpty()
    return when {
        trimmedName.isEmpty() -> null
        trimmedArtist.isEmpty() -> trimmedName
        else -> "$trimmedName — $trimmedArtist"
    }
}

fun PlaybackState.analyticsPlayTrackId(): String? =
    shareTrackIdOrNull() ?: lastYouTubeId?.trim()?.takeIf { it.isNotBlank() }

/**
 * The `control` values for the `tune` event, one per changed control, in the order the
 * tuning dialog presents them. The three probability fields collapse into a single
 * `branch_probability` because the web app treats them as one conceptual control.
 *
 * Only the seven user-facing controls are compared — never the whole TuningState, whose
 * computedThreshold/deletedEdgeIds/anchorBranchId fields are receiver- and engine-derived.
 */
fun analyticsChangedTuneControls(previous: TuningState, next: TuningState): List<String> =
    buildList {
        if (previous.threshold != next.threshold) add("threshold")
        if (previous.minJumpDistancePercent != next.minJumpDistancePercent) {
            add("min_branch_length")
        }
        if (previous.minProb != next.minProb ||
            previous.maxProb != next.maxProb ||
            previous.ramp != next.ramp
        ) {
            add("branch_probability")
        }
        if (previous.justBackwards != next.justBackwards) add("just_backwards")
        if (previous.removeSequential != next.removeSequential) add("sequential")
        if (previous.highlightAnchorBranch != next.highlightAnchorBranch) add("anchor_highlight")
    }
