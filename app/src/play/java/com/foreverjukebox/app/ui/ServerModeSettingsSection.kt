package com.foreverjukebox.app.ui

import androidx.compose.runtime.Composable
import com.foreverjukebox.app.data.AppMode

@Composable
fun ServerModeSettingsSection(
    selectedMode: AppMode,
    onModeChange: (AppMode) -> Unit,
    onOpenServerSettings: () -> Unit
) = Unit
