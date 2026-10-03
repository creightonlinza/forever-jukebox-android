#include "swing_renderer.h"

#include <algorithm>
#include <cmath>

#include <rubberband/RubberBandStretcher.h>

namespace fj {
namespace {

using RubberBand::RubberBandStretcher;

constexpr double kJoinFadeSeconds = 0.004;
constexpr double kMaxJoinFadeFraction = 0.25;
constexpr double kHalfPi = 1.57079632679489661923;

// Matches the options the web player's Rubber Band worker stretches with.
constexpr RubberBandStretcher::Options kStretchOptions =
    RubberBandStretcher::OptionProcessOffline |
    RubberBandStretcher::OptionStretchPrecise |
    RubberBandStretcher::OptionTransientsCrisp |
    RubberBandStretcher::OptionDetectorCompound |
    RubberBandStretcher::OptionPhaseLaminar |
    RubberBandStretcher::OptionPitchHighQuality |
    RubberBandStretcher::OptionChannelsTogether;

int16_t toInt16(float sample) {
    const float clamped = std::clamp(sample, -1.0f, 0.9999695f);
    return static_cast<int16_t>(std::lrint(clamped * 32768.0f));
}

bool segmentFits(const SwingSegment& segment, int64_t totalFrames) {
    return segment.inputStartFrame >= 0 && segment.inputFrameCount >= 0 &&
           segment.outputStartFrame >= 0 && segment.outputFrameCount >= 0 &&
           segment.inputStartFrame + segment.inputFrameCount <= totalFrames &&
           segment.outputStartFrame + segment.outputFrameCount <= totalFrames;
}

// Planar float scratch reused across segments so a render does not allocate
// per half-beat.
struct StretchScratch {
    std::vector<std::vector<float>> input;
    std::vector<std::vector<float>> stretched;
    std::vector<std::vector<float>> chunk;
    std::vector<float*> inputPtrs;
    std::vector<float*> chunkPtrs;

