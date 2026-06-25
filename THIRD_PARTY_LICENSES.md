# Third-Party Licenses

Forever Jukebox Android (the "app") is licensed under the GNU Affero General
Public License v3.0 (see [`LICENSE`](LICENSE)). It bundles or links the
third-party components listed below. This file documents their licenses and
attributions.

The app is distributed free of charge with **no advertising and no purchases**.
This non-commercial nature is relevant to the madmom terms noted below.

---

## Native audio analysis (bundled in the app binary)

### Essentia — GNU AGPLv3

- **Use:** Statically linked into the `local_analysis_jni` native library for
  on-device feature extraction (MFCC, HPCP, RMS).
- **License:** GNU Affero General Public License v3.0. Because Essentia is
  statically linked, the entire app is a derivative work and is itself released
  under AGPLv3.
- **License text:** [`third_party/essentia/LICENSES/ESSENTIA-AGPLv3.txt`](third_party/essentia/LICENSES/ESSENTIA-AGPLv3.txt)
- **Upstream:** <https://essentia.upf.edu/> · <https://github.com/MTG/essentia>
- **Prebuilt source:** static libraries fetched from
  `https://github.com/deeeed/rn-essentia-static` (commit
  `476d5cfa763ad8950bf91f876788e7d6739fdecc`); see
  [`third_party/essentia/prebuilt/active/SOURCE.txt`](third_party/essentia/prebuilt/active/SOURCE.txt).
- Copyright © Music Technology Group, Universitat Pompeu Fabra.

### Beat detection: `madmom-beats-port` + madmom models

- **Use:** Beat and downbeat detection in local mode, via the
  `madmom_beats_port_ffi` shared library and the bundled bidirectional-LSTM
  model files (`app/src/main/assets/madmom_beats_port_models/downbeats_blstm.json`
  and `downbeats_blstm_weights.npz`).
- **Port code (the `.so`):** Apache License 2.0 OR MIT, at your option.
  Source: <https://github.com/creightonlinza/madmom-beats-port> (v4.1.0).
- **Bundled models:** Exported from
  [`madmom_models`](https://github.com/CPJKU/madmom_models) and licensed
  **Creative Commons Attribution-NonCommercial-ShareAlike 4.0 (CC BY-NC-SA 4.0)**.
  The models **may not be used for commercial purposes**; Forever Jukebox is
  non-commercial (no ads, no purchases) and relies on this. Attribution:
  Institute of Computational Perception, Johannes Kepler University Linz (CPJKU).
  Commercial licensing inquiries: Gerhard Widmer
  (<http://www.cp.jku.at/people/widmer/>).
- **Upstream madmom** (MIT; referenced only to regenerate models, not shipped):
  <https://github.com/CPJKU/madmom>.
- **Citation:** Böck, Korzeniowski, Schlüter, Krebs, Widmer, "madmom: a new
  Python Audio and Music Signal Processing Library," ACM Multimedia 2016.

### SpeexDSP (resampler subset) — BSD 3-Clause

- **Use:** Sample-rate conversion feeding Essentia/madmom (vendored subset of
  the SpeexDSP resampler).
- **License text:** [`third_party/speexdsp/LICENSES/SPEEXDSP-BSD.txt`](third_party/speexdsp/LICENSES/SPEEXDSP-BSD.txt)
- **Upstream:** <https://gitlab.xiph.org/xiph/speexdsp>
- Copyright © 2002–2008 Xiph.org Foundation, Jean-Marc Valin, Analog Devices
  Inc., and the Commonwealth Scientific and Industrial Research Organisation
  (CSIRO).

---

## Libraries (Maven dependencies)

### Apache License 2.0

- **Google Oboe** (`com.google.oboe:oboe`) — low-latency audio engine.
  Copyright © Google LLC.
- **AndroidX / Jetpack & Jetpack Compose** (`androidx.*`, including
  `core-ktx`, `activity-compose`, `compose.ui`, `compose.material3`,
  `compose.material`, `compose.runtime`, `lifecycle-*`, `datastore-preferences`,
  `media`, `mediarouter`). Copyright © The Android Open Source Project.
- **Material Components for Android** (`com.google.android.material:material`).
  Copyright © Google LLC.
- **OkHttp** (`com.squareup.okhttp3:okhttp`). Copyright © Square, Inc.
- **Coil** (`io.coil-kt.coil3:coil-compose`, `coil-network-okhttp`).
  Copyright © Coil Contributors.
- **kotlinx.serialization** and **kotlinx.coroutines**
  (`org.jetbrains.kotlinx:*`). Copyright © JetBrains s.r.o. and contributors.
- **Google Play services – Cast framework**
  (`com.google.android.gms:play-services-cast-framework`), distributed under the
  Google Play Services SDK terms; bundled third-party notices ship within the
  AAR. Copyright © Google LLC.

### MIT License

- **Sentry Android SDK** (`io.sentry:sentry-android-core`,
  `sentry-android-ndk`) — crash and error reporting. Copyright © Sentry
  (Functional Software, Inc.) and contributors. Note: this SDK collects crash
  and device diagnostic data; see the app's Play Data Safety disclosure /
  privacy policy.

---

## Notes

- Full, authoritative license texts for the Maven dependencies are distributed
  within their respective artifacts (e.g. `META-INF/.../LICENSE.txt` and
  `third_party_licenses.txt` inside the AARs).
- The Google Play–distributed build excludes server/remote functionality; this
  notice covers components present in both the `full` and `play` builds unless
  stated otherwise.
