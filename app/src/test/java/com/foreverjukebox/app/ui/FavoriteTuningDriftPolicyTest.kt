package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.FavoritePlayMode
import com.foreverjukebox.app.data.FavoriteTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteTuningDriftPolicyTest {

    @Test
    fun findsNoFavoriteWithoutATrackId() {
        assertNull(favoriteForCurrentTrack(UiState()))
    }

    @Test
    fun findsNoFavoriteForAnUnrelatedTrack() {
        val state = stateFor(favorites = listOf(favorite(id = OTHER_JOB_ID)))

        assertNull(favoriteForCurrentTrack(state))
        assertFalse(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun findsAFavoriteStoredUnderTheYouTubeId() {
        val state = stateFor(
            favorites = listOf(favorite(id = YOUTUBE_ID)),
            playback = loadedPlayback().copy(lastYouTubeId = YOUTUBE_ID)
        )

        assertEquals(YOUTUBE_ID, favoriteForCurrentTrack(state)?.uniqueSongId)
    }

    @Test
    fun reportsNoDriftWhenTuningMatchesTheSavedTuningInAnyKeyOrder() {
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = "am=lofi&jb=1")),
            tuning = TuningState(justBackwards = true),
            playback = loadedPlayback().copy(jukeboxAudioMode = JukeboxAudioMode.Lofi)
        )

        assertFalse(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun reportsDriftWhenTuningDiffersFromTheSavedTuning() {
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = "jb=1&am=lofi")),
            tuning = TuningState(justBackwards = true)
        )

        assertTrue(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun reportsDriftWhenAnAutocanonizerRoundTripClearsTheAudioMode() {
        // Switching to autocanonizer silences the audio mode, and switching back leaves it off.
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = "thresh=45&am=nightcore")),
            tuning = TuningState(threshold = 45, computedThreshold = 29),
            playback = loadedPlayback().copy(jukeboxAudioMode = JukeboxAudioMode.Off)
        )

        assertTrue(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun reportsDriftWhenThePlayModeDiffersFromTheSavedPlayMode() {
        val state = stateFor(
            favorites = listOf(favorite(playMode = null)),
            playback = loadedPlayback().copy(playMode = PlaybackMode.Autocanonizer)
        )

        assertTrue(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun reportsNoDriftForALegacyFavoriteWithNothingSaved() {
        val state = stateFor(favorites = listOf(favorite(tuningParams = null, playMode = null)))

        assertFalse(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun reportsNoDriftForAnAutocanonizerFavoritePlayedInAutocanonizer() {
        // Autocanonizer carries no tuning, so live branch tuning is not part of the comparison.
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = null, playMode = FavoritePlayMode.Autocanonizer)),
            tuning = TuningState(justBackwards = true),
            playback = loadedPlayback().copy(playMode = PlaybackMode.Autocanonizer)
        )

        assertFalse(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun buildsLiveTuningFromTheReceiverWhileCasting() {
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = "jb=1&am=daycore")),
            tuning = TuningState(justBackwards = true),
            playback = loadedPlayback().copy(
                isCasting = true,
                castTotalBeats = 400,
                castAudioModeWireValue = "daycore",
                jukeboxAudioMode = JukeboxAudioMode.Off
            )
        )

        assertEquals("jb=1&am=daycore", liveTuningParams(state))
        assertFalse(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun reportsNoDriftWhileCastingBeforeTheReceiverReports() {
        // Cast connect resets the mirrored tuning, which would read as drift for one round trip.
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = "jb=1")),
            playback = loadedPlayback().copy(isCasting = true, castTotalBeats = null)
        )

        assertFalse(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun emitsThresholdOnceTheGraphReportsItsComputedValue() {
        val state = stateFor(tuning = TuningState(threshold = 45, computedThreshold = 29))

        assertEquals("thresh=45", liveTuningParams(state))
    }

    @Test
    fun namesTheDriftInTheFavoriteActionDescription() {
        assertEquals(
            "Add favorite",
            favoriteActionContentDescription(isFavorite = false, hasTuningDrift = false)
        )
        assertEquals(
            "Remove favorite",
            favoriteActionContentDescription(isFavorite = true, hasTuningDrift = false)
        )
        assertEquals(
            "Remove favorite, tuning differs from the saved tuning",
            favoriteActionContentDescription(isFavorite = true, hasTuningDrift = true)
        )
    }

    private fun stateFor(
        favorites: List<FavoriteTrack> = emptyList(),
        tuning: TuningState = TuningState(),
        playback: PlaybackState = loadedPlayback()
    ): UiState = UiState(favorites = favorites, tuning = tuning, playback = playback)

    private fun loadedPlayback(): PlaybackState = PlaybackState(
        lastJobId = JOB_ID,
        audioLoaded = true,
        analysisLoaded = true
    )

    private fun favorite(
        id: String = JOB_ID,
        tuningParams: String? = null,
        playMode: FavoritePlayMode? = null
    ): FavoriteTrack = FavoriteTrack(
        uniqueSongId = id,
        title = "Title",
        artist = "Artist",
        tuningParams = tuningParams,
        playMode = playMode
    )

    private companion object {
        const val JOB_ID = "a3f3c0dc73c6476c9db95c227f9206f2"
        const val OTHER_JOB_ID = "b4e4d1ed84d7587dae0a6d338a0317f3"
        const val YOUTUBE_ID = "dQw4w9WgXcQ"
    }
}
