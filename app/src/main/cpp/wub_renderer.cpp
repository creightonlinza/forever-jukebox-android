#include "wub_renderer.h"

#include <algorithm>
#include <cmath>
#include <map>
#include <tuple>
#include <unordered_map>

#include "swing_renderer.h"

#if defined(FJ_HAS_SPEEXDSP)
#include <speex_resampler.h>
#endif

namespace fj {
namespace {

constexpr double kSliceFadeSeconds = 0.002;
constexpr size_t kStereo = 2;

using Planar = std::vector<std::vector<float>>;

Planar makePlanar(size_t frames) {
    return Planar(kStereo, std::vector<float>(frames, 0.0f));
}

int16_t toInt16(float sample) {
    const float clamped = std::clamp(sample, -1.0f, 0.9999695f);
    return static_cast<int16_t>(std::lrint(clamped * 32768.0f));
}

// Interleaved 16-bit PCM to planar stereo float; mono is duplicated and extra
// channels are dropped.
Planar toStereoPlanar(const int16_t* data, size_t frames, int32_t channelCount) {
    Planar out = makePlanar(frames);
    const size_t channels = static_cast<size_t>(std::max(1, channelCount));
    for (size_t frame = 0; frame < frames; frame += 1) {
        const float left = static_cast<float>(data[frame * channels]) / 32768.0f;
        const float right = channels > 1
            ? static_cast<float>(data[frame * channels + 1]) / 32768.0f
            : left;
        out[0][frame] = left;
        out[1][frame] = right;
    }
    return out;
}

std::vector<float> resampleLinear(const std::vector<float>& input, int32_t fromRate, int32_t toRate) {
    if (input.empty()) return {};
    const double ratio = static_cast<double>(toRate) / static_cast<double>(fromRate);
    const size_t outFrames = static_cast<size_t>(std::llround(static_cast<double>(input.size()) * ratio));
    std::vector<float> out(outFrames);
    for (size_t i = 0; i < outFrames; i += 1) {
        const double position = static_cast<double>(i) / ratio;
        const size_t index0 = std::min(input.size() - 1, static_cast<size_t>(std::floor(position)));
        const size_t index1 = std::min(input.size() - 1, index0 + 1);
        const float frac = static_cast<float>(position - static_cast<double>(index0));
        out[i] = input[index0] + (input[index1] - input[index0]) * frac;
    }
    return out;
}

#if defined(FJ_HAS_SPEEXDSP)
bool resampleSpeex(
    const std::vector<float>& input,
    int32_t fromRate,
    int32_t toRate,
    std::vector<float>* output) {
    int err = RESAMPLER_ERR_SUCCESS;
    SpeexResamplerState* state = speex_resampler_init(
        1,
        static_cast<spx_uint32_t>(fromRate),
        static_cast<spx_uint32_t>(toRate),
        SPEEX_RESAMPLER_QUALITY_MAX,
        &err);
    if (state == nullptr || err != RESAMPLER_ERR_SUCCESS) {
        if (state != nullptr) speex_resampler_destroy(state);
        return false;
    }
    const double ratio = static_cast<double>(toRate) / static_cast<double>(fromRate);
    output->clear();
    output->reserve(static_cast<size_t>(std::ceil(static_cast<double>(input.size()) * ratio)) + 256U);
    constexpr spx_uint32_t kChunk = 65536U;
    size_t offset = 0;
    while (offset < input.size()) {
        spx_uint32_t inLen = static_cast<spx_uint32_t>(std::min<size_t>(kChunk, input.size() - offset));
        spx_uint32_t outLen = static_cast<spx_uint32_t>(std::ceil(static_cast<double>(inLen) * ratio)) + 128U;
        const size_t writeOffset = output->size();
        output->resize(writeOffset + outLen);
        const int rc = speex_resampler_process_float(
            state, 0, input.data() + offset, &inLen, output->data() + writeOffset, &outLen);
        if (rc != RESAMPLER_ERR_SUCCESS) {
            speex_resampler_destroy(state);
            return false;
        }
        output->resize(writeOffset + outLen);
        offset += inLen;
        if (inLen == 0U && outLen == 0U) break;
    }
    // Flush the filter tail.
    const float silence = 0.0f;
    for (int flush = 0; flush < 32; flush += 1) {
        spx_uint32_t inLen = 0U;
        spx_uint32_t outLen = 512U;
        const size_t writeOffset = output->size();
        output->resize(writeOffset + outLen);
        const int rc = speex_resampler_process_float(
            state, 0, &silence, &inLen, output->data() + writeOffset, &outLen);
        if (rc != RESAMPLER_ERR_SUCCESS) {
            speex_resampler_destroy(state);
            return false;
        }
        output->resize(writeOffset + outLen);
        if (outLen == 0U) break;
    }
    speex_resampler_destroy(state);
    return true;
}
#endif

std::vector<float> resampleChannel(const std::vector<float>& input, int32_t fromRate, int32_t toRate) {
    if (fromRate == toRate || input.empty()) return input;
#if defined(FJ_HAS_SPEEXDSP)
    std::vector<float> out;
    if (resampleSpeex(input, fromRate, toRate, &out)) return out;
#endif
    return resampleLinear(input, fromRate, toRate);
}

// The sample as planar stereo float at the render sample rate.
Planar prepareSample(const WubSample& sample, int32_t sampleRate) {
    const size_t channels = static_cast<size_t>(std::max(1, sample.channelCount));
    const size_t frames = sample.pcm.size() / channels;
    Planar planar = toStereoPlanar(sample.pcm.data(), frames, sample.channelCount);
    if (sample.sampleRate > 0 && sample.sampleRate != sampleRate) {
        for (auto& channel : planar) {
            channel = resampleChannel(channel, sample.sampleRate, sampleRate);
        }
    }
    return planar;
}

// Short fades at both ends of `[offset, offset + length)` to avoid clicks.
void fadeEdges(Planar& channels, int32_t sampleRate, size_t offset, size_t length) {
    const size_t fade = std::min<size_t>(
        static_cast<size_t>(std::lround(kSliceFadeSeconds * sampleRate)), length / 4);
    for (auto& channel : channels) {
        for (size_t i = 0; i < fade; i += 1) {
            const float gain = static_cast<float>(i) / static_cast<float>(fade);
            channel[offset + i] *= gain;
            channel[offset + length - 1 - i] *= gain;
        }
    }
}

// Stretches `[start, stop)` of the source to `frames`; silence when empty.
Planar stretchRange(
    const Planar& source,
    int32_t sampleRate,
    int64_t start,
    int64_t stop,
    int64_t frames) {
    const int64_t sourceFrames = static_cast<int64_t>(source[0].size());
    start = std::clamp<int64_t>(start, 0, sourceFrames);
    stop = std::clamp<int64_t>(stop, start, sourceFrames);
    if (frames <= 0) return makePlanar(0);
    if (stop <= start) return makePlanar(static_cast<size_t>(frames));
    Planar input(kStereo);
    for (size_t channel = 0; channel < kStereo; channel += 1) {
        input[channel].assign(source[channel].begin() + start, source[channel].begin() + stop);
    }
    Planar stretched;
    stretchPlanarToFrames(input, sampleRate, static_cast<size_t>(frames), &stretched);
    for (auto& channel : stretched) {
        channel.resize(static_cast<size_t>(frames), 0.0f);
    }
    return stretched;
}

// Stretches every slice to its own share of the grid and lays the pieces end
// to end. Identical slices are stretched once.
bool stretchToGrid(
    const Planar& source,
    int32_t sampleRate,
    const std::vector<WubSlice>& slices,
    const std::function<bool()>& onSlice,
    Planar* out) {
    using Key = std::tuple<int64_t, int64_t, int64_t>;
    std::map<Key, Planar> pieces;
    std::vector<const Planar*> stretched;
    stretched.reserve(slices.size());
    size_t total = 0;
    for (const WubSlice& slice : slices) {
        const Key key{slice.startFrame, slice.stopFrame, slice.targetFrames};
        auto found = pieces.find(key);
        if (found == pieces.end()) {
            found = pieces.emplace(
                key,
                stretchRange(source, sampleRate, slice.startFrame, slice.stopFrame, slice.targetFrames))
                .first;
        }
        stretched.push_back(&found->second);
        total += found->second[0].size();
        if (!onSlice()) return false;
    }
    *out = makePlanar(total);
    size_t offset = 0;
    for (const Planar* piece : stretched) {
        const size_t length = (*piece)[0].size();
        for (size_t channel = 0; channel < kStereo; channel += 1) {
            std::copy(
                (*piece)[channel].begin(), (*piece)[channel].end(), (*out)[channel].begin() + offset);
        }
        fadeEdges(*out, sampleRate, offset, length);
        offset += length;
    }
    return true;
}

// Averages the samples into a bed of `length` frames.
Planar mixBed(const std::vector<const Planar*>& samples, size_t length) {
    Planar bed = makePlanar(length);
    if (samples.empty()) return bed;
    const float gain = 1.0f / static_cast<float>(samples.size());
    for (const Planar* sample : samples) {
        for (size_t channel = 0; channel < kStereo; channel += 1) {
            const std::vector<float>& data = (*sample)[channel];
            const size_t frames = std::min(length, data.size());
            for (size_t i = 0; i < frames; i += 1) {
                bed[channel][i] += data[i] * gain;
            }
        }
    }
    return bed;
}

// Mixes `under` into the bed in place: `mix` of the bed, `1 - mix` of the source.
void mixUnder(Planar& bed, const Planar& under, float mix) {
    const size_t overlap = std::min(bed[0].size(), under[0].size());
    for (size_t channel = 0; channel < kStereo; channel += 1) {
        std::vector<float>& target = bed[channel];
        const std::vector<float>& data = under[channel];
        for (size_t i = 0; i < target.size(); i += 1) {
            target[i] = target[i] * mix + (i < overlap ? data[i] * (1.0f - mix) : 0.0f);
        }
    }
}

}  // namespace

bool renderWubPcm(
    const std::vector<int16_t>& source,
    int32_t sampleRate,
    int32_t channelCount,
    const WubRenderRequest& request,
    const std::vector<WubSample>& samples,
    const WubProgress& onProgress,
    std::vector<int16_t>* output) {
    if (!output || sampleRate <= 0 || channelCount <= 0 || request.partFrames < 0) {
        return false;
    }
    for (const WubPart& part : request.parts) {
        for (int32_t index : part.samples) {
            if (index < 0 || static_cast<size_t>(index) >= samples.size()) return false;
        }
    }
    const size_t channels = static_cast<size_t>(channelCount);
    const Planar planarSource = toStereoPlanar(source.data(), source.size() / channels, channelCount);

    // Slices are counted once per group, matching the stretches actually run.
    size_t totalSlices = 0;
    {
        std::unordered_map<int32_t, bool> counted;
        for (const WubPart& part : request.parts) {
            if (part.slices.empty() || counted[part.sliceGroup]) continue;
            counted[part.sliceGroup] = true;
            totalSlices += part.slices.size();
        }
    }
    size_t completedSlices = 0;
    if (onProgress && !onProgress(0, totalSlices)) return false;

    std::unordered_map<int32_t, Planar> prepared;
    auto sampleFor = [&](int32_t index) -> const Planar* {
        auto found = prepared.find(index);
        if (found == prepared.end()) {
            found = prepared.emplace(index, prepareSample(samples[static_cast<size_t>(index)], sampleRate)).first;
        }
        return &found->second;
    };

    std::unordered_map<int32_t, Planar> stretched;
    std::vector<Planar> rendered;
    rendered.reserve(request.parts.size());
    size_t totalFrames = 0;
    for (const WubPart& part : request.parts) {
        std::vector<const Planar*> beds;
        beds.reserve(part.samples.size());
        for (int32_t index : part.samples) beds.push_back(sampleFor(index));
        if (part.slices.empty()) {
            size_t length = 0;
            for (const Planar* bed : beds) length = std::max(length, (*bed)[0].size());
            rendered.push_back(mixBed(beds, length));
            totalFrames += length;
            continue;
        }
        Planar bed = mixBed(beds, static_cast<size_t>(request.partFrames));
        auto under = stretched.find(part.sliceGroup);
        if (under == stretched.end()) {
            Planar pieces;
            const bool finished = stretchToGrid(
                planarSource, sampleRate, part.slices,
                [&]() {
                    completedSlices += 1;
                    return !onProgress || onProgress(completedSlices, totalSlices);
                },
                &pieces);
            if (!finished) return false;
            under = stretched.emplace(part.sliceGroup, std::move(pieces)).first;
        }
        mixUnder(bed, under->second, part.mix);
        totalFrames += bed[0].size();
        rendered.push_back(std::move(bed));
    }

    output->assign(totalFrames * kStereo, 0);
    size_t offset = 0;
    for (const Planar& part : rendered) {
        const size_t frames = part[0].size();
        for (size_t frame = 0; frame < frames; frame += 1) {
            (*output)[(offset + frame) * kStereo] = toInt16(part[0][frame]);
            (*output)[(offset + frame) * kStereo + 1] = toInt16(part[1][frame]);
        }
        offset += frames;
    }
    return true;
}

}  // namespace fj