    explicit StretchScratch(int32_t channels)
        : input(static_cast<size_t>(channels)),
          stretched(static_cast<size_t>(channels)),
          chunk(static_cast<size_t>(channels)),
          inputPtrs(static_cast<size_t>(channels)),
          chunkPtrs(static_cast<size_t>(channels)) {}
};

void drain(RubberBandStretcher& stretcher, StretchScratch& scratch) {
    for (;;) {
        const int available = stretcher.available();
        if (available < 1) return;
        for (size_t channel = 0; channel < scratch.chunk.size(); channel += 1) {
            scratch.chunk[channel].resize(static_cast<size_t>(available));
            scratch.chunkPtrs[channel] = scratch.chunk[channel].data();
        }
        const size_t received =
            stretcher.retrieve(scratch.chunkPtrs.data(), static_cast<size_t>(available));
        for (size_t channel = 0; channel < scratch.chunk.size(); channel += 1) {
            scratch.stretched[channel].insert(
                scratch.stretched[channel].end(),
                scratch.chunk[channel].begin(),
                scratch.chunk[channel].begin() + static_cast<std::ptrdiff_t>(received));
        }
    }
}

// Leaves `scratch.stretched` holding `scratch.input` stretched to exactly
// `targetFrames` frames per channel: truncated when Rubber Band overshoots,
// padded with the last sample when it falls short.
void stretchToFrameCount(
    StretchScratch& scratch,
    int32_t sampleRate,
    size_t inputFrames,
    size_t targetFrames) {
    const size_t channels = scratch.input.size();
    for (auto& channel : scratch.stretched) {
        channel.clear();
    }
    if (inputFrames == 0 || targetFrames == 0) {
        return;
    }
    if (inputFrames == targetFrames) {
        for (size_t channel = 0; channel < channels; channel += 1) {
            scratch.stretched[channel] = scratch.input[channel];
        }
        return;
    }

    const double timeRatio =
        static_cast<double>(targetFrames) / static_cast<double>(inputFrames);
    RubberBandStretcher stretcher(
        static_cast<size_t>(sampleRate), channels, kStretchOptions, timeRatio, 1.0);
    stretcher.setExpectedInputDuration(inputFrames);
    const size_t chunkFrames = std::max<size_t>(1, stretcher.getSamplesRequired());

    for (size_t read = 0; read < inputFrames;) {
        const size_t count = std::min(chunkFrames, inputFrames - read);
        for (size_t channel = 0; channel < channels; channel += 1) {
            scratch.inputPtrs[channel] = scratch.input[channel].data() + read;
        }
        read += count;
        stretcher.study(scratch.inputPtrs.data(), count, read >= inputFrames);
    }
    for (size_t read = 0; read < inputFrames;) {
        const size_t count = std::min(chunkFrames, inputFrames - read);
        for (size_t channel = 0; channel < channels; channel += 1) {
            scratch.inputPtrs[channel] = scratch.input[channel].data() + read;
        }
        read += count;
        stretcher.process(scratch.inputPtrs.data(), count, read >= inputFrames);
        drain(stretcher, scratch);
    }
    drain(stretcher, scratch);

    for (auto& channel : scratch.stretched) {
        const float pad = channel.empty() ? 0.0f : channel.back();
        channel.resize(targetFrames, pad);
    }
}

// Equal-power fade out into `boundaryFrame` and back in after it, hiding the
// discontinuity where two independently stretched pieces meet.
void applyJoinFade(
    std::vector<int16_t>& output,
    int32_t channels,
    int64_t totalFrames,
    int64_t boundaryFrame,
    int32_t sampleRate,
    int64_t previousFrameCount,
    int64_t nextFrameCount) {
    if (boundaryFrame <= 0 || boundaryFrame >= totalFrames ||
        previousFrameCount <= 0 || nextFrameCount <= 0) {
        return;
    }
    const int64_t fadeFrames = std::max<int64_t>(
        1,
        std::min({
            static_cast<int64_t>(std::llround(kJoinFadeSeconds * sampleRate)),
            static_cast<int64_t>(std::floor(previousFrameCount * kMaxJoinFadeFraction)),
            static_cast<int64_t>(std::floor(nextFrameCount * kMaxJoinFadeFraction)),
            boundaryFrame,
            totalFrames - boundaryFrame,
        }));
    if (fadeFrames <= 1) {
        return;
    }
    const int64_t startFrame = boundaryFrame - fadeFrames;
    for (int64_t index = 0; index < fadeFrames; index += 1) {
        const double t =
            static_cast<double>(index + 1) / static_cast<double>(fadeFrames + 1);
        const double fadeOut = std::cos(t * kHalfPi);
        const double fadeIn = std::sin(t * kHalfPi);
        for (int32_t channel = 0; channel < channels; channel += 1) {
            int16_t& outSample =
                output[static_cast<size_t>((startFrame + index) * channels + channel)];
            int16_t& inSample =
                output[static_cast<size_t>((boundaryFrame + index) * channels + channel)];
            outSample = static_cast<int16_t>(std::lrint(outSample * fadeOut));
            inSample = static_cast<int16_t>(std::lrint(inSample * fadeIn));
        }
    }
}

struct BeatSpan {
    int64_t startFrame = 0;
    int64_t frameCount = 0;

