# Rubber Band Library Integration

This app uses the Rubber Band Library to time-stretch half-beats for the Swing audio mode.

## Source

- Upstream: `https://breakfastquay.com/rubberband/`
- Version: `3.3.0` (`rubberband-3.3.0.tar.bz2`, the release the web app's `rubberband-wasm` wraps)
- Included files (single-file build subset, upstream layout preserved):
  - `third_party/rubberband/single/RubberBandSingle.cpp`
  - `third_party/rubberband/rubberband/`
  - `third_party/rubberband/src/RubberBandStretcher.cpp`, `src/rubberband-c.cpp`
  - `third_party/rubberband/src/common/`, `src/faster/`, `src/finer/`

## Build wiring

- `app/src/main/cpp/CMakeLists.txt` builds `single/RubberBandSingle.cpp` as a static library
  using Rubber Band's built-in FFT and resampler, so it has no external dependencies.
- `fj_oboe` links that static library; `app/src/main/cpp/swing_renderer.cpp` calls it.

## License

- Rubber Band Library is licensed under the GNU General Public License, version 2 or later.
- License text is included at `third_party/rubberband/LICENSES/RUBBERBAND-GPL.txt`.
