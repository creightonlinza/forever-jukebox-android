#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace fj {

// One stretch of source audio on the remix grid: frames `[startFrame, stopFrame)`
// of the source are time-stretched to `targetFrames` frames. An empty range
// renders as silence of the target length.
struct WubSlice {
    int64_t startFrame = 0;
    int64_t stopFrame = 0;
    int64_t targetFrames = 0;
};

// One part of the arrangement: the samples averaged into its bed, the slices
// laid under it, and the bed gain (the source gets 1 - mix). Parts with the
// same `sliceGroup` share one stretched source. A part without slices is the
// ending: its bed keeps the samples' own length.
struct WubPart {
    std::vector<int32_t> samples;
    std::vector<WubSlice> slices;
    int32_t sliceGroup = 0;
    float mix = 0.5f;
};

struct WubRenderRequest {
    // Length of every part that carries source audio: exactly 8 bars at 140 BPM.
    int64_t partFrames = 0;
    std::vector<WubPart> parts;
};

// A decoded sample as the app shipped it: interleaved 16-bit PCM at its own
// sample rate and channel count. The renderer resamples it to the source rate.
struct WubSample {
    std::vector<int16_t> pcm;
    int32_t sampleRate = 48000;
    int32_t channelCount = 2;
};

// Called after each stretched slice; returning false cancels the render.
using WubProgress = std::function<bool(size_t completedSlices, size_t totalSlices)>;

// Renders the Wub Machine remix of interleaved 16-bit `source` as interleaved
// stereo 16-bit PCM at `sampleRate`. Mirrors dubstepRenderer.ts in the web
// repo with its beat-grid option: every slice is stretched onto the 140 BPM
// grid, slices are joined with 2 ms fades, each part's samples are averaged
// into a bed and the stretched source is mixed under it.
//
// Returns false, leaving `output` unspecified, when the render is cancelled or
// the request names a sample that `samples` does not hold.
bool renderWubPcm(
    const std::vector<int16_t>& source,
    int32_t sampleRate,
    int32_t channelCount,
    const WubRenderRequest& request,
    const std::vector<WubSample>& samples,
    const WubProgress& onProgress,
    std::vector<int16_t>* output);

}  // namespace fj
