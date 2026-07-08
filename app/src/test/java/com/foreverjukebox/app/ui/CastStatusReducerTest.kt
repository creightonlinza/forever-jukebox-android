package com.foreverjukebox.app.ui

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastStatusReducerTest {

    @Test
    fun parseCastStatusMessageRejectsInvalidPayloads() {
        assertNull(parseCastStatusMessage("not-json"))
        assertNull(parseCastStatusMessage("""{"type":"ping"}"""))
    }

    @Test
    fun parseCastStatusMessageParsesKnownFields() {
        val createdAt = "2026-04-17T00:57:46.945271+00:00"
        val parsed = parseCastStatusMessage(
            """
            {
              "type":"status",
              "jobId":"0123456789abcdef0123456789abcdef",
              "createdAt":"$createdAt",
              "title":"Track",
              "artist":"Artist",
              "trackDurationSeconds":212.4,
              "totalBeats":512,
              "totalBranches":73,
              "isPlaying":true,
              "isLoading":false,
              "playbackState":"playing",
              "error":"",
              "errorCode":"cast_track_too_long",
              "activeVizIndex":4,
              "supportedAudioModes":[
                {"wireValue":" off ","label":" Off "},
                {"wireValue":"daycore","label":"Daycore"},
                {"wireValue":"daycore","label":"Duplicate Daycore"},
                {"wireValue":"","label":"Ignored"},
                {"wireValue":"future_mode","label":"Future Mode"},
                {"wireValue":"blank_label","label":" "}
              ],
              "tuning":{
                "justBackwards":true,
                "justLongBranches":true,
                "minLongBranchPercent":30,
                "removeSequentialBranches":true,
                "threshold":28,
                "computedThreshold":31,
                "branchProbability":{
                  "minPercent":12,
                  "maxPercent":45,
                  "deltaPercent":20
                },
                "deletedEdgeIds":[2,5],
                "highlightAnchorBranch":true,
                "audioMode":"daycore"
              }
            }
            """.trimIndent()
        )
        assertNotNull(parsed)
        assertEquals("0123456789abcdef0123456789abcdef", parsed?.jobId)
        assertEquals(createdAt, parsed?.createdAt)
        assertEquals("Track", parsed?.title)
        assertEquals("Artist", parsed?.artist)
        assertEquals(212.4, parsed?.trackDurationSeconds ?: 0.0, 0.0001)
        assertEquals(512, parsed?.totalBeats)
        assertEquals(73, parsed?.totalBranches)
        assertTrue(parsed?.isPlaying == true)
        assertFalse(parsed?.isLoading == true)
        assertEquals("playing", parsed?.playbackState)
        assertEquals("", parsed?.error)
        assertEquals("cast_track_too_long", parsed?.errorCode)
        assertEquals(4, parsed?.activeVizIndex)
        assertEquals(
            listOf(
                AudioModeOption("off", "Off"),
                AudioModeOption("daycore", "Daycore"),
                AudioModeOption("future_mode", "Future Mode")
            ),
            parsed?.supportedAudioModes
        )
        assertEquals("daycore", parsed?.tuning?.audioModeWireValue)
        assertEquals(28, parsed?.tuning?.threshold)
        assertEquals(31, parsed?.tuning?.computedThreshold)
        assertEquals(12, parsed?.tuning?.branchProbability?.minPercent)
        assertEquals(45, parsed?.tuning?.branchProbability?.maxPercent)
        assertEquals(20, parsed?.tuning?.branchProbability?.deltaPercent)
        assertEquals(listOf(2, 5), parsed?.tuning?.deletedEdgeIds)
        assertTrue(parsed?.tuning?.justBackwards == true)
        assertEquals(30, parsed?.tuning?.minLongBranchPercent)
        assertTrue(parsed?.tuning?.highlightAnchorBranch == true)
    }

    @Test
    fun parseCastStatusMessageFallsBackForOlderReceiverBranchStatus() {
        val enabled = parseCastStatusMessage(
            """
            {
              "type":"status",
              "tuning":{
                "justLongBranches":true,
                "branchProbability":{}
              }
            }
            """.trimIndent()
        )
        val disabled = parseCastStatusMessage(
            """
            {
              "type":"status",
              "tuning":{
                "justLongBranches":false,
                "branchProbability":{}
              }
            }
            """.trimIndent()
        )

        assertEquals(20, enabled?.tuning?.minLongBranchPercent)
        assertEquals(0, disabled?.tuning?.minLongBranchPercent)
    }

    @Test
    fun parseCastStatusMessageRejectsInvalidBranchPercentBeforeLegacyFallback() {
        val parsed = parseCastStatusMessage(
            """
            {
              "type":"status",
              "tuning":{
                "justLongBranches":true,
                "minLongBranchPercent":15,
                "branchProbability":{}
              }
            }
            """.trimIndent()
        )

        assertEquals(20, parsed?.tuning?.minLongBranchPercent)
    }

    @Test
    fun parseCastStatusMessageParsesLoadingSupportedAudioModesWithoutTuning() {
        val parsed = parseCastStatusMessage(
            """
            {
              "type":"status",
              "isLoading":true,
              "playbackState":"loading",
              "supportedAudioModes":[
                {"wireValue":"off","label":"Off"},
                {"wireValue":"cowbell","label":"More Cowbell"}
              ],
              "tuning":null
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertTrue(parsed?.isLoading == true)
        assertNull(parsed?.tuning)
        assertEquals(
            listOf(
                AudioModeOption("off", "Off"),
                AudioModeOption("cowbell", "More Cowbell")
            ),
            parsed?.supportedAudioModes
        )
    }

    @Test
    fun parseCastStatusMessageDropsMalformedSupportedAudioModes() {
        val parsed = parseCastStatusMessage(
            """
            {
              "type":"status",
              "supportedAudioModes":[
                {"wireValue":"","label":"Blank Wire"},
                {"wireValue":"lofi","label":""},
                "not-an-object"
              ]
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals(emptyList<AudioModeOption>(), parsed?.supportedAudioModes)
    }

    @Test
    fun parseCastStatusMessageKeepsNullTuning() {
        val parsed = parseCastStatusMessage(
            """
            {
              "type":"status",
              "tuning":null
            }
            """.trimIndent()
        )
        assertNotNull(parsed)
        assertNull(parsed?.tuning)
    }

    @Test
    fun parseCastStatusMessageParsesSnakeCaseErrorCode() {
        val parsed = parseCastStatusMessage(
            """
            {
              "type":"status",
              "error_code":"cast_track_too_long"
            }
            """.trimIndent()
        )
        assertNotNull(parsed)
        assertEquals("cast_track_too_long", parsed?.errorCode)
    }

    @Test
    fun parseCastStatusMessageParsesCamelCaseJobAndCreatedAtFields() {
        val parsed = parseCastStatusMessage(
            """
            {
              "type":"status",
              "jobId":"0123456789abcdef0123456789abcdef",
              "createdAt":"2026-04-17T00:57:46.945271+00:00"
            }
            """.trimIndent()
        )

        assertNotNull(parsed)
        assertEquals("0123456789abcdef0123456789abcdef", parsed?.jobId)
        assertEquals("2026-04-17T00:57:46.945271+00:00", parsed?.createdAt)
    }

    @Test
    fun reduceCastStatusKeepsRunningDuringLoadingState() {
        val current = UiState(
            playback = PlaybackState(
                isRunning = true,
                playTitle = "Existing",
                trackTitle = "Old Track",
                trackArtist = "Old Artist",
                lastYouTubeId = "old_song",
                activeVizIndex = 2,
                analysisErrorMessage = "Previous error"
            ),
            tuning = TuningState(threshold = 22)
        )
        val status = CastStatusMessage(
            title = "",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = false,
            isLoading = false,
            playbackState = "loading",
            error = "",
            activeVizIndex = 4,
        )

        val next = reduceCastStatus(current, status)

        assertTrue(next.playback.isRunning)
        assertTrue(next.playback.isCastLoading)
        // analysisInFlight is owned by the local analysis pipeline; receiver loading must not set it.
        assertFalse(next.playback.analysisInFlight)
        assertEquals("old_song", next.playback.lastYouTubeId)
        assertNull(next.playback.lastJobId)
        assertEquals(4, next.playback.activeVizIndex)
        assertEquals("Existing", next.playback.playTitle)
        assertEquals("Old Track", next.playback.trackTitle)
        assertEquals("Old Artist", next.playback.trackArtist)
        assertEquals("Previous error", next.playback.analysisErrorMessage)
        assertEquals(22, next.tuning.threshold)
    }

    @Test
    fun reduceCastStatusAppliesPlayingStateMetadataAndThreshold() {
        val current = UiState(
            playback = PlaybackState(
                isRunning = false,
                playTitle = "",
                activeVizIndex = 1
            ),
            tuning = TuningState(threshold = 8)
        )
        val status = CastStatusMessage(
            createdAt = serverTimestampMinutesAgo(minutesAgo = 3),
            title = "New Song",
            artist = "New Artist",
            trackDurationSeconds = 189.5,
            totalBeats = 640,
            totalBranches = 82,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 3,
            tuning = castTuning(threshold = 31)
        )

        val next = reduceCastStatus(current, status)

        assertTrue(next.playback.isRunning)
        assertFalse(next.playback.analysisInFlight)
        assertEquals("New Song — New Artist", next.playback.playTitle)
        assertEquals("New Song", next.playback.trackTitle)
        assertEquals("New Artist", next.playback.trackArtist)
        assertFalse(next.playback.isPaused)
        assertEquals(189.5, next.playback.trackDurationSeconds ?: 0.0, 0.0001)
        assertEquals(640, next.playback.castTotalBeats)
        assertEquals(82, next.playback.castTotalBranches)
        assertNull(next.playback.lastYouTubeId)
        assertNull(next.playback.lastJobId)
        assertEquals(3, next.playback.activeVizIndex)
        assertEquals(31, next.tuning.threshold)
    }

    @Test
    fun reduceCastStatusAppliesReceiverTuningAndAudioMode() {
        val current = UiState(
            playback = PlaybackState(
                isRunning = true,
                playTitle = "Old Song",
                trackTitle = "Old Song",
                jukeboxAudioMode = JukeboxAudioMode.Off
            ),
            tuning = TuningState(threshold = 8)
        )
        val status = CastStatusMessage(
            createdAt = serverTimestampMinutesAgo(minutesAgo = 3),
            title = "New Song",
            artist = "New Artist",
            trackDurationSeconds = 189.5,
            totalBeats = 640,
            totalBranches = 82,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 3,
            supportedAudioModes = supportedAudioModes(),
            tuning = castTuning(
                justBackwards = true,
                minLongBranchPercent = 30,
                removeSequentialBranches = true,
                threshold = 31,
                computedThreshold = 29,
                minPercent = 12,
                maxPercent = 45,
                deltaPercent = 20,
                highlightAnchorBranch = true,
                audioMode = JukeboxAudioMode.Daycore
            )
        )

        val next = reduceCastStatus(current, status)

        assertEquals(JukeboxAudioMode.Daycore, next.playback.jukeboxAudioMode)
        assertEquals("daycore", next.playback.castAudioModeWireValue)
        assertEquals(supportedAudioModes(), next.playback.castSupportedAudioModes)
        assertEquals("Old Song (Daycore) — New Artist", next.playback.playTitle)
        assertEquals(31, next.tuning.threshold)
        assertEquals(29, next.tuning.computedThreshold)
        assertEquals(12, next.tuning.minProb)
        assertEquals(45, next.tuning.maxProb)
        assertEquals(20, next.tuning.ramp)
        assertTrue(next.tuning.justBackwards)
        assertEquals(30, next.tuning.minJumpDistancePercent)
        assertTrue(next.tuning.removeSequential)
        assertTrue(next.tuning.highlightAnchorBranch)
    }

    @Test
    fun reduceCastStatusDisplaysReceiverOnlyAudioModeLabel() {
        val current = UiState(
            playback = PlaybackState(
                isRunning = true,
                playTitle = "",
                trackTitle = "Old Song",
                jukeboxAudioMode = JukeboxAudioMode.Off
            )
        )
        val status = CastStatusMessage(
            createdAt = serverTimestampMinutesAgo(minutesAgo = 3),
            title = "New Song",
            artist = "New Artist",
            trackDurationSeconds = 189.5,
            totalBeats = 640,
            totalBranches = 82,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 3,
            supportedAudioModes = listOf(
                AudioModeOption("off", "Off"),
                AudioModeOption("future_mode", "Future Mode")
            ),
            tuning = castTuning(audioModeWireValue = "future_mode")
        )

        val next = reduceCastStatus(current, status)

        assertEquals(JukeboxAudioMode.Off, next.playback.jukeboxAudioMode)
        assertEquals("future_mode", next.playback.castAudioModeWireValue)
        assertEquals("Old Song (Future Mode) — New Artist", next.playback.playTitle)
    }

    @Test
    fun reduceCastStatusClearsSupportedAudioModesWhenStatusOmitsValidOptions() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                castSupportedAudioModes = supportedAudioModes(),
                castAudioModeWireValue = "cowbell",
                playTitle = "Song (More Cowbell)",
                trackTitle = "Song"
            )
        )
        val status = CastStatusMessage(
            title = "",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = null,
            tuning = castTuning(audioMode = JukeboxAudioMode.Cowbell)
        )

        val next = reduceCastStatus(current, status)

        assertEquals(emptyList<AudioModeOption>(), next.playback.castSupportedAudioModes)
        assertEquals("cowbell", next.playback.castAudioModeWireValue)
        assertEquals("Song", next.playback.playTitle)
    }

    @Test
    fun reduceCastStatusDoesNotHydrateTuningWhenReceiverTuningIsNull() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                isRunning = true,
                lastJobId = "job_1",
                jukeboxAudioMode = JukeboxAudioMode.Lofi,
                castAudioModeWireValue = JukeboxAudioMode.Lofi.wireValue
            ),
            tuning = TuningState(threshold = 42, justBackwards = true)
        )
        val status = CastStatusMessage(
            title = "",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = null
        )

        val next = reduceCastStatus(current, status)

        assertEquals(JukeboxAudioMode.Lofi, next.playback.jukeboxAudioMode)
        assertEquals(42, next.tuning.threshold)
        assertTrue(next.tuning.justBackwards)
        assertTrue(next.playback.castReceiverDetailsReady())
    }

    @Test
    fun reduceCastStatusAppliesErrorAndRejectsInvalidVizIndex() {
        val current = UiState(
            playback = PlaybackState(
                isRunning = true,
                activeVizIndex = 5
            ),
            tuning = TuningState(threshold = 19)
        )
        val status = CastStatusMessage(
            title = "",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = true,
            isLoading = false,
            playbackState = "error",
            error = "Receiver error",
            activeVizIndex = 99,
        )

        val next = reduceCastStatus(current, status)

        assertFalse(next.playback.isRunning)
        assertFalse(next.playback.isPaused)
        assertFalse(next.playback.analysisInFlight)
        assertEquals("Receiver error", next.playback.analysisErrorMessage)
        assertEquals(5, next.playback.activeVizIndex)
        assertEquals(19, next.tuning.threshold)
    }

    @Test
    fun reduceCastStatusFallsBackToRawFlagsForUnknownPlaybackState() {
        val current = UiState(
            playback = PlaybackState(isRunning = true)
        )
        val loadingStatus = CastStatusMessage(
            title = "",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = false,
            isLoading = true,
            playbackState = "mystery",
            error = "",
            activeVizIndex = null,
        )
        val loadedPausedStatus = loadingStatus.copy(
            isLoading = false,
            isPlaying = false
        )

        val loading = reduceCastStatus(current, loadingStatus)
        val loadedPaused = reduceCastStatus(current, loadedPausedStatus)

        assertTrue(loading.playback.isCastLoading)
        assertTrue(loading.playback.isRunning)
        assertFalse(loadedPaused.playback.isCastLoading)
        assertFalse(loadedPaused.playback.isRunning)
    }

    @Test
    fun reduceCastStatusTracksCastLoadingFromReceiverState() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                analysisInFlight = true,
                isRunning = true,
                playTitle = "Loading track on cast device...",
                lastYouTubeId = "new_song",
                activeVizIndex = 2
            ),
            tuning = TuningState(threshold = 24)
        )
        val loading = CastStatusMessage(
            title = "",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = false,
            isLoading = true,
            playbackState = "loading",
            error = "",
            activeVizIndex = 4,
        )

        val next = reduceCastStatus(current, loading)

        assertTrue(next.playback.isCastLoading)
        // A running local analysis is preserved across receiver statuses.
        assertTrue(next.playback.analysisInFlight)
    }

    @Test
    fun reduceCastStatusClearsCastLoadingWhenReceiverIsReady() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                analysisInFlight = true,
                isRunning = true,
                lastYouTubeId = "new_song",
                isCastLoading = true,
                playTitle = "Loading track on cast device..."
            )
        )
        val ready = CastStatusMessage(
            createdAt = serverTimestampMinutesAgo(minutesAgo = 2),
            title = "Loaded Song",
            artist = "Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, ready)

        assertFalse(next.playback.isCastLoading)
        // Receiver readiness never clears a running local analysis; that's the analysis pipeline's job.
        assertTrue(next.playback.analysisInFlight)
        assertTrue(next.playback.isRunning)
        assertFalse(next.playback.isPaused)
        assertEquals(201.0, next.playback.trackDurationSeconds ?: 0.0, 0.0001)
        assertEquals(480, next.playback.castTotalBeats)
        assertEquals(56, next.playback.castTotalBranches)
    }

    @Test
    fun reduceCastStatusMarksPausedWhenReceiverReportsPausedState() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                isRunning = true,
                isPaused = false,
                lastYouTubeId = "new_song"
            )
        )
        val paused = CastStatusMessage(
            title = "Loaded Song",
            artist = "Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = false,
            isLoading = false,
            playbackState = "paused",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, paused)

        assertFalse(next.playback.isRunning)
        assertTrue(next.playback.isPaused)
        assertFalse(next.playback.analysisInFlight)
    }

    @Test
    fun reduceCastStatusKeepsExistingAppMetadataWhenReceiverSendsDifferentValues() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                trackTitle = "App Track",
                trackArtist = "App Artist",
                playTitle = "App Track — App Artist",
                lastYouTubeId = "abc123def45"
            )
        )
        val status = CastStatusMessage(
            createdAt = serverTimestampMinutesAgo(minutesAgo = 1),
            title = "Receiver Track",
            artist = "Receiver Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertEquals("App Track", next.playback.trackTitle)
        assertEquals("App Artist", next.playback.trackArtist)
        assertEquals("App Track — App Artist", next.playback.playTitle)
    }

    @Test
    fun reduceCastStatusAppliesReceiverMetadataForMatchingJobWithoutCreatedAt() {
        // Relay upload mode: receiver statuses carry no server createdAt, but a matching jobId is
        // enough to accept per-track metadata. Delete stays disabled without createdAt.
        val jobId = "0123456789abcdef0123456789abcdef"
        val previousCreatedAtEpochMs = OffsetDateTime.now(ZoneOffset.UTC)
            .minusDays(10)
            .toInstant()
            .toEpochMilli()
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                isCastLoading = true,
                lastJobId = jobId,
                lastTrackCreatedAtEpochMs = previousCreatedAtEpochMs,
                deleteEligible = true,
                trackTitle = "Stealth",
                trackArtist = "Bad Religion",
                playTitle = "Stealth — Bad Religion",
                trackDurationSeconds = null,
                castTotalBeats = null,
                castTotalBranches = null
            )
        )
        val status = CastStatusMessage(
            jobId = jobId,
            createdAt = null,
            title = "Gangnam Style (강남스타일)",
            artist = "PSY",
            trackDurationSeconds = 219.50566893424036,
            totalBeats = 475,
            totalBranches = 173,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertEquals("Stealth", next.playback.trackTitle)
        assertEquals("Bad Religion", next.playback.trackArtist)
        assertEquals("Stealth — Bad Religion", next.playback.playTitle)
        assertEquals(219.50566893424036, next.playback.trackDurationSeconds ?: 0.0, 0.0001)
        assertEquals(475, next.playback.castTotalBeats)
        assertEquals(173, next.playback.castTotalBranches)
        assertNull(next.playback.lastTrackCreatedAtEpochMs)
        assertFalse(next.playback.deleteEligible)
    }

    @Test
    fun reduceCastStatusIgnoresReceiverMetadataForMismatchedJobId() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                lastJobId = "aaaa111122223333",
                trackTitle = null,
                trackArtist = null,
                playTitle = "",
                trackDurationSeconds = 180.0,
                castTotalBeats = 400,
                castTotalBranches = 40
            )
        )
        val status = CastStatusMessage(
            jobId = "cccc777788889999",
            createdAt = serverTimestampMinutesAgo(minutesAgo = 1),
            title = "Other Track",
            artist = "Other Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertNull(next.playback.trackTitle)
        assertNull(next.playback.trackArtist)
        assertEquals(180.0, next.playback.trackDurationSeconds ?: 0.0, 0.0001)
        assertEquals(400, next.playback.castTotalBeats)
        assertEquals(40, next.playback.castTotalBranches)
    }

    @Test
    fun reduceCastStatusBackfillsTitleForMatchingJobWithoutCreatedAt() {
        val jobId = "0123456789abcdef0123456789abcdef"
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                isCastLoading = false,
                lastJobId = jobId,
                lastTrackCreatedAtEpochMs = OffsetDateTime.now(ZoneOffset.UTC)
                    .minusMinutes(5)
                    .toInstant()
                    .toEpochMilli(),
                trackTitle = null,
                trackArtist = null,
                playTitle = ""
            )
        )
        val status = CastStatusMessage(
            jobId = jobId,
            createdAt = null,
            title = "Receiver Track",
            artist = "Receiver Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertEquals("Receiver Track", next.playback.trackTitle)
        assertEquals("Receiver Artist", next.playback.trackArtist)
        assertEquals("Receiver Track — Receiver Artist", next.playback.playTitle)
        assertEquals(201.0, next.playback.trackDurationSeconds ?: 0.0, 0.0001)
        assertEquals(480, next.playback.castTotalBeats)
        assertEquals(56, next.playback.castTotalBranches)
    }

    @Test
    fun reduceCastStatusBackfillsMissingMetadataFromReceiver() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                trackTitle = null,
                trackArtist = null,
                playTitle = "",
                lastYouTubeId = null
            )
        )
        val status = CastStatusMessage(
            createdAt = serverTimestampMinutesAgo(minutesAgo = 1),
            title = "Receiver Track",
            artist = "Receiver Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertEquals("Receiver Track", next.playback.trackTitle)
        assertEquals("Receiver Artist", next.playback.trackArtist)
        assertEquals("Receiver Track — Receiver Artist", next.playback.playTitle)
        assertNull(next.playback.lastYouTubeId)
    }

    @Test
    fun reduceCastStatusEnablesDeleteForRecentCreatedAtWithJobId() {
        val jobId = "0123456789abcdef0123456789abcdef"
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                deleteEligible = false
            )
        )
        val status = CastStatusMessage(
            jobId = jobId,
            createdAt = serverTimestampMinutesAgo(minutesAgo = 5),
            title = "Track",
            artist = "Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertTrue(next.playback.deleteEligible)
        assertEquals(jobId, next.playback.lastJobId)
    }

    @Test
    fun reduceCastStatusDisablesDeleteWhenCreatedAtMissing() {
        val jobId = "0123456789abcdef0123456789abcdef"
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                lastJobId = jobId,
                deleteEligible = true
            )
        )
        val status = CastStatusMessage(
            jobId = jobId,
            createdAt = null,
            title = "Track",
            artist = "Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertFalse(next.playback.deleteEligible)
    }

    @Test
    fun reduceCastStatusRetainsCreatedAtAcrossStatusUpdatesForSameJob() {
        val jobId = "0123456789abcdef0123456789abcdef"
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                lastJobId = jobId,
                lastTrackCreatedAtEpochMs = OffsetDateTime.now(ZoneOffset.UTC)
                    .minusMinutes(2)
                    .toInstant()
                    .toEpochMilli(),
                deleteEligible = true
            )
        )
        val status = CastStatusMessage(
            jobId = jobId,
            createdAt = null,
            title = "Track",
            artist = "Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertTrue(next.playback.deleteEligible)
        assertEquals(jobId, next.playback.lastJobId)
        assertEquals(
            current.playback.lastTrackCreatedAtEpochMs,
            next.playback.lastTrackCreatedAtEpochMs
        )
    }

    @Test
    fun reduceCastStatusKeepsExistingJobIdWhenStatusJobIdMissing() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                lastJobId = "0123456789abcdef0123456789abcdef"
            )
        )
        val status = CastStatusMessage(
            jobId = null,
            createdAt = null,
            title = "Track",
            artist = "Artist",
            trackDurationSeconds = 201.0,
            totalBeats = 480,
            totalBranches = 56,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 1,
        )

        val next = reduceCastStatus(current, status)

        assertEquals("0123456789abcdef0123456789abcdef", next.playback.lastJobId)
    }

    @Test
    fun reduceCastStatusPreservesTransferWhileOldTrackStillReportsStatus() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                lastJobId = "aaaa111122223333",
                castTransfer = CastTransfer.Uploading("bbbb444455556666", percent = 40)
            )
        )
        val oldTrackStatus = CastStatusMessage(
            jobId = "aaaa111122223333",
            title = "Old Song",
            artist = "Old Artist",
            trackDurationSeconds = 180.0,
            totalBeats = 400,
            totalBranches = 40,
            isPlaying = true,
            isLoading = false,
            playbackState = "playing",
            error = "",
            activeVizIndex = 0,
        )

        val next = reduceCastStatus(current, oldTrackStatus)

        assertEquals(
            CastTransfer.Uploading("bbbb444455556666", percent = 40),
            next.playback.castTransfer
        )
        assertNull(next.playback.trackTitle)
        assertNull(next.playback.trackDurationSeconds)
        assertNull(next.playback.castTotalBeats)
        assertNull(next.playback.castTotalBranches)
    }

    @Test
    fun reduceCastStatusClearsTransferWhenReceiverAcksTransferredTrack() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                castTransfer = CastTransfer.WaitingForReceiver("bbbb444455556666")
            )
        )
        val ackStatus = CastStatusMessage(
            jobId = "bbbb444455556666",
            title = "New Song",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = false,
            isLoading = true,
            playbackState = "loading",
            error = "",
            activeVizIndex = null,
        )

        val next = reduceCastStatus(current, ackStatus)

        assertNull(next.playback.castTransfer)
    }

    @Test
    fun reduceCastStatusClearsPreviousTrackMetadataWhenTransferredTrackAcks() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                lastJobId = "aaaa111122223333",
                trackDurationSeconds = 180.0,
                castTotalBeats = 400,
                castTotalBranches = 40,
                castTransfer = CastTransfer.WaitingForReceiver("bbbb444455556666")
            )
        )
        val ackStatus = CastStatusMessage(
            jobId = "bbbb444455556666",
            title = "New Song",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = false,
            isLoading = true,
            playbackState = "loading",
            error = "",
            activeVizIndex = null,
        )

        val next = reduceCastStatus(current, ackStatus)

        assertNull(next.playback.castTransfer)
        assertNull(next.playback.trackDurationSeconds)
        assertNull(next.playback.castTotalBeats)
        assertNull(next.playback.castTotalBranches)
        assertEquals("bbbb444455556666", next.playback.lastJobId)
    }

    @Test
    fun reduceCastStatusClearsTransferOnReceiverError() {
        val current = UiState(
            playback = PlaybackState(
                isCasting = true,
                castTransfer = CastTransfer.WaitingForReceiver("bbbb444455556666")
            )
        )
        val errorStatus = CastStatusMessage(
            jobId = null,
            title = "",
            artist = "",
            trackDurationSeconds = null,
            totalBeats = null,
            totalBranches = null,
            isPlaying = false,
            isLoading = false,
            playbackState = "error",
            error = "Receiver exploded",
            activeVizIndex = null,
        )

        val next = reduceCastStatus(current, errorStatus)

        assertNull(next.playback.castTransfer)
        assertEquals("Receiver exploded", next.playback.analysisErrorMessage)
    }

    private fun serverTimestampMinutesAgo(minutesAgo: Long): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSxxx")
        return OffsetDateTime.now(ZoneOffset.UTC)
            .minusMinutes(minutesAgo)
            .withNano(945_271_000)
            .format(formatter)
    }

    private fun castTuning(
        justBackwards: Boolean = false,
        justLongBranches: Boolean = false,
        minLongBranchPercent: Int = if (justLongBranches) 20 else 0,
        removeSequentialBranches: Boolean = false,
        threshold: Int? = null,
        computedThreshold: Int? = null,
        minPercent: Int = 18,
        maxPercent: Int = 50,
        deltaPercent: Int = 10,
        deletedEdgeIds: List<Int> = emptyList(),
        highlightAnchorBranch: Boolean = false,
        audioMode: JukeboxAudioMode = JukeboxAudioMode.Off,
        audioModeWireValue: String = audioMode.wireValue
    ): CastTuningStatus {
        return CastTuningStatus(
            justBackwards = justBackwards,
            justLongBranches = justLongBranches,
            minLongBranchPercent = minLongBranchPercent,
            removeSequentialBranches = removeSequentialBranches,
            threshold = threshold,
            computedThreshold = computedThreshold,
            branchProbability = CastBranchProbabilityStatus(
                minPercent = minPercent,
                maxPercent = maxPercent,
                deltaPercent = deltaPercent
            ),
            deletedEdgeIds = deletedEdgeIds,
            highlightAnchorBranch = highlightAnchorBranch,
            audioModeWireValue = audioModeWireValue
        )
    }

    private fun supportedAudioModes(): List<AudioModeOption> {
        return listOf(
            AudioModeOption("off", "Off"),
            AudioModeOption("daycore", "Daycore"),
            AudioModeOption("cowbell", "More Cowbell")
        )
    }
}
