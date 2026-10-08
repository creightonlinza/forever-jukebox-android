package com.foreverjukebox.app.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * Where a favorite's audio came from, as the web app spells it on the wire. Values this build does
 * not know are kept verbatim so a sync round trip never rewrites them; compare against the
 * constants rather than enumerating.
 */
@Serializable
@JvmInline
value class FavoriteSourceType(val wireName: String) {
    companion object {
        val Youtube = FavoriteSourceType("youtube")
        val SoundCloud = FavoriteSourceType("soundcloud")
        val Bandcamp = FavoriteSourceType("bandcamp")
        val Upload = FavoriteSourceType("upload")
    }
}

fun favoriteSourceTypeFromProvider(raw: String?): FavoriteSourceType? = when (sourceProviderFromRaw(raw)) {
    "upload" -> FavoriteSourceType.Upload
    "youtube" -> FavoriteSourceType.Youtube
    "soundcloud" -> FavoriteSourceType.SoundCloud
    "bandcamp" -> FavoriteSourceType.Bandcamp
    else -> null
}

/**
 * Play mode a favorite or playlist entry was saved in, as spelled on the wire. A mode this build
 * does not know is kept verbatim (and plays as jukebox, see toPlaybackMode) so syncing from a
 * newer web or Android version never downgrades it on the server.
 */
@Serializable
@JvmInline
value class FavoritePlayMode(val wireName: String) {
    companion object {
        val Jukebox = FavoritePlayMode("jukebox")
        val Autocanonizer = FavoritePlayMode("autocanonizer")
        val WubMachine = FavoritePlayMode("wubmachine")
    }
}

@Serializable
data class FavoriteTrack(
    val uniqueSongId: String,
    val title: String,
    val artist: String,
    val duration: Double? = null,
    val sourceType: FavoriteSourceType? = null,
    val tuningParams: String? = null,
    // Play mode the track was favorited in; absent/null on legacy favorites,
    // which predate autocanonizer favorites and are treated as jukebox.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val playMode: FavoritePlayMode? = null
)
