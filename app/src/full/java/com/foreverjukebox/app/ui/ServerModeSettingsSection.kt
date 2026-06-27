package com.foreverjukebox.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.data.AppMode

@Composable
fun ServerModeSettingsSection(
    selectedMode: AppMode,
    onModeChange: (AppMode) -> Unit,
    onOpenServerSettings: () -> Unit
) {
    Text("App Mode")
    AppModeSliderToggle(
        selectedMode = selectedMode,
        onModeChange = onModeChange,
        modifier = Modifier.height(SmallButtonHeight)
    )
    if (selectedMode == AppMode.Server) {
        OutlinedButton(
            onClick = onOpenServerSettings,
            colors = pillOutlinedButtonColors(),
            border = pillButtonBorder(),
            shape = PillShape,
            contentPadding = SmallButtonPadding,
            modifier = Modifier.height(SmallButtonHeight)
        ) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Server Settings", style = MaterialTheme.typography.labelSmall)
        }
    }
}
