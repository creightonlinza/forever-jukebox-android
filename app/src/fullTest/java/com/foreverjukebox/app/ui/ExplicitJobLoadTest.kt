package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AnalysisResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ExplicitJobLoadTest {

    @Test
    fun failedInitialResponseUsesSuccessfulRestartResponse() = runTest {
        val failed = AnalysisResponse(id = "job_123", status = "failed")
        val restarted = AnalysisResponse(id = "job_123", status = "queued")
        var retryCalls = 0

        val result = loadExplicitJobInitialResponse(
            fetchJob = { failed },
            retryJob = {
                retryCalls += 1
                restarted
            }
        )

        assertSame(restarted, result)
        assertEquals(1, retryCalls)
    }

    @Test
    fun backendRejectedRestartReturnsFailureWithoutRetryLoop() = runTest {
        val initialFailure = AnalysisResponse(id = "job_123", status = "failed")
        val rejectedRestart = AnalysisResponse(
            id = "job_123",
            status = "failed",
            error = "Retry is not allowed"
        )
        var retryCalls = 0

        val result = loadExplicitJobInitialResponse(
            fetchJob = { initialFailure },
            retryJob = {
                retryCalls += 1
                rejectedRestart
            }
        )

        assertSame(rejectedRestart, result)
        assertEquals(1, retryCalls)
    }

    @Test
    fun activeInitialResponseContinuesWithoutRetry() = runTest {
        val processing = AnalysisResponse(id = "job_123", status = "processing")
        var retryCalls = 0

        val result = loadExplicitJobInitialResponse(
            fetchJob = { processing },
            retryJob = {
                retryCalls += 1
                AnalysisResponse(id = "job_123", status = "queued")
            }
        )

        assertSame(processing, result)
        assertEquals(0, retryCalls)
    }
}
