package com.foreverjukebox.app

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessExitDiagnosticsTest {
    @Test
    fun namesTheExitReasonsThatExplainAKilledPlaybackProcess() {
        assertEquals("user_requested", exitReasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("low_memory", exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("crash", exitReasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("other", exitReasonName(ApplicationExitInfo.REASON_OTHER))
        assertEquals("freezer", exitReasonName(ApplicationExitInfo.REASON_FREEZER))
    }

    @Test
    fun keepsTheRawCodeForReasonsAddedAfterThisBuild() {
        assertEquals("unknown(99)", exitReasonName(99))
    }
}
