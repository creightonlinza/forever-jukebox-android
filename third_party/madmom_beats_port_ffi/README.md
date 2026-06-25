# madmom_beats_port_ffi Android Integration

This app integrates the madmom beats/downbeats Rust FFI artifacts from:

- Source: `https://github.com/creightonlinza/madmom-beats-port/tree/main/rust/madmom_beats_port_ffi`
- Release: `https://github.com/creightonlinza/madmom-beats-port/releases/tag/v4.1.1`
- Android ZIP artifact: `https://github.com/creightonlinza/madmom-beats-port/releases/download/v4.1.1/madmom-beats-port-v4.1.1-android.zip`

## Expected Native Library Layout

ABI `.so` files are fetched from the release ZIP at build time and staged to:

- `app/build/generated/madmom_beats_port_ffi/jniLibs/arm64-v8a/libmadmom_beats_port_ffi.so`
- `app/build/generated/madmom_beats_port_ffi/jniLibs/armeabi-v7a/libmadmom_beats_port_ffi.so`
- `app/build/generated/madmom_beats_port_ffi/jniLibs/x86_64/libmadmom_beats_port_ffi.so`

The Gradle task `prepareMadmomBeatsPortFfiJniLibs` is wired into `preBuild`.

Optional overrides:

- `-PmadmomBeatsPortFfiZipUrl=<custom release zip url>`
- `-PmadmomBeatsPortFfiZipPath=/absolute/path/to/madmom-beats-port-v4.1.1-android.zip`

Header for reference:

- `third_party/madmom_beats_port_ffi/include/madmom_beats_port.h`

## Model Files

Model files are committed in assets under:

- `app/src/main/assets/madmom_beats_port_models/downbeats_blstm.json`
- `app/src/main/assets/madmom_beats_port_models/downbeats_blstm_weights.npz`

At runtime, `MadmomBeatsPortModelExtractor` copies these assets to:

- `<filesDir>/madmom_beats_port_models/`

so native code can open model files by filesystem path.

The v4.1.1 Android release ZIP also includes matching model files. Gradle only
extracts ABI-specific `libmadmom_beats_port_ffi.so` files from the ZIP; the
committed assets remain the packaged model source for this app.

## Licenses and Notices

The v4.1.1 release updates license attribution for the port:

- Project-owned source code and FFI artifacts are BSD 2-Clause.
- The Rust runtime includes portions derived from upstream `madmom`, also under
  BSD 2-Clause, and requires redistributing the upstream notice.
- The model artifacts remain CC BY-NC-SA 4.0 and non-commercial.

Local copies of the upstream v4.1.1 license map and notices are included in
this directory:

- `LICENSE`
- `NOTICE`
- `LICENSES/BSD_2_CLAUSE.txt`
- `LICENSES/MODELS_CC_BY_NC_SA.txt`
- `LICENSES/THIRD_PARTY.md`

## Notes

- APK ABI splits are enabled for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- Keep fetched `madmom_beats_port_ffi` binaries aligned with the same ABIs.
