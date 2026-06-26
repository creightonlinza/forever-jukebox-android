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
import androidx.compose.ui.text.AnnotatedString
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
                    withLink(LinkAnnotation.Url(url = INFINITE_JUKEBOX_URL)) {
                        withStyle(linkStyle) { append("Infinite Jukebox") }
                    }
                    append(" and ")
                    withLink(LinkAnnotation.Url(url = AUTOCANONIZER_URL)) {
                        withStyle(linkStyle) { append("Autocanonizer") }
                    }
                    append(" — rebuilt from the ground up by ")
                    withLink(LinkAnnotation.Url(url = CREIGHTON_URL)) {
                        withStyle(linkStyle) { append("Creighton Linza") }
                    }
                    append(
                        ". This build focuses on local, on-device playback and generates a " +
                            "forever-evolving version of compatible audio files on your device."
                    )
                }
                FaqText(whatText)
            }

            FaqAccordionSection(
                title = "How does it work?",
                expanded = expandedSectionIndex == 1,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(1) }
            ) {
                FaqText(
                    "Audio is processed on this device by the Forever Jukebox Analysis Engine, " +
                        "which approximates Spotify’s legacy Echo Nest analysis (now deprecated) " +
                        "by extracting beats, segments, and related features. Those features drive " +
                        "beat-aligned playback. On each beat, the player may jump to a " +
                        "different, sonically similar point in the track based on timbre, " +
                        "loudness, segment duration, and beat position. The visualizations map " +
                        "these potential jump paths for every beat."
                )
                val sourceText = buildAnnotatedString {
                    append("The source code is available in the ")
                    withLink(LinkAnnotation.Url(url = GITHUB_PROJECT_URL)) {
                        withStyle(linkStyle) { append("forever-jukebox-android") }
                    }
                    append(" repository.")
                }
                FaqText(sourceText)
            }

            FaqAccordionSection(
                title = "How can I tune the Jukebox?",
                expanded = expandedSectionIndex == 2,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(2) }
            ) {
                BulletListItem("Click the Tune button to open the tuning panel.")
                BulletListItem("Lower the threshold for higher audio continuity; raise it for more branches.")
                BulletListItem(
                    "Adjust branch probability min/max and ramp speed to shape how often jumps happen."
                )
                BulletListItem("Use the checkboxes to allow or restrict certain branch types.")
            }

            FaqAccordionSection(
                title = "Why is loading so slow?",
                expanded = expandedSectionIndex == 3,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(3) }
            ) {
                BulletListItem("The track must be analyzed locally on this device before it can play.")
                BulletListItem(
                    "The entire track must also be decoded and loaded into memory so the Jukebox " +
                        "can jump instantly. Longer tracks take longer, even when analysis is cached."
                )
                BulletListItem(
                    "For best results, keep the screen on and the app open until loading finishes. " +
                        "Loading may slow considerably when the device is locked or the app is in " +
                        "the background."
                )
            }

            FaqAccordionSection(
                title = "How do Playlists work?",
                expanded = expandedSectionIndex == 4,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(4) }
            ) {
                BulletListItem("Playlists let you queue up multiple tracks and move between them from the Listen screen.")
                BulletListItem(
                    "First load a track, then long-press another cached track to add it to the " +
                        "playlist. A short tap continues to swap the track in and start loading it."
                )
                BulletListItem(
                    "Tap the playlist icon on the Listen screen to pick, remove, or clear tracks. " +
                        "Previous and next controls skip through the playlist, and playlists are " +
                        "automatically saved."
                )
            }

            FaqAccordionSection(
                title = "How do Favorites work?",
                expanded = expandedSectionIndex == 5,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(5) }
            ) {
                BulletListItem(
                    "Favorites are saved/unsaved by clicking the star icon on a track and stored " +
                        "on this device."
                )
                BulletListItem(
                    "When you favorite a track, its tuning is saved too, so future loads restore " +
                        "your chosen parameters."
                )
                BulletListItem(
                    "Use Reset in the Tune panel to restore default tuning (must be re-favorited " +
                        "to save changes)."
                )
            }

            FaqAccordionSection(
                title = "Open source & licenses",
                expanded = expandedSectionIndex == 6,
                onToggle = { expandedSectionIndex = expandedSectionIndex.nextAccordionIndex(6) }
            ) {
                val licenseIntro = buildAnnotatedString {
                    append("Forever Jukebox is free, open-source software licensed under the ")
                    withLink(LinkAnnotation.Url(url = AGPL_LICENSE_URL)) {
                        withStyle(linkStyle) { append("GNU AGPL v3.0") }
                    }
                    append(". The complete source code is available on ")
                    withLink(LinkAnnotation.Url(url = GITHUB_PROJECT_URL)) {
                        withStyle(linkStyle) { append("GitHub") }
                    }
                    append(".")
                }
                FaqText(licenseIntro)
                FaqText("This app uses the following third-party components:")
                BulletListItem("Essentia — on-device audio analysis (AGPLv3).")
                BulletListItem(
                    "madmom-beats-port — beat and downbeat detection " +
                        "(code BSD 2-Clause; bundled models CC BY-NC-SA 4.0, " +
                        "non-commercial — this app is non-commercial, with no ads or purchases)."
                )
                BulletListItem("SpeexDSP — audio resampling (BSD 3-Clause).")
                BulletListItem(
                    "Oboe, AndroidX/Jetpack Compose, OkHttp, Coil, kotlinx, and Google Cast " +
                        "(Apache-2.0); Sentry crash reporting (MIT)."
                )
                val noticeText = buildAnnotatedString {
                    append("Full attributions and license texts are in ")
                    withLink(LinkAnnotation.Url(url = THIRD_PARTY_LICENSES_URL)) {
                        withStyle(linkStyle) { append("THIRD_PARTY_LICENSES.md") }
                    }
                    append(".")
                }
                FaqText(noticeText)
            }
        }
    }
}

@Composable
private fun FaqText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun FaqText(text: AnnotatedString) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        )
    )
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
                onClick = { uriHandler.openUri(DISCORD_COMMUNITY_URL) },
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

private const val INFINITE_JUKEBOX_URL =
    "https://musicmachinery.com/2012/11/12/the-infinite-jukebox/"
private const val AUTOCANONIZER_URL =
    "https://musicmachinery.com/2014/03/18/how-the-autocanonizer-works/"
private const val CREIGHTON_URL = "https://creighton.dev/"
private const val REDDIT_COMMUNITY_URL = "https://www.reddit.com/r/infinitejukebox/"
private const val DISCORD_COMMUNITY_URL = "https://discord.com/invite/KWN5BfD"
private const val GITHUB_PROJECT_URL = "https://github.com/creightonlinza/forever-jukebox-android/"
private const val AGPL_LICENSE_URL =
    "https://github.com/creightonlinza/forever-jukebox-android/blob/main/LICENSE"
private const val THIRD_PARTY_LICENSES_URL =
    "https://github.com/creightonlinza/forever-jukebox-android/blob/main/THIRD_PARTY_LICENSES.md"
