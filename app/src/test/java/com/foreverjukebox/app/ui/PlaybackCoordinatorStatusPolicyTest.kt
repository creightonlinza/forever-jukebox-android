package com.foreverjukebox.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCoordinatorStatusPolicyTest {

    @Test
    fun analysisInProgressStatusIncludesQueuedDownloadingAndProcessing() {
        assertTrue(isAnalysisInProgressStatus("downloading"))
        assertTrue(isAnalysisInProgressStatus("queued"))
        assertTrue(isAnalysisInProgressStatus("processing"))
    }

    @Test
    fun analysisInProgressStatusExcludesFailedCompleteAndNull() {
        assertFalse(isAnalysisInProgressStatus("failed"))
        assertFalse(isAnalysisInProgressStatus("complete"))
        assertFalse(isAnalysisInProgressStatus("unknown"))
        assertFalse(isAnalysisInProgressStatus(null))
    }

    @Test
    fun completeAudioLoadRetriesUntilMaxAttempt() {
        assertTrue(shouldRetryCompleteAudioLoad(1))
        assertTrue(shouldRetryCompleteAudioLoad(2))
        assertFalse(shouldRetryCompleteAudioLoad(COMPLETE_AUDIO_LOAD_MAX_ATTEMPTS))
    }

    @Test
    fun completeAudioLoadRetryPolicyRejectsInvalidAttemptsOrBudget() {
        assertFalse(shouldRetryCompleteAudioLoad(0))
        assertFalse(shouldRetryCompleteAudioLoad(1, maxAttempts = 1))
    }
}
