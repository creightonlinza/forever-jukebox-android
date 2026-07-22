# Forever Jukebox Android

Native Android port (Jetpack Compose) for 1:1 feature parity against the Forever Jukebox web UI.

## Download

- **[FJ Local on Google Play](https://play.google.com/store/apps/details?id=com.foreverjukebox.app.play)** —
  the official Play Store app. Local mode only: fully on-device analysis and
  playback, no backend needed.
- **[Full release on GitHub](https://github.com/creightonlinza/forever-jukebox-android/releases/latest)** —
  APK with everything in FJ Local plus Server mode (Top Songs, Search, favorites
  sync) for use with a self-hosted [backend](https://github.com/creightonlinza/forever-jukebox).

The two builds use different application IDs, so both can be installed side by side.

### Full release APK signature (SHA-256)

Verify downloaded APKs against this signing certificate fingerprint
(applies to GitHub releases; the Play Store app is signed via Play App Signing):

```text
B5:30:EB:FD:C1:7E:C2:D0:1A:2E:9A:9D:D9:DD:02:CA:5D:2F:E0:7A:E2:C6:E5:F8:45:E7:FF:41:FD:78:B4:4D
```

## Features

- Native engine + visualization (ported from the TypeScript web engine).
- Local mode: on-device analysis from audio files, with local caching for faster reloads.
- Server mode: Music discovery plus Top/Trending/Recent/Favorites flows from API.
- Visualization layouts, fullscreen, and tuning controls.
- Autocanonizer playback mode.
- Chromecast support (both modes) via the relay receiver.
- Theme toggle (system/light/dark).
- Low-latency native PCM playback (Oboe) for beat-accurate jumping.

## Modes

### Local mode

- No backend is required.
- Use the **Input** tab to pick an audio file from the device.
- Analysis runs fully on-device, then playback uses the native engine/visualization.
- Results are cached in app cache storage and can be cleared from Settings.
- Very long tracks that exceed the app's memory budget are rejected with a
  clear error before analysis starts (the limit scales with the device's
  per-app heap).

### Server mode

- Requires a running [backend API + worker](https://github.com/creightonlinza/forever-jukebox).
- Requires a valid base URL (`http://` or `https://` with a host).
- Server mode unlocks the **Top Songs** and **Search** tabs, plus server-backed favorites sync.
- You can switch modes later from Settings.

## Running Locally

1. Open this repository root in Android Studio.
2. Build and install a debug APK of the `full` flavor (the `play` flavor is the
   local-only Play Store build):

   ```bash
   ./gradlew installFullDebug
   ```

3. On first launch, choose Local or Server mode.
4. If you choose Server mode, ensure your API/worker are running and set the API base URL (for example `http://10.0.2.2:8000` on the emulator).

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

## License

Forever Jukebox Android is free, open-source software licensed under the
**GNU Affero General Public License v3.0** — see [`LICENSE`](LICENSE).

The whole app is AGPLv3 because it statically links
[Essentia](https://essentia.upf.edu/) (AGPLv3) for on-device audio analysis,
which makes the combined work a derivative covered by AGPLv3. The complete
corresponding source is this repository:
<https://github.com/creightonlinza/forever-jukebox-android>.

Copyright © Creighton Linza and contributors.

Third-party components and their licenses (Essentia, madmom-beats-port/madmom
models, SpeexDSP, Oboe, and others) are documented in
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md). The
madmom-beats-port runtime code is BSD 2-Clause; the app is non-commercial
(no ads, no purchases), which matters because the bundled madmom beat-detection
models are licensed CC BY-NC-SA 4.0 (non-commercial) — see that file for
details.
