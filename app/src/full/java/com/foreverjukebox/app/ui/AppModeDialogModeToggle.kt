package com.foreverjukebox.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.foreverjukebox.app.data.AppMode

@Composable
fun AppModeDialogModeToggle(
    selectedMode: AppMode,
    onModeChange: (AppMode) -> Unit,
    modifier: Modifier = Modifier
) {
    AppModeSliderToggle(
        selectedMode = selectedMode,
        onModeChange = onModeChange,
        modifier = modifier
    )
}
