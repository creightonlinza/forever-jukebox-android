package com.foreverjukebox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Configures and tracks an audio export. Mirrors the web app's export dialog:
 * output duration in seconds (5 s to 2 h) defaulting to the track's length,
 * with the current tuning, deleted branches, and audio mode baked in.
 */
@Composable
fun ExportDialog(
    export: ExportUiState,
    trackDurationSeconds: Double?,
    audioMode: JukeboxAudioMode,
    audioModeIntensity: Int,
    onStart: (Int) -> Unit,
    onCancelExport: () -> Unit,
    onDismiss: () -> Unit
) {
    var durationText by remember {
        mutableStateOf(defaultExportDurationSeconds(trackDurationSeconds).toString())
    }
    val parsedSeconds = durationText.trim().toIntOrNull()
    val isDurationValid = parsedSeconds != null &&
        parsedSeconds in EXPORT_MIN_DURATION_SECONDS..EXPORT_MAX_DURATION_SECONDS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Jukebox Audio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (export.isExporting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            "Exporting audio (${export.progressPercent}%)",
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        "The export keeps running if you close this dialog.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it },
                        label = { Text("Export duration (seconds)") },
                        supportingText = {
                            Text(
                                if (isDurationValid && parsedSeconds != null) {
                                    formatDuration(parsedSeconds.toDouble())
                                } else {
                                    "Enter $EXPORT_MIN_DURATION_SECONDS to " +
                                        "$EXPORT_MAX_DURATION_SECONDS seconds"
                                }
                            )
                        },
                        isError = !isDurationValid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        exportContentsSummary(audioMode, audioModeIntensity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Saves an M4A file to your Music folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (export.isExporting) {
                Button(
                    onClick = onCancelExport,
                    colors = pillButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Cancel Export", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Button(
                    onClick = { parsedSeconds?.let(onStart) },
                    enabled = isDurationValid,
                    colors = pillButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Export Audio", style = MaterialTheme.typography.labelSmall)
                }
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
                Text("Close", style = MaterialTheme.typography.labelSmall)
            }
        }
    )
}

internal fun exportContentsSummary(audioMode: JukeboxAudioMode, intensity: Int): String {
    val base = "Exports using current tuning and deleted branches."
    if (audioMode == JukeboxAudioMode.Off) return base
    val modeText = if (audioMode.supportsIntensity && intensity != AudioModeIntensity.DEFAULT) {
        "${audioMode.label} (intensity $intensity)"
    } else {
        audioMode.label
    }
    return "$base Audio mode: $modeText."
}
