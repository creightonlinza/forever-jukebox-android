# Playlist How-To

Use playlists to line up multiple Forever Jukebox tracks and move between them without leaving the Listen screen. Playlists work in Jukebox and Autocanonizer playback modes.

## Before You Start

- Load one track first. The playlist starts from the currently loaded track.
- Add at least one more track. Playlist controls appear only when the playlist has two or more tracks.
- A playlist can hold up to 10 tracks.
- In Local mode, playlist tracks must come from Cached Analysis. Open and analyze a local audio file first if the track is not cached yet.
- In Server mode, playlist tracks can come from Top Songs, Trending, Recent, Favorites, or Search.

## Start a Playlist

1. Load the first track you want to hear.
   - Local mode: open a cached track from Input > Cached Analysis, or open a new audio file and wait for analysis to finish.
   - Server mode: choose a track from Top Songs, Search, Recent, Trending, or Favorites.
2. Find the next track.
3. Long-press the track to add it to the playlist.
   - Local mode: long-press a row in Input > Cached Analysis.
   - Server mode: long-press a row in Top Songs, Trending, Recent, or Favorites.
4. A short confirmation appears when the track is added.

If you long-press the currently loaded track or a track already in the playlist, the app leaves the playlist unchanged. If the playlist already has 10 tracks, the app leaves it unchanged.

## Add More Tracks

After a playlist is active, you can add or swap tracks in two ways:

- Long-press a track in an eligible list to append it without changing the currently playing track.
- Tap a track normally to replace the current playlist slot and switch to it immediately.

Search follows the normal selection flow. When a playlist is already active, selecting a Search result replaces the current playlist slot once the app has a track to load.

If the tapped track is already elsewhere in the playlist, the app moves that existing playlist item into the current slot and removes its old occurrence. The playlist keeps only one copy of each item.

## Open and Use the Playlist

1. Go to the Listen screen.
2. Tap the Playlist button.
3. In the Playlist dialog:
   - Tap a track to load it.
   - Tap the remove button beside a track to remove it.
   - Tap Clear to remove the whole playlist.
   - Tap Close to return to Listen.

The current track is highlighted. The app does not let you remove the current track. If removing a track would leave only one track, the playlist is cleared because playlist controls require at least two tracks.

## Skip Between Tracks

Use the previous and next playlist buttons on the Listen screen or fullscreen visualization. The same previous and next actions can also appear in the Android media notification when playlist skipping is available.

Skipping loads the selected track in the current playback mode. If playback was already active, the app resumes playback after the next track finishes loading.

In Autocanonizer mode, when a track naturally reaches the end, the app advances to the next playlist track if one is available.

## Saved Playlists

The app saves your playlist automatically.

- When you reopen the app with a saved playlist, the Listen screen can show a Saved Playlist button.
- Tap Saved Playlist, then tap a track in the playlist to start it.
- Starting a different track outside the saved playlist clears the inactive saved playlist.
- Clear removes both the active playlist and the saved playlist.

Saved playlists are filtered by the current app mode:

- In Server mode, only saved server tracks are available.
- In Local mode, only saved cached local tracks are available.
- Local tracks that are no longer cached are skipped until they are analyzed and cached again.

## Notes

- Playlist controls are available in Jukebox and Autocanonizer playback modes.
- A playlist needs at least two distinct tracks.
- A playlist can hold up to 10 tracks.
- Server tracks and local cached tracks with the same ID are treated as different playlist items.
- Server playlist entries keep their custom tuning settings when those settings are available from Favorites or the current track.
