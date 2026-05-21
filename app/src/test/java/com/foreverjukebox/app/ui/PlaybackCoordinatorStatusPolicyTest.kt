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
}
