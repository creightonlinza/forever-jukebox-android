package com.foreverjukebox.app.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadingAudioFeedbackControllerTest {

    @Test
    fun disabledLoadingDoesNotStartPulse() {
        val player = FakeLoadingAudioFeedbackPlayer()
        val controller = LoadingAudioFeedbackController(player)

        controller.update(
            enabled = false,
            loading = true,
            failureMessage = null
        )

        assertEquals(0, player.startCount)
        assertEquals(1, player.stopCount)
        assertEquals(0, player.failureCount)
    }

    @Test
    fun enabledLoadingStartsPulse() {
        val player = FakeLoadingAudioFeedbackPlayer()
        val controller = LoadingAudioFeedbackController(player)

        controller.update(
            enabled = true,
            loading = true,
            failureMessage = null
        )

        assertEquals(1, player.startCount)
        assertEquals(0, player.failureCount)
    }

    @Test
    fun loadingCompletionStopsPulseWithoutFailure() {
        val player = FakeLoadingAudioFeedbackPlayer()
        val controller = LoadingAudioFeedbackController(player)

        controller.update(enabled = true, loading = true, failureMessage = null)
        controller.update(enabled = true, loading = false, failureMessage = null)

        assertEquals(1, player.startCount)
        assertEquals(1, player.stopCount)
        assertEquals(0, player.failureCount)
    }

    @Test
    fun failureAfterActiveLoadingPlaysFailureOnce() {
        val player = FakeLoadingAudioFeedbackPlayer()
        val controller = LoadingAudioFeedbackController(player)

        controller.update(enabled = true, loading = true, failureMessage = null)
        controller.update(enabled = true, loading = false, failureMessage = "Loading failed.")
        controller.update(enabled = true, loading = false, failureMessage = "Loading failed.")

        assertEquals(1, player.startCount)
        assertEquals(2, player.stopCount)
        assertEquals(1, player.failureCount)
    }

    @Test
    fun cancellationAfterActiveLoadingDoesNotPlayFailure() {
        val player = FakeLoadingAudioFeedbackPlayer()
        val controller = LoadingAudioFeedbackController(player)

        controller.update(enabled = true, loading = true, failureMessage = null)
        controller.update(
            enabled = true,
            loading = false,
            failureMessage = LoadingAudioFeedbackController.LOCAL_ANALYSIS_CANCELLED_MESSAGE
        )

        assertEquals(0, player.failureCount)
    }

    @Test
    fun disablingWhileLoadingStopsPulseAndSuppressesLaterFailure() {
        val player = FakeLoadingAudioFeedbackPlayer()
        val controller = LoadingAudioFeedbackController(player)

        controller.update(enabled = true, loading = true, failureMessage = null)
        controller.update(enabled = false, loading = true, failureMessage = null)
        controller.update(enabled = false, loading = false, failureMessage = "Loading failed.")

        assertEquals(1, player.startCount)
        assertEquals(2, player.stopCount)
        assertEquals(0, player.failureCount)
    }

    @Test
    fun releaseReleasesPlayer() {
        val player = FakeLoadingAudioFeedbackPlayer()
        val controller = LoadingAudioFeedbackController(player)

        controller.release()

        assertEquals(1, player.releaseCount)
    }
}

private class FakeLoadingAudioFeedbackPlayer : LoadingAudioFeedbackPlayer {
    var startCount = 0
    var stopCount = 0
    var failureCount = 0
    var releaseCount = 0

    override fun startLoadingPulse() {
        startCount += 1
    }

    override fun stopLoadingPulse() {
        stopCount += 1
    }

    override fun playFailure() {
        failureCount += 1
    }

    override fun release() {
        releaseCount += 1
    }
}
