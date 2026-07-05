package com.foreverjukebox.app.ui

import androidx.compose.ui.graphics.Color
import com.foreverjukebox.app.autocanonizer.AUTOCANONIZER_MAIN_COLOR_HEX
import com.foreverjukebox.app.autocanonizer.AUTOCANONIZER_OTHER_COLOR_HEX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutocanonizerCursorUiPolicyTest {
    @Test
    fun cursorTimesShowOnlyForLocalAutocanonizerPlayback() {
        assertTrue(
            shouldShowAutocanonizerCursorTimes(
                PlaybackState(playMode = PlaybackMode.Autocanonizer)
            )
        )
        assertFalse(
            shouldShowAutocanonizerCursorTimes(
                PlaybackState(playMode = PlaybackMode.Jukebox)
            )
        )
        assertFalse(
            shouldShowAutocanonizerCursorTimes(
                PlaybackState(
                    playMode = PlaybackMode.Autocanonizer,
                    isCasting = true
                )
            )
        )
    }

    @Test
    fun cursorAndVisualizationColorsMatchWebPalette() {
        assertEquals("#4F8FFF", AUTOCANONIZER_MAIN_COLOR_HEX)
        assertEquals("#10DF00", AUTOCANONIZER_OTHER_COLOR_HEX)
        // Default theme tokens carry the same web palette in both themes.
        assertEquals(Color(0xFF4F8FFF), themeTokens(isDark = true).canonMain)
        assertEquals(Color(0xFF10DF00), themeTokens(isDark = true).canonOther)
        assertEquals(Color(0xFF4F8FFF), themeTokens(isDark = false).canonMain)
        assertEquals(Color(0xFF10DF00), themeTokens(isDark = false).canonOther)
    }
}
