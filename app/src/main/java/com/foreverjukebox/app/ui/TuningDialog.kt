package com.foreverjukebox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

internal val MIN_JUMP_DISTANCE_OPTIONS = listOf(0, 5, 10, 20, 30)

internal fun minJumpDistancePercentForIndex(index: Int): Int {
    return MIN_JUMP_DISTANCE_OPTIONS[index.coerceIn(MIN_JUMP_DISTANCE_OPTIONS.indices)]
}

internal fun minJumpDistanceIndexForPercent(percent: Int): Int {
    return MIN_JUMP_DISTANCE_OPTIONS.indexOf(percent).coerceAtLeast(0)
}

internal fun minimumJumpDistanceLabel(percent: Int): String {
    return if (percent == 0) {
        "Any distance"
    } else {
        ">$percent% of track"
    }
}

private enum class TuningDialogTab(val title: String) {
    Tuning("Tuning"),
    AudioMode("Audio Mode")
}

private val TuningDialogContentHeight = 420.dp

@Composable
@Suppress("AssignedValueIsNeverRead")
fun TuningDialog(
    initialThreshold: Int,
    initialMinProb: Int,
    initialMaxProb: Int,
    initialRamp: Int,
    initialHighlightAnchorBranch: Boolean,
    initialJustBackwards: Boolean,
    initialMinJumpDistancePercent: Int,
    initialRemoveSequential: Boolean,
    initialAudioModeWireValue: String,
    initialAudioModeIntensity: Int,
    audioModeOptions: List<AudioModeOption>,
    isAudioModePickerEnabled: Boolean,
    onDismiss: () -> Unit,
    onResetBranchTuning: () -> Unit,
    onResetAudioMode: () -> Unit,
    onApply: (
        threshold: Int,
        minProb: Double,
        maxProb: Double,
        ramp: Double,
        highlightAnchorBranch: Boolean,
        justBackwards: Boolean,
        minJumpDistancePercent: Int,
        removeSequentialBranches: Boolean,
        audioModeWireValue: String,
        audioModeIntensity: Int
    ) -> Unit
) {
    var selectedTab by remember { mutableStateOf(TuningDialogTab.Tuning) }
    var threshold by remember(initialThreshold) { mutableFloatStateOf(initialThreshold.toFloat()) }
    var probRange by remember(initialMinProb, initialMaxProb) {
        mutableStateOf(
            initialMinProb.toFloat().coerceAtMost(initialMaxProb.toFloat())..
                initialMaxProb.toFloat().coerceAtLeast(initialMinProb.toFloat())
        )
    }
    var ramp by remember(initialRamp) { mutableFloatStateOf(initialRamp.toFloat()) }
    var highlightAnchorBranch by remember(initialHighlightAnchorBranch) {
        mutableStateOf(initialHighlightAnchorBranch)
    }
    var justBackwards by remember(initialJustBackwards) { mutableStateOf(initialJustBackwards) }
    var minJumpIndex by remember(initialMinJumpDistancePercent) {
        mutableFloatStateOf(
            minJumpDistanceIndexForPercent(initialMinJumpDistancePercent).toFloat()
        )
    }
    var removeSequential by remember(initialRemoveSequential) { mutableStateOf(initialRemoveSequential) }
    var audioModeWireValue by remember(initialAudioModeWireValue) {
        mutableStateOf(initialAudioModeWireValue)
    }
    var audioModeIntensity by remember(initialAudioModeIntensity) {
        mutableFloatStateOf(AudioModeIntensity.clamp(initialAudioModeIntensity).toFloat())
    }
    val selectedMinJumpDistancePercent =
        minJumpDistancePercentForIndex(minJumpIndex.toInt())

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = SurfaceShape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = Color.Transparent
                ) {
                    TuningDialogTab.entries.forEach { tab ->
                        Tab(
                            selected = tab == selectedTab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.title, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(TuningDialogContentHeight)
                ) {
                    when (selectedTab) {
                        TuningDialogTab.Tuning -> TuningTabContent(
                            threshold = threshold,
                            onThresholdChange = { threshold = it },
                            minJumpIndex = minJumpIndex,
                            onMinJumpIndexChange = { minJumpIndex = it },
                            selectedMinJumpDistancePercent = selectedMinJumpDistancePercent,
                            probRange = probRange,
                            onProbRangeChange = { probRange = it },
                            ramp = ramp,
                            onRampChange = { ramp = it },
                            justBackwards = justBackwards,
                            onJustBackwardsChange = { justBackwards = it },
                            removeSequential = removeSequential,
                            onRemoveSequentialChange = { removeSequential = it },
                            highlightAnchorBranch = highlightAnchorBranch,
                            onHighlightAnchorBranchChange = { highlightAnchorBranch = it }
                        )
                        TuningDialogTab.AudioMode -> AudioModeTabContent(
                            audioModeOptions = audioModeOptions,
                            isPickerEnabled = isAudioModePickerEnabled,
                            selectedWireValue = audioModeWireValue,
                            onSelectWireValue = { audioModeWireValue = it },
                            intensity = audioModeIntensity,
                            onIntensityChange = { audioModeIntensity = it }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                when (selectedTab) {
                                    TuningDialogTab.Tuning -> onResetBranchTuning()
                                    TuningDialogTab.AudioMode -> onResetAudioMode()
                                }
                                onDismiss()
                            },
                            colors = pillOutlinedButtonColors(),
                            border = pillButtonBorder(),
                            shape = PillShape,
                            contentPadding = SmallButtonPadding,
                            modifier = Modifier.height(SmallButtonHeight)
                        ) {
                            Text("Reset", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Row(
                        modifier = Modifier.weight(2f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                        Button(
                            onClick = {
                                onApply(
                                    threshold.toInt(),
                                    probRange.start / 100.0,
                                    probRange.endInclusive / 100.0,
                                    ramp / 500.0,
                                    highlightAnchorBranch,
                                    justBackwards,
                                    selectedMinJumpDistancePercent,
                                    removeSequential,
                                    audioModeWireValue,
                                    audioModeIntensity.toInt()
                                )
                                onDismiss()
                            },
                            colors = pillButtonColors(),
                            border = pillButtonBorder(),
                            shape = PillShape,
                            contentPadding = SmallButtonPadding,
                            modifier = Modifier.height(SmallButtonHeight)
                        ) {
                            Text("Apply", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TuningTabContent(
    threshold: Float,
    onThresholdChange: (Float) -> Unit,
    minJumpIndex: Float,
    onMinJumpIndexChange: (Float) -> Unit,
    selectedMinJumpDistancePercent: Int,
    probRange: ClosedFloatingPointRange<Float>,
    onProbRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    ramp: Float,
    onRampChange: (Float) -> Unit,
    justBackwards: Boolean,
    onJustBackwardsChange: (Boolean) -> Unit,
    removeSequential: Boolean,
    onRemoveSequentialChange: (Boolean) -> Unit,
    highlightAnchorBranch: Boolean,
    onHighlightAnchorBranchChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SliderLabelRow("Branch Similarity Threshold", "${threshold.toInt()}")
        Slider(
            value = threshold,
            onValueChange = onThresholdChange,
            valueRange = 2f..80f,
            steps = 77
        )
        SliderLabelRow(
            "Min Jump Distance",
            minimumJumpDistanceLabel(selectedMinJumpDistancePercent)
        )
        Slider(
            value = minJumpIndex,
            onValueChange = onMinJumpIndexChange,
            valueRange = 0f..4f,
            steps = 3
        )
        SliderLabelRow(
            "Branch Probability",
            "${probRange.start.toInt()}–${probRange.endInclusive.toInt()}%"
        )
        RangeSlider(
            value = probRange,
            onValueChange = onProbRangeChange,
            valueRange = 0f..100f,
            steps = 49
        )
        SliderLabelRow("Branch Ramp Speed", "${ramp.toInt()}%")
        Slider(
            value = ramp,
            onValueChange = onRampChange,
            valueRange = 0f..100f,
            steps = 49
        )
        LabeledSwitchRow(
            label = "Allow only reverse branches",
            checked = justBackwards,
            onCheckedChange = onJustBackwardsChange
        )
        LabeledSwitchRow(
            label = "Remove sequential branches",
            checked = removeSequential,
            onCheckedChange = onRemoveSequentialChange
        )
        LabeledSwitchRow(
            label = "Highlight forced anchor jump",
            checked = highlightAnchorBranch,
            onCheckedChange = onHighlightAnchorBranchChange
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioModeTabContent(
    audioModeOptions: List<AudioModeOption>,
    isPickerEnabled: Boolean,
    selectedWireValue: String,
    onSelectWireValue: (String) -> Unit,
    intensity: Float,
    onIntensityChange: (Float) -> Unit
) {
    // When casting to a receiver with no supported modes there is nothing to list,
    // so the local options render as the disabled picker.
    val chipOptions = audioModeOptions.ifEmpty { localAudioModeOptions }
    val selectedSupportsIntensity =
        JukeboxAudioMode.fromWireValue(selectedWireValue)?.supportsIntensity == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chipOptions.forEach { option ->
                FilterChip(
                    selected = option.wireValue == selectedWireValue,
                    onClick = { onSelectWireValue(option.wireValue) },
                    enabled = isPickerEnabled,
                    label = { Text(option.label, style = MaterialTheme.typography.labelSmall) },
                    shape = PillShape
                )
            }
        }
        if (!isPickerEnabled) {
            Text(
                "Not supported by this receiver",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (selectedSupportsIntensity) {
            SliderLabelRow("Audio Mode Intensity", "${intensity.toInt()}%")
            Slider(
                value = intensity,
                onValueChange = onIntensityChange,
                valueRange = AudioModeIntensity.MIN.toFloat()..AudioModeIntensity.MAX.toFloat(),
                steps = AudioModeIntensity.MAX - AudioModeIntensity.MIN - 1
            )
        }
    }
}

@Composable
private fun SliderLabelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Weighting the label lets long values ("Any distance") keep a single line.
        Text(label, modifier = Modifier.weight(1f))
        Text(value, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun LabeledSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
