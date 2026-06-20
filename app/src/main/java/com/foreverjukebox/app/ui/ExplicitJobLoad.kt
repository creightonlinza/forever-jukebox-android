package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AnalysisResponse

internal suspend fun loadExplicitJobInitialResponse(
    fetchJob: suspend () -> AnalysisResponse,
    retryJob: suspend () -> AnalysisResponse
): AnalysisResponse {
    val initialResponse = fetchJob()
    return if (initialResponse.status == "failed") {
        retryJob()
    } else {
        initialResponse
    }
}
