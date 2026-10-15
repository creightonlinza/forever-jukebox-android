package com.foreverjukebox.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JukeboxCommandHandlerTest {

    private class FakeExternalTransport(
        var running: Boolean = false,
        var paused: Boolean = false,
        private val resumes: Boolean = true
    ) : ExternalTransport {
        val calls = mutableListOf<String>()

        override fun isRunning(): Boolean = running

        override fun isPaused(): Boolean = paused

        override fun pause() {
            calls += "pause"
            running = false
            paused = true
        }

        override fun resume(): Boolean {
            calls += "resume"
            if (resumes) {
                running = true
                paused = false
            }
            return resumes
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private class FakeTarget(
        var playing: Boolean = false,
        var audio: Boolean = true,
        var owner: Boolean = true,
        var external: ExternalTransport? = null,
        var focusGranted: Boolean = true
    ) : JukeboxTransportTarget {
        val calls = mutableListOf<String>()

        override fun isPlaying(): Boolean = playing

        override fun hasAudio(): Boolean = audio

        override fun hasOwner(): Boolean = owner

        override fun activeExternalTransport(): ExternalTransport? = external

        override fun requestAudioFocusForLocalPlayback(): Boolean {
            calls += "requestFocus"
            return focusGranted
        }

        override fun startExternalPlayback(resetTimers: Boolean) {
            calls += "startExternal(resetTimers=$resetTimers)"
        }

        override fun playOrResumePlayback(): Boolean {
            calls += "playOrResume"
            playing = true
            return true
        }

        override fun pausePlayback() {
            calls += "pause"
            playing = false
        }

        override fun pauseExternalPlayback() {
            calls += "pauseExternal"
        }

        override fun stopAllPlayback() {
            calls += "stopAll"
            playing = false
        }
    }

    private class FakeEvents(private val castDelivered: Boolean = true) : JukeboxSessionEvents {
        val calls = mutableListOf<String>()

        override fun retryFailedLoad() {
            calls += "retry"
        }

        override fun skipToPrevious() {
            calls += "previous"
        }

        override fun skipToNext() {
            calls += "next"
        }

        override fun localPlaybackStateChanged() {
            calls += "stateChanged"
        }

        override fun sendCastCommand(command: String): Boolean {
            calls += "cast:$command"
            return castDelivered
        }

        override fun stopSession() {
            calls += "stopSession"
        }
    }

    private val warnings = mutableListOf<String>()

    private fun handler(target: FakeTarget, events: FakeEvents) =
        JukeboxCommandHandler(target, events) { warnings += it }

    private val cast = PlaybackSessionSurface.Cast(isPlaying = true, title = "T", artist = "A")

    @Test
    fun localPlayStartsPausedPlaybackAndAsksTheUiToResync() {
        val target = FakeTarget(playing = false)
        val events = FakeEvents()

        val override = handler(target, events).handle(PlaybackAction.Play, PlaybackSessionSurface.Local)

        assertNull(override)
        assertEquals(listOf("playOrResume"), target.calls)
        assertEquals(listOf("stateChanged"), events.calls)
    }

    @Test
    fun localPlayWhileAlreadyPlayingLeavesPlaybackAlone() {
        val target = FakeTarget(playing = true)
        val events = FakeEvents()

        handler(target, events).handle(PlaybackAction.Play, PlaybackSessionSurface.Local)

        assertEquals(emptyList<String>(), target.calls)
        assertEquals(listOf("stateChanged"), events.calls)
    }

    @Test
    fun localPausePausesTheJukebox() {
        val target = FakeTarget(playing = true)
        val events = FakeEvents()

        handler(target, events).handle(PlaybackAction.Pause, PlaybackSessionSurface.Local)

        assertEquals(listOf("pause"), target.calls)
        assertEquals(listOf("stateChanged"), events.calls)
    }

    @Test
    fun localStopStopsEveryTransport() {
        val target = FakeTarget(playing = true)
        val events = FakeEvents()

        val override = handler(target, events).handle(PlaybackAction.Stop, PlaybackSessionSurface.Local)

        assertEquals(listOf("stopAll"), target.calls)
        assertEquals(listOf("stateChanged"), events.calls)
        assertEquals(SessionOverride(stopped = true), override)
    }

    @Test
    fun pauseActsOnTheRunningExternalTransportInsteadOfTheJukebox() {
        val external = FakeExternalTransport(running = true)
        val target = FakeTarget(playing = true, external = external)

        handler(target, FakeEvents()).handle(PlaybackAction.Pause, PlaybackSessionSurface.Local)

        assertEquals(listOf("pause"), external.calls)
        assertEquals(listOf("pauseExternal"), target.calls)
    }

    @Test
    fun playResumesAPausedExternalTransportWithoutResettingTimers() {
        val external = FakeExternalTransport(paused = true)
        val target = FakeTarget(playing = false, external = external)

        handler(target, FakeEvents()).handle(PlaybackAction.Play, PlaybackSessionSurface.Local)

        assertEquals(listOf("resume"), external.calls)
        assertEquals(listOf("requestFocus", "startExternal(resetTimers=false)"), target.calls)
    }

    @Test
    fun deniedFocusKeepsThePausedExternalTransportPaused() {
        val external = FakeExternalTransport(paused = true)
        val target = FakeTarget(playing = false, external = external, focusGranted = false)

        handler(target, FakeEvents()).handle(PlaybackAction.Play, PlaybackSessionSurface.Local)

        assertEquals(emptyList<String>(), external.calls)
        assertEquals(listOf("requestFocus"), target.calls)
    }

    @Test
    fun playWithNothingLoadedResyncsTheOwnerInsteadOfPlaying() {
        val target = FakeTarget(playing = false, audio = false, owner = true)
        val events = FakeEvents()

        handler(target, events).handle(PlaybackAction.Play, PlaybackSessionSurface.Local)

        assertEquals(emptyList<String>(), target.calls)
        assertEquals(listOf("stateChanged"), events.calls)
        assertEquals(1, warnings.size)
    }

    @Test
    fun playWithNothingLoadedAndNoOwnerEndsTheSession() {
        val target = FakeTarget(playing = false, audio = false, owner = false)
        val events = FakeEvents()

        handler(target, events).handle(PlaybackAction.Play, PlaybackSessionSurface.Local)

        assertEquals(emptyList<String>(), target.calls)
        assertEquals(listOf("stopSession"), events.calls)
    }

    @Test
    fun pressesWhileLoadingAreDropped() {
        val target = FakeTarget()
        val events = FakeEvents()
        val handler = handler(target, events)

        PlaybackAction.entries.forEach { action ->
            handler.handle(action, PlaybackSessionSurface.Loading(progressBucket = null))
        }
        handler.skipToNext(PlaybackSessionSurface.Loading(progressBucket = 10))
        handler.skipToPrevious(PlaybackSessionSurface.Loading(progressBucket = 10))

        assertEquals(emptyList<String>(), target.calls)
        assertEquals(emptyList<String>(), events.calls)
    }

    @Test
    fun playOnFailedLoadAsksForRetryAndPauseIsIgnored() {
        val target = FakeTarget()
        val events = FakeEvents()
        val handler = handler(target, events)

        assertNull(handler.handle(PlaybackAction.Play, PlaybackSessionSurface.LoadFailed))
        assertNull(handler.handle(PlaybackAction.Pause, PlaybackSessionSurface.LoadFailed))

        assertEquals(emptyList<String>(), target.calls)
        assertEquals(listOf("retry"), events.calls)
    }

    @Test
    fun stopOnFailedLoadOnlyMarksTheSurfaceStopped() {
        val target = FakeTarget()
        val events = FakeEvents()

        val override = handler(target, events).handle(PlaybackAction.Stop, PlaybackSessionSurface.LoadFailed)

        // A dismissal arrives as this stop; it must not retry or touch playback.
        assertEquals(SessionOverride(stopped = true), override)
        assertEquals(emptyList<String>(), target.calls)
        assertEquals(emptyList<String>(), events.calls)
    }

    @Test
    fun stopWhileLoadingIsNotMarked() {
        assertNull(
            handler(FakeTarget(), FakeEvents())
                .handle(PlaybackAction.Stop, PlaybackSessionSurface.Loading(progressBucket = null))
        )
    }

    @Test
    fun castPressesAreForwardedToTheReceiverAndOverrideTheShownState() {
        val target = FakeTarget()
        val events = FakeEvents()
        val handler = handler(target, events)

        val paused = handler.handle(PlaybackAction.Pause, cast)
        val played = handler.handle(PlaybackAction.Play, cast)
        val stopped = handler.handle(PlaybackAction.Stop, cast)

        assertEquals(SessionOverride(castPlaying = false), paused)
        assertEquals(SessionOverride(castPlaying = true), played)
        assertEquals(SessionOverride(castPlaying = false, stopped = true), stopped)
        assertEquals(listOf("cast:pause", "cast:play", "cast:stop"), events.calls)
        assertEquals(emptyList<String>(), target.calls)
    }

    @Test
    fun undeliveredCastPressMarksTheReceiverUnreachable() {
        val events = FakeEvents(castDelivered = false)

        val override = handler(FakeTarget(), events).handle(PlaybackAction.Pause, cast)

        assertEquals(SessionOverride(castUnreachable = true), override)
    }

    @Test
    fun skipsReachThePlaylistForLocalAndCastPlayback() {
        val events = FakeEvents()
        val handler = handler(FakeTarget(), events)

        handler.skipToNext(PlaybackSessionSurface.Local)
        handler.skipToPrevious(cast)

        assertEquals(listOf("next", "previous"), events.calls)
    }

    @Test
    fun skipsAreIgnoredOnFailedAndHiddenSurfaces() {
        val events = FakeEvents()
        val handler = handler(FakeTarget(), events)

        handler.skipToNext(PlaybackSessionSurface.LoadFailed)
        handler.skipToPrevious(PlaybackSessionSurface.Hidden)

        assertEquals(emptyList<String>(), events.calls)
    }

    @Test
    fun playPressOnFailedNotificationBroadcastsRetry() {
        // While the failed notification is showing, the transport button (and any
        // play/play-pause media key) must hand the press to the app's retry flow
        // instead of touching local playback.
        assertEquals(
            TransportActionRoute.BroadcastRetry,
            routeTransportAction(
                isLoading = false,
                isLoadFailed = true,
                action = PlaybackAction.Play
            )
        )
    }

    @Test
    fun pauseAndStopPressesOnFailedNotificationAreIgnored() {
        assertEquals(
            TransportActionRoute.Ignore,
            routeTransportAction(
                isLoading = false,
                isLoadFailed = true,
                action = PlaybackAction.Pause
            )
        )
        assertEquals(
            TransportActionRoute.Ignore,
            routeTransportAction(
                isLoading = false,
                isLoadFailed = true,
                action = PlaybackAction.Stop
            )
        )
    }

    @Test
    fun allPressesWhileLoadingAreIgnored() {
        PlaybackAction.entries.forEach { action ->
            assertEquals(
                TransportActionRoute.Ignore,
                routeTransportAction(
                    isLoading = true,
                    isLoadFailed = false,
                    action = action
                )
            )
        }
    }

    @Test
    fun pressesOnNormalNotificationReachPlaybackHandling() {
        PlaybackAction.entries.forEach { action ->
            assertEquals(
                TransportActionRoute.Handle,
                routeTransportAction(
                    isLoading = false,
                    isLoadFailed = false,
                    action = action
                )
            )
        }
    }

    @Test
    fun playPressWithNothingLoadedIsRecognized() {
        assertTrue(
            isPlayRequestWithoutAudio(
                action = PlaybackAction.Play,
                hasAudio = false,
                isPlaying = false,
                externalTransportActive = false
            )
        )
    }

    @Test
    fun pressesWithSomethingToControlAreNotTreatedAsEmpty() {
        PlaybackAction.entries.forEach { action ->
            assertFalse(
                isPlayRequestWithoutAudio(
                    action = action,
                    hasAudio = true,
                    isPlaying = false,
                    externalTransportActive = false
                )
            )
            assertFalse(
                isPlayRequestWithoutAudio(
                    action = action,
                    hasAudio = false,
                    isPlaying = false,
                    externalTransportActive = true
                )
            )
            assertFalse(
                isPlayRequestWithoutAudio(
                    action = action,
                    hasAudio = false,
                    isPlaying = true,
                    externalTransportActive = false
                )
            )
        }
    }

    @Test
    fun pauseAndStopPressesWithNothingLoadedKeepNormalHandling() {
        listOf(PlaybackAction.Pause, PlaybackAction.Stop).forEach { action ->
            assertFalse(
                isPlayRequestWithoutAudio(
                    action = action,
                    hasAudio = false,
                    isPlaying = false,
                    externalTransportActive = false
                )
            )
        }
    }
}
