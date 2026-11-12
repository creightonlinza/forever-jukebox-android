package com.foreverjukebox.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.BuildConfig
import com.foreverjukebox.app.ui.PillShape
import com.foreverjukebox.app.ui.SmallButtonHeight
import com.foreverjukebox.app.ui.SmallButtonPadding
import com.foreverjukebox.app.ui.pillButtonBorder
import com.foreverjukebox.app.ui.pillOutlinedButtonColors

/** Debug builds only: forgets the onboarding record, so every tour shows again. */
@Composable
fun OnboardingDebugSettingsSection() {
    if (!BuildConfig.DEBUG) return
    val controller = LocalOnboardingController.current ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Onboarding tour (debug)")
        OutlinedButton(
            onClick = controller::replay,
            colors = pillOutlinedButtonColors(),
            border = pillButtonBorder(),
            shape = PillShape,
            contentPadding = SmallButtonPadding,
            modifier = Modifier.height(SmallButtonHeight)
        ) {
            Text("Reset onboarding", style = MaterialTheme.typography.labelSmall)
        }
    }
}
