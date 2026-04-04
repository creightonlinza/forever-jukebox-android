package com.foreverjukebox.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenPulsePolicyTest {

    @Test
    fun pulsingListenContainerColorUsesCssMapping() {
        val titleAccent = Color(0xFFB06A1F)
        val onBackground = Color(0xFF2D2113)

        val start = pulsingListenContainerColor(
            titleAccent = titleAccent,
            onBackground = onBackground,
            pulseAmount = 0f
        )
        val end = pulsingListenContainerColor(
            titleAccent = titleAccent,
            onBackground = onBackground,
            pulseAmount = 1f
        )

        assertEquals(titleAccent, start)
        assertEquals(onBackground.copy(alpha = 0.12f), end)
    }

    @Test
    fun pulseAmountIsClampedToValidRange() {
        val titleAccent = Color(0xFFB06A1F)
        val onBackground = Color(0xFFE7E4DD)

        val below = pulsingListenContainerColor(titleAccent, onBackground, -1f)
        val above = pulsingListenContainerColor(titleAccent, onBackground, 2f)

        assertEquals(titleAccent, below)
        assertEquals(onBackground.copy(alpha = 0.12f), above)
    }

    @Test
    fun pulsingListenContentColorUsesInverseAtPulseStartAndTextAtPulseEnd() {
        val onBackground = Color(0xFF2D2113)
        val inverse = Color(
            red = 1f - onBackground.red,
            green = 1f - onBackground.green,
            blue = 1f - onBackground.blue,
            alpha = onBackground.alpha
        )

        val start = pulsingListenContentColor(onBackground, 0f)
        val mid = pulsingListenContentColor(onBackground, 0.5f)
        val end = pulsingListenContentColor(onBackground, 1f)

        assertEquals(inverse, start)
        assertEquals(lerp(inverse, onBackground, 0.5f), mid)
        assertEquals(onBackground, end)
    }
}
