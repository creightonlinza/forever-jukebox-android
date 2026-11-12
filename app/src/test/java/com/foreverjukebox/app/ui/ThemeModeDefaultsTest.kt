package com.foreverjukebox.app.ui

import com.foreverjukebox.app.data.DefaultThemeMode
import com.foreverjukebox.app.data.ThemeMode
import com.foreverjukebox.app.data.themeModeFromStored
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeDefaultsTest {

    @Test
    fun defaultThemeIsDarkForWebParity() {
        assertEquals(ThemeMode.Dark, DefaultThemeMode)
        assertEquals(DefaultThemeMode, UiState().themeMode)
    }

    @Test
    fun missingOrUnknownStoredThemeResolvesToDefault() {
        assertEquals(DefaultThemeMode, themeModeFromStored(null))
        assertEquals(DefaultThemeMode, themeModeFromStored("Sepia"))
    }

    @Test
    fun storedThemeNamesRoundTrip() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, themeModeFromStored(mode.name))
        }
    }
}
