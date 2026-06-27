package com.foreverjukebox.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun pillButtonColors(active: Boolean = false): ButtonColors {
    val tokens = LocalThemeTokens.current
    val container = if (active) tokens.controlSurface else tokens.panelSurface
    val content = tokens.onBackground
    return ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = container.withAlpha(0.4f),
        disabledContentColor = content.withAlpha(0.4f)
    )
}

@Composable
fun pillOutlinedButtonColors(active: Boolean = false): ButtonColors {
    val tokens = LocalThemeTokens.current
    val container = if (active) tokens.controlSurface else tokens.panelSurface
    val content = tokens.onBackground
    return ButtonDefaults.outlinedButtonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = container.withAlpha(0.4f),
        disabledContentColor = content.withAlpha(0.4f)
    )
}

/**
 * Filled button styled with the hero title accent — used to draw extra attention to a
 * primary action. Mirrors the fully-highlighted Listen tab: an accent fill with an
 * inverted foreground so the label stays legible on the bright color in both themes.
 */
@Composable
fun heroButtonColors(): ButtonColors {
    val tokens = LocalThemeTokens.current
    val container = tokens.titleAccent
    val content = pulsingListenContentColor(tokens.onBackground, pulseAmount = 0f)
    return ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = content,
        disabledContainerColor = container.withAlpha(0.4f),
        disabledContentColor = content.withAlpha(0.4f)
    )
}

@Composable
fun pillButtonBorder(): BorderStroke {
    val tokens = LocalThemeTokens.current
    return BorderStroke(1.dp, tokens.controlBorder)
}

private fun Color.withAlpha(alpha: Float): Color = copy(alpha = alpha)
