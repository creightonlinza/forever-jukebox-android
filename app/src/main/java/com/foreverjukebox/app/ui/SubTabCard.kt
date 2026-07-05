package com.foreverjukebox.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Large card-style selector button used for sub-tab switching (and matching
 * one-off actions like "Add Audio"): a tinted icon tile with a label, selected
 * state highlighted with the accent border.
 */
@Composable
fun SubTabCard(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = LocalThemeTokens.current.onBackground,
    active: Boolean = false
) {
    val tokens = LocalThemeTokens.current
    val containerColor by animateColorAsState(
        targetValue = if (active) tokens.controlSurface else tokens.panelSurface,
        label = "subTabCardContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (active) tokens.accent else tokens.controlBorder,
        label = "subTabCardBorder"
    )
    Row(
        modifier = modifier
            .clip(SurfaceShape)
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = SurfaceShape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(SurfaceShape)
                .background(tokens.vizBackground),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.onBackground,
            maxLines = 1
        )
    }
}
