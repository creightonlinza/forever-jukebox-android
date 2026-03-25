# Forever Jukebox Android

Native Android port (Jetpack Compose) for 1:1 feature parity against the Forever Jukebox web UI.

## Download

- [GitHub Releases](https://github.com/creightonlinza/forever-jukebox-android/releases/latest)

## Signature (SHA-256)

```bash
B5:30:EB:FD:C1:7E:C2:D0:1A:2E:9A:9D:D9:DD:02:CA:5D:2F:E0:7A:E2:C6:E5:F8:45:E7:FF:41:FD:78:B4:4D
```

## Features

- Native engine + visualization (ported from the TypeScript web engine).
- Local mode: on-device analysis from audio files, with local caching for faster reloads.
- Server mode: Music discovery plus Top/Trending/Recent/Favorites flows from API.
- Visualization layouts, fullscreen, and tuning controls.
- Theme toggle (system/light/dark).
- PCM AudioTrack playback for beat-accurate jumping.

## Running Locally

1. Open this repository root in Android Studio.
2. Build and install a debug APK:

```bash
./gradlew assembleDebug
```

3. On first launch, choose Local or Server mode.
4. If you choose Server mode, ensure your API/worker are running and set the API base URL (for example `http://10.0.2.2:8000` on the emulator).

## Modes

### Local mode

- No backend is required.
- Use the **Input** tab to pick an audio file from the device.
- Analysis runs fully on-device, then playback uses the native engine/visualization.
- Results are cached in app cache storage and can be cleared from Settings.
- Devices with less than 4 GB RAM may fail on longer tracks.

### Server mode

- Requires a running [backend API + worker](https://github.com/creightonlinza/forever-jukebox).
- Requires a valid base URL (`http://` or `https://` with a host).
- Server mode unlocks the **Top Songs** and **Search** tabs, plus server-backed favorites sync and cast workflows.
- You can switch modes later from Settings.

## Local Mode Native Dependencies

Local mode feature extraction requires native analysis libraries:

- `madmom_beats_port_ffi` is fetched automatically during Gradle `preBuild`.
- Essentia must be available for `local_analysis_jni`.

If you see an error about Essentia not being linked into `local_analysis_jni`,
fetch prebuilt Android artifacts:

```bash
./third_party/essentia/fetch_prebuilt_from_rn_essentia_static.sh
```

This populates:

- `third_party/essentia/prebuilt/active/<abi>/libessentia.a`
- `third_party/essentia/prebuilt/active/include/essentia/...`

Optional advanced path: build Essentia from upstream source using
`./third_party/essentia/build_android_from_upstream.sh` (requires
`pkg-config` + `eigen3` development headers).
