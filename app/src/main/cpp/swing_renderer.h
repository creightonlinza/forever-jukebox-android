#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace fj {

// One half-beat: `inputFrameCount` source frames starting at `inputStartFrame`
// are time-stretched to fill `outputFrameCount` frames at `outputStartFrame`.
struct SwingSegment {
    int64_t inputStartFrame = 0;
    int64_t inputFrameCount = 0;
    int64_t outputStartFrame = 0;
    int64_t outputFrameCount = 0;
};

// Called after each stretched segment; returning false cancels the render.
using SwingProgress = std::function<bool(size_t completedSegments, size_t totalSegments)>;

// Renders a swung copy of interleaved 16-bit `source`. `segments` holds two
// entries per beat (first half, second half) in beat order. The output has the
// same length as the source, with audio outside the beats copied unchanged, so
// beat times and scheduled jumps stay valid against it.
//
// Returns false, leaving `output` unspecified, when the render is cancelled or
// the segments do not fit the source.
bool renderSwingPcm(
    const std::vector<int16_t>& source,
    int32_t sampleRate,
    int32_t channelCount,
    const std::vector<SwingSegment>& segments,
    const SwingProgress& onProgress,
    std::vector<int16_t>* output);

}  // namespace fj
