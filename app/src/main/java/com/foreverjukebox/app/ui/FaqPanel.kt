package com.foreverjukebox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun FaqPanel() {
    val uriHandler = LocalUriHandler.current
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
            Text("What the what?", fontWeight = FontWeight.Bold)
            Text("This app lets you search a song on Spotify, match it to YouTube audio, and generate a forever-changing version of the song.")
            Text("How does it work?", fontWeight = FontWeight.Bold)
            Text("The engine on the other side of the API base analyzes audio into beats and segments, then plays it beat by beat. At each beat there is a chance to jump to a different part of the song that sounds similar. Similarity uses features like timbre, loudness, duration, and beat position. The visualization shows the possible jump paths for each beat.")
            Text("How can I tune the Jukebox?", fontWeight = FontWeight.Bold)
            Text("Use the Tune button to open the tuning panel. Lower the threshold for higher audio continuity; raise it for more branches. Adjust branch probability min/max and ramp speed to shape how often jumps happen. Use the toggles to allow or restrict certain branch types.")
            Text("Credits", fontWeight = FontWeight.Bold)
            val creditsLine1 = buildAnnotatedString {
                append("The Forever Jukebox & Analysis Engine by ")
                pushStringAnnotation(tag = "URL", annotation = "https://creighton.dev")
                withStyle(linkStyle) {
                    append("Creighton Linza")
                }
                pop()
                append(".")
            }
            ClickableText(
                text = creditsLine1,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                onClick = { offset ->
                    val annotation = creditsLine1.getStringAnnotations("URL", offset, offset).firstOrNull()
                    annotation?.let { link -> uriHandler.openUri(link.item) }
                }
            )
            val creditsLine2 = buildAnnotatedString {
                append("Based off of ")
                pushStringAnnotation(tag = "URL", annotation = "https://musicmachinery.com/")
                withStyle(linkStyle) {
                    append("Paul Lamere")
                }
                pop()
                append("'s original Infinite Jukebox.")
            }
            ClickableText(
                text = creditsLine2,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                onClick = { offset ->
                    val annotation = creditsLine2.getStringAnnotations("URL", offset, offset).firstOrNull()
                    annotation?.let { link -> uriHandler.openUri(link.item) }
                }
            )
        }
    }
}