    int64_t endFrame() const { return startFrame + frameCount; }
};

BeatSpan beatSpan(const std::vector<SwingSegment>& segments, size_t beatIndex) {
    const SwingSegment& first = segments[beatIndex * 2];
    const SwingSegment& second = segments[beatIndex * 2 + 1];
    return {first.outputStartFrame, first.outputFrameCount + second.outputFrameCount};
}

// Fades the beat's outer edges: against the previous beat when the two touch,
// and against untouched source audio wherever the beat grid has a gap.
void applyBeatBoundaryFades(
    std::vector<int16_t>& output,
    int32_t channels,
    int64_t totalFrames,
    const std::vector<SwingSegment>& segments,
    size_t beatIndex,
    int32_t sampleRate) {
    const size_t beatCount = segments.size() / 2;
    const BeatSpan beat = beatSpan(segments, beatIndex);
    const bool hasPrevious = beatIndex > 0;
    const BeatSpan previous = hasPrevious ? beatSpan(segments, beatIndex - 1) : BeatSpan{};

    if (hasPrevious && previous.endFrame() == beat.startFrame) {
        applyJoinFade(output, channels, totalFrames, beat.startFrame, sampleRate,
                      previous.frameCount, beat.frameCount);
    } else if (beat.startFrame > 0) {
        applyJoinFade(output, channels, totalFrames, beat.startFrame, sampleRate,
                      beat.startFrame, beat.frameCount);
    }

    const bool hasContiguousNext =
        beatIndex + 1 < beatCount &&
        beatSpan(segments, beatIndex + 1).startFrame == beat.endFrame();
    if (hasContiguousNext) {
        return;
    }
    const int64_t remainingFrames = totalFrames - beat.endFrame();
    if (remainingFrames > 0) {
        applyJoinFade(output, channels, totalFrames, beat.endFrame(), sampleRate,
                      beat.frameCount, remainingFrames);
    }
}

}  // namespace

bool renderSwingPcm(
    const std::vector<int16_t>& source,
    int32_t sampleRate,
    int32_t channelCount,
    const std::vector<SwingSegment>& segments,
    const SwingProgress& onProgress,
    std::vector<int16_t>* output) {
    if (!output || sampleRate <= 0 || channelCount <= 0 || segments.size() % 2 != 0) {
        return false;
    }
    const size_t channels = static_cast<size_t>(channelCount);
    const int64_t totalFrames = static_cast<int64_t>(source.size() / channels);
    for (const SwingSegment& segment : segments) {
        if (!segmentFits(segment, totalFrames)) {
            return false;
        }
    }

    *output = source;
    StretchScratch scratch(channelCount);
    const size_t totalSegments = segments.size();
    if (onProgress && !onProgress(0, totalSegments)) {
        return false;
    }
    for (size_t index = 0; index < totalSegments; index += 1) {
        const SwingSegment& segment = segments[index];
        const size_t inputFrames = static_cast<size_t>(segment.inputFrameCount);
        const size_t targetFrames = static_cast<size_t>(segment.outputFrameCount);
        for (size_t channel = 0; channel < channels; channel += 1) {
            std::vector<float>& planar = scratch.input[channel];
            planar.resize(inputFrames);
            const size_t base =
                static_cast<size_t>(segment.inputStartFrame) * channels + channel;
            for (size_t frame = 0; frame < inputFrames; frame += 1) {
                planar[frame] = static_cast<float>(source[base + frame * channels]) / 32768.0f;
            }
        }
        stretchToFrameCount(scratch, sampleRate, inputFrames, targetFrames);
        for (size_t channel = 0; channel < channels; channel += 1) {
            const std::vector<float>& planar = scratch.stretched[channel];
            const size_t base =
                static_cast<size_t>(segment.outputStartFrame) * channels + channel;
            for (size_t frame = 0; frame < planar.size(); frame += 1) {
                (*output)[base + frame * channels] = toInt16(planar[frame]);
            }
        }
        if (onProgress && !onProgress(index + 1, totalSegments)) {
            return false;
        }
        if (index % 2 == 1) {
            const SwingSegment& first = segments[index - 1];
            applyJoinFade(*output, channelCount, totalFrames, segment.outputStartFrame,
                          sampleRate, first.outputFrameCount, segment.outputFrameCount);
            applyBeatBoundaryFades(
                *output, channelCount, totalFrames, segments, index / 2, sampleRate);
        }
    }
    return true;
}

}  // namespace fj
