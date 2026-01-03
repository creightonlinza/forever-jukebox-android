package com.foreverjukebox.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foreverjukebox.app.data.ThemeMode

@Composable
fun HeaderBar(
    state: UiState,
    onEditBaseUrl: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onTabSelected: (TabId) -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        val transition = rememberInfiniteTransition(label = "neonFlicker")
        val flicker = transition.animateFloat(
            initialValue = 1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 6000
                    1f at 0
                    0.95f at 120
                    0.75f at 180
                    1f at 240
                    0.85f at 360
                    1f at 420
                    0.92f at 720
                    1f at 780
                    0.88f at 1680
                    1f at 1740
                    0.7f at 2640
                    1f at 2760
                    0.9f at 3480
                    1f at 3540
                    0.8f at 4560
                    1f at 4620
                    0.86f at 5340
                    1f at 5400
                    1f at 6000
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "flicker"
        ).value
        val glow = 0.45f + (0.55f * flicker)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "THE FOREVER JUKEBOX",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = neonFontFamily,
                    letterSpacing = 2.sp,
                    shadow = Shadow(
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = glow),
                        offset = Offset(0f, 0f),
                        blurRadius = 18f * glow
                    )
                ),
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f * flicker),
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { showSettings = true },
                modifier = Modifier.size(SmallButtonHeight)
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        TabBar(
            state = state,
            onTabSelected = onTabSelected
        )
    }

    if (showSettings) {
        SettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onThemeChange = onThemeChange,
            onEditBaseUrl = onEditBaseUrl
        )
    }
}

@Composable
private fun SettingsDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onEditBaseUrl: (String) -> Unit
) {
    var urlInput by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onEditBaseUrl(urlInput)
                    onDismiss()
                },
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Save", style = MaterialTheme.typography.labelSmall)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = pillOutlinedButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Close", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Theme")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onThemeChange(ThemeMode.System) },
                        colors = pillOutlinedButtonColors(),
                        border = pillButtonBorder(),
                        shape = PillShape,
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.height(SmallButtonHeight)
                    ) {
                        Text("System", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { onThemeChange(ThemeMode.Light) },
                        colors = pillOutlinedButtonColors(),
                        border = pillButtonBorder(),
                        shape = PillShape,
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.height(SmallButtonHeight)
                    ) {
                        Text("Light", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = { onThemeChange(ThemeMode.Dark) },
                        colors = pillOutlinedButtonColors(),
                        border = pillButtonBorder(),
                        shape = PillShape,
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.height(SmallButtonHeight)
                    ) {
                        Text("Dark", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text("API Base URL")
                androidx.compose.material3.OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    label = { Text("Example: http://10.0.2.2:8000") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    modifier = Modifier.heightIn(min = SmallFieldMinHeight)
                )
            }
        }
    )
}
