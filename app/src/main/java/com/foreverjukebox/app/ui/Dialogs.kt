package com.foreverjukebox.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.data.AppMode

internal data class SleepTimerDialogSelectionState(
    val appliedOption: SleepTimerOption,
    val pendingOption: SleepTimerOption
)

internal sealed interface SleepTimerDialogAction {
    data class SelectOption(val option: SleepTimerOption) : SleepTimerDialogAction
    data object Set : SleepTimerDialogAction
}

internal fun reduceSleepTimerDialogSelection(
    state: SleepTimerDialogSelectionState,
    action: SleepTimerDialogAction
): SleepTimerDialogSelectionState {
    return when (action) {
        is SleepTimerDialogAction.SelectOption -> state.copy(pendingOption = action.option)
        SleepTimerDialogAction.Set -> state.copy(appliedOption = state.pendingOption)
    }
}

@Composable
fun SleepTimerDialog(
    selectedOption: SleepTimerOption,
    remainingMs: Long,
    onDismiss: () -> Unit,
    onSelectOption: (SleepTimerOption) -> Unit
) {
    var showOptions by remember { mutableStateOf(false) }
    var selectionState by remember(selectedOption) {
        mutableStateOf(
            SleepTimerDialogSelectionState(
                appliedOption = selectedOption,
                pendingOption = selectedOption
            )
        )
    }
    val hasActiveTimer = remainingMs > 0L
    val countdownText = if (hasActiveTimer) {
        formatDuration(remainingMs.toDouble() / 1000.0)
    } else {
        "Off"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val next = reduceSleepTimerDialogSelection(
                        state = selectionState,
                        action = SleepTimerDialogAction.Set
                    )
                    selectionState = next
                    onSelectOption(next.appliedOption)
                    onDismiss()
                },
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Set", style = MaterialTheme.typography.labelSmall)
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
        title = { Text("Sleep Timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (hasActiveTimer) {
                        "Current countdown: $countdownText"
                    } else {
                        "Current countdown: Off"
                    }
                )
                Text("Select timer length")
                Box {
                    OutlinedButton(
                        onClick = { showOptions = true },
                        colors = pillOutlinedButtonColors(),
                        border = pillButtonBorder(),
                        shape = PillShape,
                        contentPadding = SmallButtonPadding,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(selectionState.pendingOption.label)
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showOptions,
                        onDismissRequest = { showOptions = false }
                    ) {
                        SleepTimerOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    showOptions = false
                                    selectionState = reduceSleepTimerDialogSelection(
                                        state = selectionState,
                                        action = SleepTimerDialogAction.SelectOption(option)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun VersionUpdateDialog(
    latestVersion: String,
    onDownload: () -> Unit,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(
                onClick = onDownload,
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Download from GitHub", style = MaterialTheme.typography.labelSmall)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onClose,
                colors = pillOutlinedButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Close", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("Update Available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New version found: $latestVersion")
            }
        }
    )
}

@Composable
fun WhatsNewDialog(
    prompt: WhatsNewPrompt,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(
                onClick = onClose,
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("OK", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text(prompt.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                prompt.bullets.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("-", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            item,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun ErrorMessageDialog(
    message: String,
    onClose: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(
                onClick = onClose,
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("OK", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("Error") },
        text = { Text(message) }
    )
}

@Composable
fun DeleteTrackDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val dangerColor = LocalThemeTokens.current.danger
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = pillButtonColors(),
                border = BorderStroke(1.dp, dangerColor),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text(
                    "Delete",
                    color = dangerColor,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Cancel", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("Delete track?") }
    )
}

@Composable
fun AppModeDialog(
    initialMode: AppMode = AppMode.Local,
    initialValue: String = "",
    onConfirm: (AppMode, String) -> Unit
) {
    var selectedMode by remember(initialMode) { mutableStateOf(initialMode) }
    var urlInput by remember(initialValue) { mutableStateOf(initialValue) }
    val trimmedUrl = urlInput.trim()
    val requiresServerUrl = com.foreverjukebox.app.BuildConfig.SERVER_MODE_AVAILABLE &&
        selectedMode == AppMode.Server
    val isValidServerUrl = isValidBaseUrl(trimmedUrl)
    val canConfirm = !requiresServerUrl || isValidServerUrl
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedMode, trimmedUrl) },
                enabled = canConfirm,
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("OK", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("App Mode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose how this app connects.")
                AppModeDialogModeToggle(
                    selectedMode = selectedMode,
                    onModeChange = { selectedMode = it },
                    modifier = Modifier.height(SmallButtonHeight)
                )
                if (requiresServerUrl) {
                    Text("API Base URL")
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Example: http://192.168.1.100") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        isError = trimmedUrl.isNotEmpty() && !isValidServerUrl,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        shape = SurfaceShape,
                        modifier = Modifier.heightIn(min = SmallFieldMinHeight)
                    )
                    if (trimmedUrl.isNotEmpty() && !isValidServerUrl) {
                        Text(
                            "Enter a valid http(s) URL.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    )
}

@Composable
fun TrackInfoDialog(
    durationSeconds: Double?,
    totalBeats: Int,
    totalBranches: Int,
    onClose: () -> Unit
) {
    val durationText = durationSeconds?.let { formatDuration(it) } ?: "00:00:00"
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(
                onClick = onClose,
                colors = pillButtonColors(),
                border = pillButtonBorder(),
                shape = PillShape,
                contentPadding = SmallButtonPadding,
                modifier = Modifier.height(SmallButtonHeight)
            ) {
                Text("Close", style = MaterialTheme.typography.labelSmall)
            }
        },
        title = { Text("Track Info") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Track Length: $durationText")
                Text("Total Beats: $totalBeats")
                Text("Total Branches: $totalBranches")
            }
        }
    )
}
