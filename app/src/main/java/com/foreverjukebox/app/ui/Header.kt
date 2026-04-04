package com.foreverjukebox.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.foreverjukebox.app.BuildConfig
import com.foreverjukebox.app.data.AppMode
import com.foreverjukebox.app.data.ThemeMode
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun HeaderBar(
    state: UiState,
    onEditBaseUrl: (String) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onAppModeChange: (AppMode) -> Unit,
    onRefreshCacheSize: () -> Unit,
    onClearCache: () -> Unit,
    onTabSelected: (TabId) -> Unit,
    onCastSessionStarted: () -> Unit,
    onOpenSleepTimer: () -> Unit
) {
    val context = LocalContext.current
    var showSettings by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .clip(SurfaceShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.weight(1f, fill = true),
                contentAlignment = Alignment.CenterStart
            ) {
                HeroTitle()
            }
            if (state.appMode == AppMode.Server) {
                CastRouteButton(
                    modifier = Modifier.size(SmallButtonHeight),
                    enabled = state.castEnabled,
                    onSessionStarted = onCastSessionStarted,
                    onDisabledClick = {
                        Toast.makeText(
                            context,
                            "Casting is not available for this API base URL.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            SquareIconButton(
                onClick = {
                    onRefreshCacheSize()
                    showSettings = true
                },
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
            onOpenSleepTimer = {
                showSettings = false
                onOpenSleepTimer()
            },
            onThemeChange = onThemeChange,
            onAppModeChange = onAppModeChange,
            onEditBaseUrl = onEditBaseUrl,
            onClearCache = onClearCache
        )
    }
}

@Composable
fun TitleOnlyHeaderBar() {
    Column(
        modifier = Modifier
            .clip(SurfaceShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        HeroTitle()
    }
}

@Composable
private fun HeroTitle() {
    val tokens = LocalThemeTokens.current
    val frameTransition = rememberInfiniteTransition(label = "heroTitleFrameFlicker")
    val frameFlicker = frameTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 14000
                1f at 0
                0.95f at 280
                0.75f at 420
                1f at 560
                0.85f at 840
                1f at 980
                0.92f at 1680
                1f at 1820
                0.88f at 3920
                1f at 4060
                0.7f at 6160
                1f at 6440
                0.9f at 8120
                1f at 8260
                0.8f at 10640
                1f at 10780
                0.86f at 12460
                1f at 12600
                1f at 14000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "frameFlicker"
    ).value
    val jukeboxTransition = rememberInfiniteTransition(label = "heroTitleWordFlicker")
    val jukeboxVisible = jukeboxTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 18000
                1f at 0
                1f at 4446
                0f at 4460
                0f at 4518
                1f at 4536
                1f at 11358
                0f at 11376
                0f at 11448
                1f at 11466
                1f at 15912
                0f at 15924
                0f at 15966
                1f at 15984
                1f at 18000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "jukeboxVisible"
    ).value
    val borderColor = lerp(tokens.titleAccent, Color.Black, (1f - frameFlicker) * 0.26f)
    val glowColor = tokens.titleGlow.copy(alpha = tokens.titleGlow.alpha * (0.5f + (0.5f * frameFlicker)))
    val activeTitleColor = tokens.titleAccent.copy(alpha = 0.72f + (0.28f * frameFlicker))
    val titleShadow = Shadow(
        color = glowColor.copy(alpha = glowColor.alpha * (0.7f + (0.3f * frameFlicker))),
        offset = Offset(0f, 0f),
        blurRadius = 18f * (0.75f + (0.25f * frameFlicker))
    )
    val titleText = buildAnnotatedString {
        withStyle(
            style = SpanStyle(
                color = activeTitleColor,
                shadow = titleShadow
            )
        ) {
            append("THE FOREVER ")
        }
        withStyle(
            style = SpanStyle(
                color = if (jukeboxVisible < 0.5f) tokens.background else activeTitleColor,
                shadow = if (jukeboxVisible < 0.5f) null else titleShadow
            )
        ) {
            append("JUKEBOX")
        }
    }
    Box(
        modifier = Modifier
            .shadow(
                elevation = 8.dp,
                shape = SurfaceShape,
                clip = false,
                ambientColor = glowColor,
                spotColor = glowColor
            )
            .clip(SurfaceShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 2.dp, color = borderColor, shape = SurfaceShape)
            .alpha(0.64f + (0.36f * frameFlicker))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = titleText,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = neonFontFamily,
                letterSpacing = 2.sp
            ),
            fontWeight = FontWeight.Normal
        )
    }
}

@Composable
private fun SettingsDialog(
    state: UiState,
    onDismiss: () -> Unit,
    onOpenSleepTimer: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onAppModeChange: (AppMode) -> Unit,
    onEditBaseUrl: (String) -> Unit,
    onClearCache: () -> Unit
) {
    var urlInput by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var selectedMode by remember(state.appMode) { mutableStateOf(state.appMode ?: defaultOnboardingMode) }
    val trimmedUrl = urlInput.trim()
    val requiresServerUrl = selectedMode == AppMode.Server
    val canSave = !requiresServerUrl || isValidBaseUrl(trimmedUrl)
    val cacheLabel = formatCacheSize(state.cacheSizeBytes)
    val cacheEnabled = state.cacheSizeBytes > 0
    val normalizedVersionName = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V")
    val versionLabel = "v$normalizedVersionName"
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(
                modifier = Modifier,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (selectedMode == AppMode.Server) {
                            onEditBaseUrl(trimmedUrl)
                        }
                        if (selectedMode != state.appMode) {
                            onAppModeChange(selectedMode)
                        }
                        onDismiss()
                    },
                    enabled = canSave,
                    colors = pillButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Save", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Settings")
                    Text(
                        versionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                SquareIconButton(onClick = onOpenSleepTimer) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = "Sleep timer",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("App Mode")
                AppModeSliderToggle(
                    selectedMode = selectedMode,
                    onModeChange = { mode -> selectedMode = mode },
                    modifier = Modifier.height(SmallButtonHeight)
                )
                if (selectedMode == AppMode.Server) {
                    Text("API Base URL")
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        label = { Text("Example: http://192.168.1.100") },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        shape = SurfaceShape,
                        modifier = Modifier.heightIn(min = SmallFieldMinHeight)
                    )
                }
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
                Text("Cache")
                OutlinedButton(
                    onClick = onClearCache,
                    enabled = cacheEnabled,
                    colors = pillOutlinedButtonColors(),
                    border = pillButtonBorder(),
                    shape = PillShape,
                    contentPadding = SmallButtonPadding,
                    modifier = Modifier.height(SmallButtonHeight)
                ) {
                    Text("Clear $cacheLabel", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    )
}

private fun formatCacheSize(bytes: Long): String {
    if (bytes <= 0) {
        return "0MB"
    }
    val mb = bytes / (1024.0 * 1024.0)
    val rounded = (mb * 10).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) {
        "${rounded.toInt()}MB"
    } else {
        String.format(Locale.US, "%.1fMB", rounded)
    }
}
