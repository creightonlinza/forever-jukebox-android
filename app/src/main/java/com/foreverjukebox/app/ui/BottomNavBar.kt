package com.foreverjukebox.app.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val BottomNavBarHeight = 68.dp

@Composable
fun BottomNavBar(state: UiState, onTabSelected: (TabId) -> Unit) {
    val tokens = LocalThemeTokens.current
    val tabs = tabsForMode(state.appMode)
    NavigationBar(
        containerColor = tokens.panelSurface,
        contentColor = tokens.onBackground,
        // The app column already applies navigation-bar insets.
        windowInsets = WindowInsets(0.dp),
        modifier = Modifier
            .height(BottomNavBarHeight)
            .clip(BottomBarSurfaceShape)
    ) {
        tabs.forEach { tabId ->
            NavigationBarItem(
                selected = state.activeTab == tabId,
                onClick = { onTabSelected(tabId) },
                icon = {
                    Icon(
                        imageVector = navIconForTab(tabId),
                        contentDescription = null
                    )
                },
                label = {
                    Text(
                        text = navLabelForTab(tabId),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = tokens.accent,
                    selectedTextColor = tokens.accent,
                    unselectedIconColor = tokens.muted,
                    unselectedTextColor = tokens.muted,
                    indicatorColor = tokens.controlSurface
                )
            )
        }
    }
}

internal fun navLabelForTab(tabId: TabId): String {
    return when (tabId) {
        TabId.Input, TabId.Top -> "Home"
        TabId.Search -> "Search"
        TabId.Play -> "Jukebox"
        TabId.Faq -> "FAQ"
    }
}

private fun navIconForTab(tabId: TabId): ImageVector {
    return when (tabId) {
        TabId.Input, TabId.Top -> Icons.Outlined.Home
        TabId.Search -> Icons.Outlined.Search
        TabId.Play -> Icons.Outlined.AllInclusive
        TabId.Faq -> Icons.AutoMirrored.Outlined.HelpOutline
    }
}
