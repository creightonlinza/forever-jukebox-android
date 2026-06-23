package com.foreverjukebox.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.foreverjukebox.app.R

@Composable
fun FaqPanel() {
    var expandedSectionIndex by rememberSaveable { mutableIntStateOf(0) }
    val linkStyle = SpanStyle(
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("FAQ", style = MaterialTheme.typography.labelLarge)
            SocialLinksSection()
            FaqAccordionSection(
                title = "What the what?",
                expanded = expandedSectionIndex == 0,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(0) }
            ) {
                val whatText = buildAnnotatedString {
                    append("The Forever Jukebox is an open-source modernization of Paul Lamere's ")
                    withLink(LinkAnnotation.Url(url = "https://musicmachinery.com/2012/11/12/the-infinite-jukebox/")) {
                        withStyle(linkStyle) { append("Infinite Jukebox") }
                    }
                    append(" and ")
                    withLink(LinkAnnotation.Url(url = "https://musicmachinery.com/2014/03/18/how-the-autocanonizer-works/")) {
                        withStyle(linkStyle) { append("Autocanonizer") }
                    }
                    append(" — rebuilt from the ground up by ")
                    withLink(LinkAnnotation.Url(url = "https://creighton.dev/")) {
                        withStyle(linkStyle) { append("Creighton Linza") }
                    }
                    append(". It generates a forever-evolving version of any song.")
                }
                Text(
                    text = whatText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            FaqAccordionSection(
                title = "How does it work?",
                expanded = expandedSectionIndex == 1,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(1) }
            ) {
                Text(
                    "Audio is processed by the Forever Jukebox Analysis Engine, which approximates Spotify’s legacy Echo Nest analysis (now deprecated) by extracting beats, segments, and related features. Those features drive beat-synchronous playback in the frontend. On each beat, the player may jump to a different, sonically similar point in the track based on timbre, loudness, segment duration, and beat position. The visualizations map these potential jump paths for every beat."
                )
                val sourceText = buildAnnotatedString {
                    append("The full source code is available in the ")
                    withLink(LinkAnnotation.Url(url = "https://github.com/creightonlinza/forever-jukebox-android/")) {
                        withStyle(linkStyle) { append("forever-jukebox-android") }
                    }
                    append(" repository.")
                }
                Text(
                    text = sourceText,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
            }

            FaqAccordionSection(
                title = "How can I tune the Jukebox?",
                expanded = expandedSectionIndex == 2,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(2) }
            ) {
                BulletListItem("Click the Tune button to open the tuning panel.")
                BulletListItem("Lower the threshold for higher audio continuity; raise it for more branches.")
                BulletListItem("Adjust branch probability min/max and ramp speed to shape how often jumps happen.")
                BulletListItem("Use the checkboxes to allow or restrict certain branch types.")
            }

            FaqAccordionSection(
                title = "Why is loading so slow?",
                expanded = expandedSectionIndex == 3,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(3) }
            ) {
                BulletListItem("The track must be analyzed locally or by the server before it can play.")
                BulletListItem("The entire track must also be decoded and loaded into memory so the Jukebox can jump instantly. Longer tracks take longer, even when analysis is cached.")
                BulletListItem("For best results, keep the screen on and the app open until loading finishes. Loading may slow considerably when the device is locked or the app is in the background.")
            }

            FaqAccordionSection(
                title = "How do Playlists work?",
                expanded = expandedSectionIndex == 4,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(4) }
            ) {
                BulletListItem("Playlists let you queue up multiple tracks and move between them from the Listen screen.")
                BulletListItem("First load a track, then long-press another track to add it to the playlist. A short tap continues to swap the track in and start loading it.")
                BulletListItem("Tap the playlist icon on the Listen screen to pick, remove, or clear tracks. Previous and next controls skip through the playlist, and playlists are automatically saved.")
            }

            FaqAccordionSection(
                title = "How do Favorites work? (server mode only)",
                expanded = expandedSectionIndex == 5,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(5) }
            ) {
                BulletListItem("Favorites are saved/unsaved by clicking the star icon on a track. They are stored locally in your browser and can optionally be synced across devices using a sync code obtained from the Favorites sync menu.")
                BulletListItem("When you favorite a track, its tuning is saved too, so future loads restore your chosen parameters.")
                BulletListItem("Use Reset in the Tune panel to restore default tuning (must be re-favorited to save changes).")
            }
        }
    }
}

@Composable
private fun FaqAccordionSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun SocialLinksSection() {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SocialLinkButton(
                label = "Reddit",
                iconResId = R.drawable.ic_reddit,
                onClick = { uriHandler.openUri(REDDIT_COMMUNITY_URL) },
                modifier = Modifier.weight(1f)
            )
            SocialLinkButton(
                label = "Discord",
                iconResId = R.drawable.ic_discord,
                onClick = { uriHandler.openUri(DISCORD_SERVER_URL) },
                modifier = Modifier.weight(1f)
            )
            SocialLinkButton(
                label = "GitHub",
                iconResId = R.drawable.ic_github,
                onClick = { uriHandler.openUri(GITHUB_PROJECT_URL) },
                modifier = Modifier.weight(1f)
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun SocialLinkButton(
    label: String,
    iconResId: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = SurfaceShape,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            painter = painterResource(id = iconResId),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

private fun Int.nextAccordionIndex(index: Int): Int = if (this == index) -1 else index

@Composable
private fun BulletListItem(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("•")
        Text(
            text = text,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        )
    }
}

private const val REDDIT_COMMUNITY_URL = "https://www.reddit.com/r/infinitejukebox/"
private const val DISCORD_SERVER_URL = "https://discord.com/invite/KWN5BfD"
private const val GITHUB_PROJECT_URL = "https://github.com/creightonlinza/forever-jukebox-android/"
