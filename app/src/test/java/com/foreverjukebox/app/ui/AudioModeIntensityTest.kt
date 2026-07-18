package com.foreverjukebox.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioModeIntensityTest {

    @Test
    fun onlyNightcoreDaycoreVaporwaveSupportIntensity() {
        assertTrue(JukeboxAudioMode.Nightcore.supportsIntensity)
        assertTrue(JukeboxAudioMode.Daycore.supportsIntensity)
        assertTrue(JukeboxAudioMode.Vaporwave.supportsIntensity)
        JukeboxAudioMode.entries
            .filterNot {
                it == JukeboxAudioMode.Nightcore ||
                    it == JukeboxAudioMode.Daycore ||
                    it == JukeboxAudioMode.Vaporwave
            }
            .forEach { assertFalse(it.supportsIntensity) }
    }

    @Test
    fun clampCoercesToRange() {
        assertEquals(50, AudioModeIntensity.clamp(0))
        assertEquals(50, AudioModeIntensity.clamp(50))
        assertEquals(100, AudioModeIntensity.clamp(100))
        assertEquals(150, AudioModeIntensity.clamp(150))
        assertEquals(150, AudioModeIntensity.clamp(999))
    }

    @Test
    fun parseDefaultsOnMissingOrInvalidInput() {
        assertEquals(100, AudioModeIntensity.parse(null, JukeboxAudioMode.Nightcore))
        assertEquals(100, AudioModeIntensity.parse("", JukeboxAudioMode.Nightcore))
        assertEquals(100, AudioModeIntensity.parse("abc", JukeboxAudioMode.Nightcore))
        assertEquals(100, AudioModeIntensity.parse("150", null))
        assertEquals(100, AudioModeIntensity.parse("150", JukeboxAudioMode.Lofi))
    }

    @Test
    fun parseClampsValidInput() {
        assertEquals(150, AudioModeIntensity.parse("150", JukeboxAudioMode.Vaporwave))
        assertEquals(50, AudioModeIntensity.parse("13", JukeboxAudioMode.Vaporwave))
        assertEquals(150, AudioModeIntensity.parse("400", JukeboxAudioMode.Vaporwave))
        assertEquals(130, AudioModeIntensity.parse(" 130 ", JukeboxAudioMode.Vaporwave))
    }

    @Test
    fun wireParamEmittedOnlyForNonDefaultIntensityOnCapableMode() {
        assertEquals("ai=150", AudioModeIntensity.wireParamOrNull(JukeboxAudioMode.Nightcore, 150))
        assertNull(AudioModeIntensity.wireParamOrNull(JukeboxAudioMode.Nightcore, 100))
        assertNull(AudioModeIntensity.wireParamOrNull(JukeboxAudioMode.Lofi, 150))
        assertNull(AudioModeIntensity.wireParamOrNull(JukeboxAudioMode.Off, 150))
        assertNull(AudioModeIntensity.wireParamOrNull(null, 150))
        assertEquals("ai=150", AudioModeIntensity.wireParamOrNull(JukeboxAudioMode.Daycore, 400))
    }

    @Test
    fun scaleRateIsExactIdentityAtDefaultIntensity() {
        // Pinned with exact equality: at 100% the preset rate must pass through
        // with no float math at all.
        assertTrue(AudioModeIntensity.scaleRate(1.2, 100) == 1.2)
        assertTrue(AudioModeIntensity.scaleRate(0.65, 100) == 0.65)
    }

    @Test
    fun scaleRateScalesAroundUnity() {
        assertEquals(1.3, AudioModeIntensity.scaleRate(1.2, 150), 1e-9)
        assertEquals(1.1, AudioModeIntensity.scaleRate(1.2, 50), 1e-9)
        assertEquals(0.7, AudioModeIntensity.scaleRate(0.8, 150), 1e-9)
        assertEquals(0.9, AudioModeIntensity.scaleRate(0.8, 50), 1e-9)
    }
}
