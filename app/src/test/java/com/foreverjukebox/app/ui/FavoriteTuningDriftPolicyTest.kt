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
    fun reportsNoDriftWhileCastingWhenTheReceiverReportsTheSavedThreshold() {
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = "thresh=31")),
            tuning = TuningState(threshold = 31, computedThreshold = 31),
            playback = castingPlayback(castTotalBeats = 400)
        )

        assertEquals("thresh=31", liveTuningParams(state))
        assertFalse(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun reportsDriftWhileCastingWhenTheReceiverReportsAutoAgainstASavedThreshold() {
        // The receiver reporting no threshold means it is choosing one per track, which is a
        // different tuning from one that names a number — even the same number.
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = "thresh=31")),
            tuning = TuningState(threshold = null, computedThreshold = 31),
            playback = castingPlayback(castTotalBeats = 400)
        )

        assertNull(liveTuningParams(state))
        assertTrue(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun reportsDriftWhileCastingWhenTheSavedThresholdIsNotTheReceiverAutoValue() {
        val state = stateFor(
            favorites = listOf(favorite(tuningParams = "thresh=45")),
            tuning = TuningState(threshold = 31, computedThreshold = 31),
            playback = castingPlayback(castTotalBeats = 400)
        )

        assertTrue(hasFavoriteTuningDrift(state, favoriteForCurrentTrack(state)))
    }

    @Test
    fun holdsTheThresholdComparisonUntilTheReceiverReportsForTheCurrentTrack() {
        // Casting preserves the mirrored computedThreshold across a track change, so until a status
        // arrives it can still describe the previous track. The saved threshold must not be read
        // against that stale auto value; castTotalBeats is what marks the status as arrived.
        val staleAutoThreshold = TuningState(threshold = 29, computedThreshold = 29)
        val favorites = listOf(favorite(tuningParams = "thresh=29"))
        val beforeStatus = stateFor(
            favorites = favorites,
            tuning = staleAutoThreshold,
            playback = castingPlayback(castTotalBeats = null)
        )

        assertFalse(hasFavoriteTuningDrift(beforeStatus, favoriteForCurrentTrack(beforeStatus)))

        // The status lands and carries totalBeats and computedThreshold together, so the comparison
        // resumes against the current track's auto value.
        val afterStatus = stateFor(
            favorites = favorites,
            tuning = TuningState(threshold = 31, computedThreshold = 31),
            playback = castingPlayback(castTotalBeats = 400)
        )

        assertTrue(hasFavoriteTuningDrift(afterStatus, favoriteForCurrentTrack(afterStatus)))
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

    private fun castingPlayback(castTotalBeats: Int?): PlaybackState = loadedPlayback().copy(
        isCasting = true,
        castTotalBeats = castTotalBeats
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
