package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.AppMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportUiPolicyTest {

    private fun exportablePlayback(): PlaybackState {
        return PlaybackState(
            audioLoaded = true,
            analysisLoaded = true
        )
    }

    @Test
    fun showsExportForLoadedLocalJukeboxTrack() {
        assertTrue(shouldShowExportAction(AppMode.Local, exportablePlayback(), sdkInt = 29))
    }

    @Test
    fun hidesExportOutsideLocalMode() {
        assertFalse(shouldShowExportAction(AppMode.Server, exportablePlayback(), sdkInt = 29))
        assertFalse(shouldShowExportAction(null, exportablePlayback(), sdkInt = 29))
    }

    @Test
    fun hidesExportBelowMinimumSdk() {
        assertFalse(shouldShowExportAction(AppMode.Local, exportablePlayback(), sdkInt = 28))
    }

    @Test
    fun hidesExportWhileCasting() {
        val playback = exportablePlayback().copy(isCasting = true)
        assertFalse(shouldShowExportAction(AppMode.Local, playback, sdkInt = 29))
    }

    @Test
    fun hidesExportInAutocanonizerMode() {
        val playback = exportablePlayback().copy(playMode = PlaybackMode.Autocanonizer)
        assertFalse(shouldShowExportAction(AppMode.Local, playback, sdkInt = 29))
    }

    @Test
    fun hidesExportUntilTrackFullyLoaded() {
        assertFalse(
            shouldShowExportAction(
                AppMode.Local,
                exportablePlayback().copy(audioLoaded = false),
                sdkInt = 29
            )
        )
        assertFalse(
            shouldShowExportAction(
                AppMode.Local,
                exportablePlayback().copy(analysisLoaded = false),
                sdkInt = 29
            )
        )
    }

    @Test
    fun hidesExportWhileAnotherTrackIsLoading() {
        val playback = exportablePlayback().copy(analysisInFlight = true)
        assertFalse(shouldShowExportAction(AppMode.Local, playback, sdkInt = 29))
    }

    @Test
    fun exportControlsStayVisibleForRunningExport() {
        val casting = exportablePlayback().copy(isCasting = true)
        assertFalse(shouldShowExportControls(AppMode.Local, casting, isExporting = false, sdkInt = 29))
        assertTrue(shouldShowExportControls(AppMode.Local, casting, isExporting = true, sdkInt = 29))
        val autocanonizer = exportablePlayback().copy(playMode = PlaybackMode.Autocanonizer)
        assertTrue(shouldShowExportControls(AppMode.Local, autocanonizer, isExporting = true, sdkInt = 29))
        assertTrue(shouldShowExportControls(AppMode.Local, exportablePlayback(), isExporting = false, sdkInt = 29))
    }

    @Test
    fun clampsExportDurationToAllowedRange() {
        assertEquals(EXPORT_MIN_DURATION_SECONDS, clampExportDurationSeconds(0))
        assertEquals(EXPORT_MIN_DURATION_SECONDS, clampExportDurationSeconds(-10))
        assertEquals(60, clampExportDurationSeconds(60))
        assertEquals(EXPORT_MAX_DURATION_SECONDS, clampExportDurationSeconds(Int.MAX_VALUE))
    }

    @Test
    fun defaultDurationTracksTheLoadedTrackLength() {
        assertEquals(200, defaultExportDurationSeconds(200.4))
        assertEquals(EXPORT_MIN_DURATION_SECONDS, defaultExportDurationSeconds(3.2))
        assertEquals(EXPORT_MAX_DURATION_SECONDS, defaultExportDurationSeconds(9000.0))
        assertEquals(60, defaultExportDurationSeconds(null))
    }

    @Test
    fun exportSummaryNamesTheActiveAudioMode() {
        assertEquals(
            "Exports using current tuning and deleted branches.",
            exportContentsSummary(JukeboxAudioMode.Off, AudioModeIntensity.DEFAULT)
        )
        assertEquals(
            "Exports using current tuning and deleted branches. Audio mode: Cathedral.",
            exportContentsSummary(JukeboxAudioMode.Cathedral, AudioModeIntensity.DEFAULT)
        )
        assertEquals(
            "Exports using current tuning and deleted branches. Audio mode: Nightcore (intensity 120).",
            exportContentsSummary(JukeboxAudioMode.Nightcore, 120)
        )
    }
}
