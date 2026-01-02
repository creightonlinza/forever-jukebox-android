package com.foreverjukebox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TabBar(state: UiState, onTabSelected: (TabId) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TabButton(
            text = "Top Songs",
            active = state.activeTab == TabId.Top,
            onClick = { onTabSelected(TabId.Top) }
        )
        TabButton(
            text = "Search",
            active = state.activeTab == TabId.Search,
            onClick = { onTabSelected(TabId.Search) }
        )
        TabButton(
            text = "Listen",
            active = state.activeTab == TabId.Play,
            enabled = state.playback.lastYouTubeId != null,
            onClick = { onTabSelected(TabId.Play) }
        )
        TabButton(
            text = "FAQ",
            active = state.activeTab == TabId.Faq,
            onClick = { onTabSelected(TabId.Faq) }
        )
    }
}

@Composable
private fun TabButton(text: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = if (active) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.outlinedButtonColors()
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        contentPadding = SmallButtonPadding,
        modifier = Modifier.height(SmallButtonHeight)
    ) {
        Text(text)
        Spacer(modifier = Modifier.width(2.dp))
    }
}
