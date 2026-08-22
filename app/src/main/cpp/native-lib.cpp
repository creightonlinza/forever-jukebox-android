#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include <android/log.h>
#include <jni.h>
#include <oboe/Oboe.h>

namespace {

constexpr const char* kLogTag = "FJOboe";
constexpr const char* kPackageName = "com.foreverjukebox.app";
constexpr const char* kPlaybackAttributionTag = "audio_playback";
constexpr double kPi = 3.14159265358979323846;
// Butterworth: a flat passband with no resonant bump, so a hot master cannot be
// pushed past full scale by the high/low pass mode filters themselves.
constexpr float kBiquadQ = 0.70710678f;
// A bandpass peaks at 0 dB regardless of Q, so Q only sets bandwidth here. This
// matches the web player's lofi bandpass so the two platforms share a tone.
constexpr float kBandPassQ = 1.0f;
constexpr double kPanRadiansPerSecond = 0.42;
constexpr double kJumpFrameEpsilon = 2.0;
constexpr double kMaxLateJumpFrames = 8.0;
constexpr double kMinJumpScheduleLeadFrames = kMaxLateJumpFrames;
constexpr float kNormalDuckingVolume = 1.0f;
constexpr float kDuckedVolume = 0.2f;
constexpr float kDuckingRampSpeed = 0.0002f;
constexpr int32_t kMaxAudioModeCode = 9;
constexpr int32_t kMinIntensity = 50;
constexpr int32_t kMaxIntensity = 150;
constexpr int32_t kDefaultIntensity = 100;
constexpr int32_t kEightBitDepth = 8;
constexpr int32_t kEightBitCrushSampleRate = 8000;
constexpr int32_t kBitcrusherLevels = 1 << kEightBitDepth;
constexpr float kCathedralReverbSeconds = 4.75f;
constexpr float kCathedralReverbDecay = 2.5f;
constexpr int32_t kCathedralTapCount = 64;
constexpr int32_t kCowbellSampleCount = 19;
constexpr float kLimiterAttackSeconds = 0.001f;
constexpr float kLimiterReleaseSeconds = 0.250f;

enum class AudioMode {
    Off = 0,
    Nightcore = 1,
    Daycore = 2,
    Vaporwave = 3,
    EightD = 4,
    Lofi = 5,
    EightBit = 6,
    Underwater = 7,
    Cathedral = 8,
    Cowbell = 9
};

struct AudioModeSettings {
    double rate = 1.0;
    float highPassFrequency = 0.0f;
    float lowPassFrequency = 0.0f;
    bool useBandPass = false;
    bool useEightBitBuffer = false;
    bool bitCrush = false;
    float dryMix = 1.0f;
    float reverbMix = 0.0f;
    bool cathedralReverb = false;
    bool pan = false;
};

struct CowbellSample {
    std::vector<int16_t> data;
    int32_t sampleRate = 44100;
    int32_t channelCount = 2;

    int64_t frameCount() const {
        if (channelCount <= 0) return 0;
        return static_cast<int64_t>(data.size() / static_cast<size_t>(channelCount));
    }
};

struct CowbellHit {
    int32_t sampleIndex = -1;
    double targetSourceFrame = 0.0;
    double sampleFrame = 0.0;
    float leftVolume = 0.0f;
    float rightVolume = 0.0f;
    bool active = false;
};

AudioModeSettings settingsForMode(int32_t mode) {
    switch (static_cast<AudioMode>(mode)) {
        case AudioMode::Nightcore:
            return {1.2, 150.0f, 0.0f, false, false, false, 1.0f, 0.0f, false, false};
        case AudioMode::Daycore:
            return {0.8, 0.0f, 0.0f, false, false, false, 1.0f, 0.4f, false, false};
        case AudioMode::Vaporwave:
            return {0.65, 0.0f, 1000.0f, false, false, false, 1.0f, 0.6f, false, false};
        case AudioMode::EightD:
            return {1.0, 0.0f, 0.0f, false, false, false, 1.0f, 0.5f, false, true};
        case AudioMode::Lofi:
            return {1.0, 0.0f, 2000.0f, true, false, false, 1.0f, 0.1f, false, false};
        case AudioMode::EightBit:
            return {1.0, 0.0f, 0.0f, false, true, true, 1.0f, 0.0f, false, false};
        case AudioMode::Underwater:
            return {1.0, 0.0f, 400.0f, false, false, false, 1.0f, 0.0f, false, false};
        case AudioMode::Cathedral:
            return {1.0, 150.0f, 5500.0f, false, false, false, 0.70f, 0.90f, true, false};
        case AudioMode::Cowbell:
        case AudioMode::Off:
        default:
            return {};
    }
}

// Only reverb modes can sum past full scale (dry <= 1.0 plus a wet tail), so
// only they route through the limiter. Everything else reaches the output
// untouched, exactly as the web player wires it.
bool audioModeNeedsLimiter(const AudioModeSettings& settings) {
    return settings.reverbMix > 0.0f;
}

int32_t sanitizeAudioModeCode(int32_t mode) {
    if (mode < 0 || mode > kMaxAudioModeCode) {
        return static_cast<int32_t>(AudioMode::Off);
    }
    return mode;
}

bool modeSupportsIntensity(int32_t mode) {
    switch (static_cast<AudioMode>(mode)) {
        case AudioMode::Nightcore:
        case AudioMode::Daycore:
        case AudioMode::Vaporwave:
            return true;
        default:
            return false;
    }
}

int32_t sanitizeIntensity(int32_t mode, int32_t intensity) {
    if (!modeSupportsIntensity(mode)) {
        return kDefaultIntensity;
    }
    return std::clamp(intensity, kMinIntensity, kMaxIntensity);
}

// Mode and intensity are packed into one atomic so the render thread can
// never observe a new mode paired with a stale intensity (or vice versa).
int32_t packModeAndIntensity(int32_t mode, int32_t intensity) {
    return (mode << 16) | (intensity & 0xFFFF);
}

int32_t modeOfPacked(int32_t packed) {
    return packed >> 16;
}

int32_t intensityOfPacked(int32_t packed) {
    return packed & 0xFFFF;
}

AudioModeSettings settingsForMode(int32_t mode, int32_t intensity) {
    AudioModeSettings settings = settingsForMode(mode);
    // At the default intensity the preset must pass through untouched so
    // pre-intensity favorites and shares sound bit-identical.
    if (intensity == kDefaultIntensity || !modeSupportsIntensity(mode)) {
        return settings;
    }
    const double i = intensity / 100.0;
    settings.rate = 1.0 + (settings.rate - 1.0) * i;
    if (settings.reverbMix > 0.0f) {
        settings.reverbMix =
            std::min(1.0f, settings.reverbMix * static_cast<float>(i));
    }
    // Filter cutoffs scale in octaves: pitch perception is logarithmic, so the
    // high-pass opens upward and the low-pass closes downward with intensity.
    if (settings.highPassFrequency > 0.0f) {
        settings.highPassFrequency *= std::exp2(static_cast<float>(i - 1.0));
    }
    if (settings.lowPassFrequency > 0.0f) {
        settings.lowPassFrequency *= std::exp2(static_cast<float>(1.0 - i));
    }
    return settings;
}

int16_t floatToInt16Sample(float sample) {
    const float clamped = std::clamp(sample, -1.0f, 0.9999695f);
    return static_cast<int16_t>(std::lrint(clamped * 32768.0f));
}

float quantizeEightBitSample(float sample) {
    const float clamped = std::clamp(sample, -1.0f, 1.0f);
    const float normalized = (clamped + 1.0f) * 0.5f;
    const float quantized = std::round(normalized * (kBitcrusherLevels - 1)) /
                            static_cast<float>(kBitcrusherLevels - 1);
    return quantized * 2.0f - 1.0f;
}

std::vector<int16_t> renderEightBitPcm(
    const std::vector<int16_t>& source,
    int32_t sampleRate,
    int32_t channelCount) {
    if (source.empty() || sampleRate <= 0 || channelCount <= 0) {
        return {};
    }
    std::vector<int16_t> output(source.size(), 0);
    const int64_t totalFrames =
        static_cast<int64_t>(source.size() / static_cast<size_t>(channelCount));
    const int32_t holdFrames = std::max(
        1,
        static_cast<int32_t>(std::lround(
            static_cast<double>(sampleRate) / static_cast<double>(kEightBitCrushSampleRate))));
    for (int32_t channel = 0; channel < channelCount; channel += 1) {
        for (int64_t frame = 0; frame < totalFrames; frame += holdFrames) {
            const size_t sourceOffset = static_cast<size_t>(frame * channelCount + channel);
            const float sample = static_cast<float>(source[sourceOffset]) / 32768.0f;
            const int16_t quantized = floatToInt16Sample(quantizeEightBitSample(sample));
            const int64_t endFrame = std::min<int64_t>(totalFrames, frame + holdFrames);
            for (int64_t heldFrame = frame; heldFrame < endFrame; heldFrame += 1) {
                const size_t targetOffset =
                    static_cast<size_t>(heldFrame * channelCount + channel);
                output[targetOffset] = quantized;
            }
        }
    }
    return output;
}

class BiquadFilter {
public:
    enum class Type {
        LowPass,
        HighPass,
        BandPass
    };

    explicit BiquadFilter(int32_t channelCount) {
        resize(channelCount);
    }

    void resize(int32_t channelCount) {
        mZ1.assign(static_cast<size_t>(channelCount), 0.0f);
        mZ2.assign(static_cast<size_t>(channelCount), 0.0f);
    }

    void reset() {
        std::fill(mZ1.begin(), mZ1.end(), 0.0f);
        std::fill(mZ2.begin(), mZ2.end(), 0.0f);
    }

    void configure(Type type, float frequency, int32_t sampleRate) {
        reset();
        if (frequency <= 0.0f || sampleRate <= 0) {
            mEnabled = false;
            return;
        }
        const float nyquist = static_cast<float>(sampleRate) * 0.5f;
        const float safeFrequency = std::clamp(frequency, 10.0f, nyquist - 10.0f);
        const float omega = static_cast<float>(2.0 * kPi) * safeFrequency /
                            static_cast<float>(sampleRate);
        const float sinOmega = std::sin(omega);
        const float cosOmega = std::cos(omega);
        const float q = type == Type::BandPass ? kBandPassQ : kBiquadQ;
        const float alpha = sinOmega / (2.0f * q);

        float b0 = 0.0f;
        float b1 = 0.0f;
        float b2 = 0.0f;
        const float a0 = 1.0f + alpha;
        float a1 = -2.0f * cosOmega;
        float a2 = 1.0f - alpha;

        switch (type) {
            case Type::LowPass:
                b0 = (1.0f - cosOmega) * 0.5f;
                b1 = 1.0f - cosOmega;
                b2 = (1.0f - cosOmega) * 0.5f;
                break;
            case Type::HighPass:
                b0 = (1.0f + cosOmega) * 0.5f;
                b1 = -(1.0f + cosOmega);
                b2 = (1.0f + cosOmega) * 0.5f;
                break;
            case Type::BandPass:
                b0 = alpha;
                b1 = 0.0f;
                b2 = -alpha;
                break;
        }

        mB0 = b0 / a0;
        mB1 = b1 / a0;
        mB2 = b2 / a0;
        mA1 = a1 / a0;
        mA2 = a2 / a0;
        mEnabled = true;
    }

    float process(float input, int32_t channel) {
        if (!mEnabled) {
            return input;
        }
        const size_t index = static_cast<size_t>(channel);
        const float output = mB0 * input + mZ1[index];
        mZ1[index] = mB1 * input - mA1 * output + mZ2[index];
        mZ2[index] = mB2 * input - mA2 * output;
        return output;
    }

private:
    bool mEnabled = false;
    float mB0 = 1.0f;
    float mB1 = 0.0f;
    float mB2 = 0.0f;
    float mA1 = 0.0f;
    float mA2 = 0.0f;
    std::vector<float> mZ1;
    std::vector<float> mZ2;
};

class SimpleReverb {
public:
    SimpleReverb(int32_t sampleRate, int32_t channelCount)
        : mSampleRate(sampleRate), mChannelCount(channelCount) {
        resize();
    }

    void reset() {
        std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
        mIndex = 0;
    }

    void setMix(float mix) {
        mMix = std::clamp(mix, 0.0f, 1.0f);
        if (mMix <= 0.0f) {
            reset();
        }
    }

    float process(float input, int32_t channel) {
        if (mMix <= 0.0f || mDelayFrames <= 0) {
            return input;
        }
        const int32_t tapOffset = mTapOffsets[static_cast<size_t>(channel) % mTapOffsets.size()];
        int32_t readIndex = mIndex - tapOffset;
        if (readIndex < 0) {
            readIndex += mDelayFrames;
        }
        const size_t writeOffset = static_cast<size_t>(mIndex * mChannelCount + channel);
        const size_t readOffset = static_cast<size_t>(readIndex * mChannelCount + channel);
        const float delayed = mBuffer[readOffset];
        mBuffer[writeOffset] = input + delayed * 0.45f;
        return input + delayed * mMix;
    }

    void advanceFrame() {
        if (mMix <= 0.0f || mDelayFrames <= 0) {
            return;
        }
        mIndex += 1;
        if (mIndex >= mDelayFrames) {
            mIndex = 0;
        }
    }

private:
    void resize() {
        mDelayFrames = std::max(1, mSampleRate * 5 / 2);
        mBuffer.assign(static_cast<size_t>(mDelayFrames * mChannelCount), 0.0f);
        mTapOffsets = {
            std::max(1, mSampleRate * 97 / 1000),
            std::max(1, mSampleRate * 131 / 1000)
        };
    }

    int32_t mSampleRate = 44100;
    int32_t mChannelCount = 2;
    int32_t mDelayFrames = 0;
    int32_t mIndex = 0;
    float mMix = 0.0f;
    std::vector<int32_t> mTapOffsets;
    std::vector<float> mBuffer;
};

class CathedralReverb {
public:
    CathedralReverb(int32_t sampleRate, int32_t channelCount)
        : mSampleRate(sampleRate), mChannelCount(channelCount) {
        resize();
        buildTaps();
    }

    void reset() {
        std::fill(mBuffer.begin(), mBuffer.end(), 0.0f);
        mIndex = 0;
    }

    void setActive(bool active) {
        if (mActive == active) {
            return;
        }
        mActive = active;
        reset();
    }

    float processWet(float input, int32_t channel) {
        if (!mActive || mDelayFrames <= 1) {
            return 0.0f;
        }
        const int32_t safeChannel = channel % std::max(1, mChannelCount);
        float wet = 0.0f;
        const auto& taps = mTaps[static_cast<size_t>(safeChannel) % mTaps.size()];
        for (const Tap& tap : taps) {
            int32_t readIndex = mIndex - tap.offset;
            if (readIndex < 0) {
                readIndex += mDelayFrames;
            }
            const size_t readOffset =
                static_cast<size_t>(readIndex * mChannelCount + safeChannel);
            wet += mBuffer[readOffset] * tap.gain;
        }
        const size_t writeOffset = static_cast<size_t>(mIndex * mChannelCount + safeChannel);
        mBuffer[writeOffset] = input;
        return wet;
    }

    void advanceFrame() {
        if (!mActive || mDelayFrames <= 1) {
            return;
        }
        mIndex += 1;
        if (mIndex >= mDelayFrames) {
            mIndex = 0;
        }
    }

private:
    struct Tap {
        int32_t offset = 1;
        float gain = 0.0f;
    };

    void resize() {
        mDelayFrames = std::max(
            1,
            static_cast<int32_t>(std::floor(
                static_cast<float>(mSampleRate) * kCathedralReverbSeconds)));
        mBuffer.assign(static_cast<size_t>(mDelayFrames * mChannelCount), 0.0f);
        mTaps.resize(static_cast<size_t>(std::max(1, mChannelCount)));
    }

    static float nextRandom(uint32_t& seed) {
        seed += 0x6d2b79f5u;
        uint32_t x = seed;
        x = (x ^ (x >> 15u)) * (x | 1u);
        x ^= x + ((x ^ (x >> 7u)) * (x | 61u));
        return static_cast<float>((x ^ (x >> 14u)) & 0x00ffffffu) /
               static_cast<float>(0x01000000u);
    }

    void buildTaps() {
        const float scale = 1.0f / std::sqrt(static_cast<float>(kCathedralTapCount));
        for (int32_t channel = 0; channel < static_cast<int32_t>(mTaps.size()); channel += 1) {
            auto& taps = mTaps[static_cast<size_t>(channel)];
            taps.clear();
            taps.reserve(static_cast<size_t>(kCathedralTapCount));
            uint32_t seed = 123456789u + static_cast<uint32_t>(channel) * 1013904223u;
            for (int32_t index = 0; index < kCathedralTapCount; index += 1) {
                const float jitter = nextRandom(seed) * 0.85f;
                const float position =
                    (static_cast<float>(index) + jitter) /
                    static_cast<float>(kCathedralTapCount);
                const int32_t offset = std::clamp(
                    static_cast<int32_t>(std::floor(
                        position * static_cast<float>(mDelayFrames - 1))) + 1,
                    1,
                    std::max(1, mDelayFrames - 1));
                const float t = static_cast<float>(offset) / static_cast<float>(mDelayFrames);
                const float decay = std::pow(1.0f - t, kCathedralReverbDecay);
                const float noise = nextRandom(seed) * 2.0f - 1.0f;
                taps.push_back({offset, noise * decay * scale});
            }
        }
    }

    int32_t mSampleRate = 44100;
    int32_t mChannelCount = 2;
    int32_t mDelayFrames = 0;
    int32_t mIndex = 0;
    bool mActive = false;
    std::vector<float> mBuffer;
    std::vector<std::vector<Tap>> mTaps;
};

class OboePlayer : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback {
public:
    OboePlayer(int32_t sampleRate, int32_t channelCount)
        : mSampleRate(sampleRate),
          mChannelCount(channelCount),
          mHighPass(channelCount),
          mToneFilter(channelCount),
          mReverb(sampleRate, channelCount),
          mCathedralReverb(sampleRate, channelCount),
          mMixScratch(static_cast<size_t>(std::max(1, channelCount)), 0.0f) {
        const float rate = static_cast<float>(std::max(1, sampleRate));
        mLimiterAttackCoeff = std::exp(-1.0f / (kLimiterAttackSeconds * rate));
        mLimiterReleaseCoeff = std::exp(-1.0f / (kLimiterReleaseSeconds * rate));
    }

    bool open() {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        return openLocked();
    }

    void close() {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        closeLocked();
    }

    void play() {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        if (!ensureStreamLocked()) {
            mIsPlaying.store(false);
            return;
        }
        auto result = mStream->requestStart();
        if (result != oboe::Result::OK) {
            const std::string firstFailure =
                describeStreamLocked("requestStart", result);
            closeLocked();
            if (ensureStreamLocked()) {
                result = mStream->requestStart();
                if (result != oboe::Result::OK) {
                    setLastStartFailureLocked(
                        firstFailure + "; retry " +
                        describeStreamLocked("requestStart", result));
                }
            } else {
                setLastStartFailureLocked(firstFailure + "; retry " + mLastStartFailure);
            }
        }
        mIsPlaying.store(result == oboe::Result::OK);
        if (result == oboe::Result::OK) {
            mLastStartFailure.clear();
        }
    }

    // Human-readable reason for the most recent play() that left the player
    // stopped, or empty when the last play() succeeded. Read from the Java
    // side so the failure reason reaches crash reporting instead of only
    // logcat.
    std::string lastStartFailure() {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        return mLastStartFailure;
    }

    void pause() {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        if (mStream) {
            auto result = mStream->requestPause();
            if (result != oboe::Result::OK) {
                closeLocked();
            }
        }
        clearPromotedJumpEvent();
        mIsPlaying.store(false);
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        if (mStream) {
            auto result = mStream->requestStop();
            if (result != oboe::Result::OK) {
                closeLocked();
            }
        }
        mReadFrame.store(0.0);
        mAudioFrame.store(0);
        mJumpAtSourceFrame.store(0.0);
        mJumpToFrame.store(0.0);
        mHasJump.store(false);
        clearPromotedJumpEvent();
        cancelCowbellHits();
        clearAnchorJump();
        resetDspState();
        mIsPlaying.store(false);
    }

    void loadPcm(std::vector<int16_t>&& data) {
        {
            std::lock_guard<std::mutex> lock(mDataMutex);
            mAudioData = std::move(data);
            mEightBitAudioData.clear();
            mEightBitAudioData.shrink_to_fit();
            syncEightBitBufferLocked();
            mTotalFrames =
                static_cast<int64_t>(mAudioData.size() / static_cast<size_t>(mChannelCount));
            mCowbellHits.clear();
        }
        mReadFrame.store(0.0);
        mAudioFrame.store(0);
        mHasJump.store(false);
        clearPromotedJumpEvent();
        clearAnchorJump();
        resetDspState();
    }

    void setGain(float gain) {
        mGain.store(std::clamp(gain, 0.0f, 1.0f));
    }

    void setDucking(bool active) {
        mDuckingTargetVolume.store(active ? kDuckedVolume : kNormalDuckingVolume);
    }

    void setJukeboxAudioMode(int32_t mode, int32_t intensity) {
        const int32_t sanitizedMode = sanitizeAudioModeCode(mode);
        mAudioModeAndIntensity.store(
            packModeAndIntensity(sanitizedMode, sanitizeIntensity(sanitizedMode, intensity)));
        std::lock_guard<std::mutex> lock(mDataMutex);
        syncEightBitBufferLocked();
    }

    // Materializes or frees the 8-bit downsampled buffer to match the active
    // audio mode. The 8-bit buffer is a full second copy of the audio, so it is
    // only built while the EightBit mode is active and released otherwise.
    // Must be called with mDataMutex held.
    void syncEightBitBufferLocked() {
        if (settingsForMode(modeOfPacked(mAudioModeAndIntensity.load())).useEightBitBuffer) {
            if (mEightBitAudioData.empty() && !mAudioData.empty()) {
                mEightBitAudioData =
                    renderEightBitPcm(mAudioData, mSampleRate, mChannelCount);
            }
        } else if (!mEightBitAudioData.empty()) {
            mEightBitAudioData.clear();
            mEightBitAudioData.shrink_to_fit();
        }
    }

    double getPlaybackRate() const {
        const int32_t packed = mAudioModeAndIntensity.load();
        return settingsForMode(modeOfPacked(packed), intensityOfPacked(packed)).rate;
    }

    void cloneAudioFrom(OboePlayer& source) {
        if (this == &source) return;
        {
            std::scoped_lock lock(mDataMutex, source.mDataMutex);
            mAudioData = source.mAudioData;
            mEightBitAudioData.clear();
            mEightBitAudioData.shrink_to_fit();
            // Only carry the 8-bit copy if this player's mode actually needs it.
            if (settingsForMode(modeOfPacked(mAudioModeAndIntensity.load())).useEightBitBuffer) {
                mEightBitAudioData = source.mEightBitAudioData.empty()
                    ? renderEightBitPcm(mAudioData, mSampleRate, mChannelCount)
                    : source.mEightBitAudioData;
            }
            mTotalFrames = source.mTotalFrames;
        }
        mReadFrame.store(0.0);
        mAudioFrame.store(0);
        mJumpAtSourceFrame.store(0.0);
        mJumpToFrame.store(0.0);
        mHasJump.store(false);
        clearPromotedJumpEvent();
        clearAnchorJump();
        resetDspState();
    }

    void seekSeconds(double seconds) {
        const int64_t frame = static_cast<int64_t>(seconds * static_cast<double>(mSampleRate));
        mReadFrame.store(frame < 0 ? 0.0 : static_cast<double>(frame));
        mHasJump.store(false);
        clearPromotedJumpEvent();
        cancelCowbellHits();
        resetDspState();
    }

    void loadCowbellSample(
        int32_t sampleIndex,
        std::vector<int16_t>&& data,
        int32_t sourceSampleRate,
        int32_t sourceChannelCount) {
        if (sampleIndex < 0 || sampleIndex >= kCowbellSampleCount ||
            data.empty() || sourceSampleRate <= 0 || sourceChannelCount <= 0) {
            return;
        }
        std::lock_guard<std::mutex> lock(mDataMutex);
        mCowbellSamples[static_cast<size_t>(sampleIndex)] = {
            std::move(data),
            sourceSampleRate,
            sourceChannelCount
        };
    }

    void scheduleCowbellHit(
        int32_t sampleIndex,
        double targetTimeSeconds,
        float leftVolume,
        float rightVolume) {
        if (sampleIndex < 0 || sampleIndex >= kCowbellSampleCount ||
            !std::isfinite(targetTimeSeconds)) {
            return;
        }
        std::lock_guard<std::mutex> lock(mDataMutex);
        const CowbellSample& sample = mCowbellSamples[static_cast<size_t>(sampleIndex)];
        if (sample.data.empty() || sample.frameCount() <= 0) {
            return;
        }
        mCowbellHits.push_back({
            sampleIndex,
            targetTimeSeconds * static_cast<double>(mSampleRate),
            0.0,
            std::max(0.0f, leftVolume),
            std::max(0.0f, rightVolume),
            false
        });
    }

    void cancelCowbellHits() {
        std::lock_guard<std::mutex> lock(mDataMutex);
        mCowbellHits.clear();
    }

    void cancelPendingCowbellHits() {
        std::lock_guard<std::mutex> lock(mDataMutex);
        mCowbellHits.erase(
            std::remove_if(
                mCowbellHits.begin(),
                mCowbellHits.end(),
                [](const CowbellHit& hit) {
                    return !hit.active;
                }),
            mCowbellHits.end());
    }

    bool scheduleJump(double targetTime, double sourceStartTime) {
        if (!std::isfinite(targetTime) || !std::isfinite(sourceStartTime)) {
            return false;
        }
        const double targetFrameRaw = targetTime * static_cast<double>(mSampleRate);
        const double sourceFrameRaw = sourceStartTime * static_cast<double>(mSampleRate);
        const int64_t targetFrame = static_cast<int64_t>(targetFrameRaw);
        const int64_t sourceFrame = static_cast<int64_t>(sourceFrameRaw);
        {
            std::lock_guard<std::mutex> lock(mDataMutex);
            if (mAudioData.empty() || mTotalFrames <= 0) {
                return false;
            }
            if (targetFrame < 0 || targetFrame >= mTotalFrames) {
                return false;
            }
            if (sourceFrame < 0 ||
                sourceFrameRaw > static_cast<double>(mTotalFrames) + kJumpFrameEpsilon) {
                return false;
            }
        }
        const double currentFrame = mReadFrame.load();
        if (sourceFrameRaw - currentFrame <= kMinJumpScheduleLeadFrames) {
            return false;
        }
        mJumpToFrame.store(static_cast<double>(targetFrame));
        mJumpAtSourceFrame.store(static_cast<double>(sourceFrame));
        mHasJump.store(true);
        return true;
    }

    void cancelScheduledJump() {
        mHasJump.store(false);
        mJumpAtSourceFrame.store(0.0);
        mJumpToFrame.store(0.0);
    }

    bool setAnchorJump(double targetTime, double sourceStartTime) {
        if (!std::isfinite(targetTime) || !std::isfinite(sourceStartTime)) {
            return false;
        }
        const double targetFrameRaw = targetTime * static_cast<double>(mSampleRate);
        const double sourceFrameRaw = sourceStartTime * static_cast<double>(mSampleRate);
        const int64_t targetFrame = static_cast<int64_t>(targetFrameRaw);
        const int64_t sourceFrame = static_cast<int64_t>(sourceFrameRaw);
        {
            std::lock_guard<std::mutex> lock(mDataMutex);
            if (mAudioData.empty() || mTotalFrames <= 0) {
                return false;
            }
            if (targetFrame < 0 || targetFrame >= mTotalFrames) {
                return false;
            }
            if (sourceFrame < 0 ||
                sourceFrameRaw > static_cast<double>(mTotalFrames) + kJumpFrameEpsilon) {
                return false;
            }
        }
        mAnchorJumpToFrame.store(static_cast<double>(targetFrame));
        mAnchorJumpAtSourceFrame.store(static_cast<double>(sourceFrame));
        mHasAnchorJump.store(true);
        return true;
    }

    void clearAnchorJump() {
        mHasAnchorJump.store(false);
        mAnchorJumpAtSourceFrame.store(0.0);
        mAnchorJumpToFrame.store(0.0);
    }

    bool consumeJumpEvent(double* sourceStartTime, double* targetTime) {
        if (!sourceStartTime || !targetTime) {
            return false;
        }
        std::lock_guard<std::mutex> lock(mDataMutex);
        if (!mHasPromotedJump.exchange(false)) {
            return false;
        }
        const double sampleRate = static_cast<double>(mSampleRate);
        *sourceStartTime = mPromotedJumpAtSourceFrame.load() / sampleRate;
        *targetTime = mPromotedJumpToFrame.load() / sampleRate;
        return true;
    }

    double getCurrentTimeSeconds() const {
        const double frame = mReadFrame.load();
        return frame / static_cast<double>(mSampleRate);
    }

    double getAudioTimeSeconds() const {
        const int64_t frame = mAudioFrame.load();
        return static_cast<double>(frame) / static_cast<double>(mSampleRate);
    }

    bool isPlaying() const {
        return mIsPlaying.load();
    }

    bool hasAudio() const {
        return mTotalFrames > 0 && !mAudioData.empty();
    }

    int32_t getChannelCount() const {
        return mChannelCount;
    }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream*,
        void* audioData,
        int32_t numFrames) override {
        auto* output = static_cast<int16_t*>(audioData);
        double currentFrame = mReadFrame.load();
        int64_t audioFrame = mAudioFrame.load();

        currentFrame = renderFrames(output, currentFrame, numFrames);
        audioFrame += numFrames;

        mReadFrame.store(currentFrame);
        mAudioFrame.store(audioFrame);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(
        oboe::AudioStream*,
        oboe::Result error) override {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "Audio stream closed with error: %s",
                            oboe::convertToText(error));
        const bool wasPlaying = mIsPlaying.load();
        if (open()) {
            if (wasPlaying && mStream) {
                auto result = mStream->requestStart();
                mIsPlaying.store(result == oboe::Result::OK);
            }
        } else {
            mIsPlaying.store(false);
        }
    }

private:
    void setLastStartFailureLocked(std::string reason) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", reason.c_str());
        mLastStartFailure = std::move(reason);
    }

    std::string describeStreamLocked(const char* step, oboe::Result result) const {
        std::string text = std::string(step) + " failed: " + oboe::convertToText(result);
        if (mStream) {
            text += " (api=" + std::string(oboe::convertToText(mStream->getAudioApi())) +
                    " rate=" + std::to_string(mStream->getSampleRate()) +
                    " channels=" + std::to_string(mStream->getChannelCount()) +
                    " perf=" + oboe::convertToText(mStream->getPerformanceMode()) +
                    " state=" + oboe::convertToText(mStream->getState()) + ")";
        }
        return text;
    }

    oboe::Result tryOpenLocked(oboe::PerformanceMode performanceMode) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(performanceMode)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setPackageName(kPackageName)
            ->setAttributionTag(kPlaybackAttributionTag)
            ->setSampleRate(mSampleRate)
            ->setChannelCount(mChannelCount)
            ->setFormat(oboe::AudioFormat::I16)
            // The callback always sees source-rate frames in the source
            // channel layout; Oboe converts to whatever the device supports.
            // Without these the open fails outright on devices whose AAudio
            // path rejects a non-native rate or layout.
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            ->setDataCallback(this)
            ->setErrorCallback(this);
        return builder.openStream(mStream);
    }

    bool openLocked() {
        // Low-latency is preferred; fall back to a plain stream so a HAL that
        // refuses the low-latency path still produces audio.
        auto result = tryOpenLocked(oboe::PerformanceMode::LowLatency);
        std::string failure;
        if (result != oboe::Result::OK) {
            failure = "open(LowLatency, rate=" + std::to_string(mSampleRate) +
                      " channels=" + std::to_string(mChannelCount) +
                      ") failed: " + oboe::convertToText(result);
            mStream.reset();
            result = tryOpenLocked(oboe::PerformanceMode::None);
        }
        if (result != oboe::Result::OK) {
            mStream.reset();
            setLastStartFailureLocked(
                failure + "; open(None) failed: " +
                oboe::convertToText(result));
            return false;
        }
        if (!failure.empty()) {
            __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                "%s; opened fallback stream (rate=%d channels=%d)",
                                failure.c_str(), mStream->getSampleRate(),
                                mStream->getChannelCount());
        }
        const int32_t burst = mStream->getFramesPerBurst();
        if (burst > 0) {
            mStream->setBufferSizeInFrames(burst * 2);
        }
        return true;
    }

    void closeLocked() {
        if (mStream) {
            mStream->requestStop();
            mStream->close();
            mStream.reset();
        }
    }

    bool ensureStreamLocked() {
        if (!mStream) {
            return openLocked();
        }
        const auto state = mStream->getState();
        if (state == oboe::StreamState::Closed ||
            state == oboe::StreamState::Disconnected) {
            closeLocked();
            return openLocked();
        }
        return true;
    }

    double renderFrames(int16_t* output, double startFrame, int32_t frames) {
        const int32_t channels = mChannelCount;
        std::lock_guard<std::mutex> dspLock(mDspMutex);
        const AudioModeSettings settings = updateDspModeIfNeeded();
        // Settings are fixed for the whole callback, so the routing decision is
        // made once here rather than per frame. Modes that bypass the limiter
        // leave the envelope parked at zero so they never start ducked.
        const bool limiterActive = audioModeNeedsLimiter(settings);
        if (!limiterActive) {
            mLimiterEnvelope = 0.0f;
        }
        double sourceFrame = startFrame;

        if (frames <= 0) return sourceFrame;

        std::lock_guard<std::mutex> lock(mDataMutex);
        const std::vector<int16_t>& audioData =
            settings.useEightBitBuffer && !mEightBitAudioData.empty()
                ? mEightBitAudioData
                : mAudioData;
        const int64_t totalFrames =
            static_cast<int64_t>(audioData.size() / static_cast<size_t>(channels));
        for (int32_t frame = 0; frame < frames; frame += 1) {
            sourceFrame = applyScheduledJump(sourceFrame);
            const float outputGain = mGain.load() * nextDuckingVolume();
            if (sourceFrame >= static_cast<double>(totalFrames) || audioData.empty()) {
                std::fill(output, output + channels, 0);
                if (limiterActive) {
                    mLimiterEnvelope *= mLimiterReleaseCoeff;
                }
                output += channels;
                sourceFrame += settings.rate;
                advanceDspFrame(settings);
                continue;
            }

            const int64_t frame0 = static_cast<int64_t>(std::floor(sourceFrame));
            const int64_t frame1 = std::min<int64_t>(frame0 + 1, totalFrames - 1);
            const float frac = static_cast<float>(sourceFrame - static_cast<double>(frame0));
            prepareCowbellHitsForFrame(sourceFrame);
            // The scratch buffer holds the music path only. The cowbell overlay
            // is deliberately kept out of it so it never drives the limiter.
            float* music = mMixScratch.data();
            float framePeak = 0.0f;
            for (int32_t channel = 0; channel < channels; channel += 1) {
                const size_t offset0 = static_cast<size_t>(frame0 * channels + channel);
                const size_t offset1 = static_cast<size_t>(frame1 * channels + channel);
                const float s0 = static_cast<float>(audioData[offset0]) / 32768.0f;
                const float s1 = static_cast<float>(audioData[offset1]) / 32768.0f;
                float sample = s0 + (s1 - s0) * frac;
                sample = processDspSample(sample, channel, settings);
                music[channel] = sample * outputGain;
                if (limiterActive) {
                    framePeak = std::max(framePeak, std::fabs(music[channel]));
                }
            }
            // Linked peak limiter: one gain across channels so the stereo image
            // holds still, and unity gain whenever the music stays within full
            // scale, so even limited modes are transparent below 0 dBFS.
            float limiterGain = 1.0f;
            if (limiterActive) {
                const float coeff = framePeak > mLimiterEnvelope ? mLimiterAttackCoeff
                                                                 : mLimiterReleaseCoeff;
                mLimiterEnvelope = coeff * mLimiterEnvelope + (1.0f - coeff) * framePeak;
                if (mLimiterEnvelope > 1.0f) {
                    limiterGain = 1.0f / mLimiterEnvelope;
                }
            }
            // Cowbell is summed after the limiter on purpose: routing it through
            // ducked the whole mix ~3.5 dB per hit with a 250 ms tail, which
            // reads as the cowbell getting quieter. The clamp below is all the
            // ceiling a ~20 ms percussive transient gets, and that is the sound.
            for (int32_t channel = 0; channel < channels; channel += 1) {
                output[channel] = floatToInt16(
                    music[channel] * limiterGain +
                    cowbellSampleForChannel(channel, channels));
            }
            advanceCowbellHits();
            applyPan(output, channels, settings);
            output += channels;
            sourceFrame += settings.rate;
            advanceDspFrame(settings);
        }
        return sourceFrame;
    }

    void prepareCowbellHitsForFrame(double sourceFrame) {
        for (CowbellHit& hit : mCowbellHits) {
            if (!hit.active && sourceFrame >= hit.targetSourceFrame) {
                hit.active = true;
                hit.sampleFrame = 0.0;
            }
        }
    }

    float cowbellSampleForChannel(int32_t outputChannel, int32_t outputChannels) const {
        float mixed = 0.0f;
        for (const CowbellHit& hit : mCowbellHits) {
            if (!hit.active) continue;
            const CowbellSample& sample = mCowbellSamples[static_cast<size_t>(hit.sampleIndex)];
            const int64_t sampleFrames = sample.frameCount();
            if (sampleFrames <= 0 || hit.sampleFrame >= static_cast<double>(sampleFrames)) {
                continue;
            }
            const int64_t frame0 = static_cast<int64_t>(std::floor(hit.sampleFrame));
            const int64_t frame1 = std::min<int64_t>(frame0 + 1, sampleFrames - 1);
            const float frac = static_cast<float>(hit.sampleFrame - static_cast<double>(frame0));
            const int32_t sampleChannel = resolveCowbellSampleChannel(
                sample.channelCount,
                outputChannel,
                outputChannels);
            const size_t offset0 =
                static_cast<size_t>(frame0 * sample.channelCount + sampleChannel);
            const size_t offset1 =
                static_cast<size_t>(frame1 * sample.channelCount + sampleChannel);
            const float s0 = static_cast<float>(sample.data[offset0]) / 32768.0f;
            const float s1 = static_cast<float>(sample.data[offset1]) / 32768.0f;
            const float value = s0 + (s1 - s0) * frac;
            mixed += value * cowbellVolumeForChannel(hit, outputChannel, outputChannels);
        }
        return mixed;
    }

    int32_t resolveCowbellSampleChannel(
        int32_t sampleChannels,
        int32_t outputChannel,
        int32_t outputChannels) const {
        if (sampleChannels <= 1) return 0;
        if (outputChannels <= 1) {
            return 0;
        }
        return std::clamp(outputChannel, 0, sampleChannels - 1);
    }

    float cowbellVolumeForChannel(
        const CowbellHit& hit,
        int32_t outputChannel,
        int32_t outputChannels) const {
        if (outputChannels <= 1) {
            return (hit.leftVolume + hit.rightVolume) * 0.5f;
        }
        return outputChannel == 0 ? hit.leftVolume : hit.rightVolume;
    }

    void advanceCowbellHits() {
        for (CowbellHit& hit : mCowbellHits) {
            if (!hit.active) continue;
            const CowbellSample& sample = mCowbellSamples[static_cast<size_t>(hit.sampleIndex)];
            hit.sampleFrame += static_cast<double>(sample.sampleRate) /
                               static_cast<double>(mSampleRate);
        }
        mCowbellHits.erase(
            std::remove_if(
                mCowbellHits.begin(),
                mCowbellHits.end(),
                [this](const CowbellHit& hit) {
                    if (!hit.active) return false;
                    const CowbellSample& sample =
                        mCowbellSamples[static_cast<size_t>(hit.sampleIndex)];
                    return hit.sampleFrame >= static_cast<double>(sample.frameCount());
                }),
            mCowbellHits.end());
    }

    float nextDuckingVolume() {
        const float target = mDuckingTargetVolume.load();
        mCurrentDuckingVolume += (target - mCurrentDuckingVolume) * kDuckingRampSpeed;
        return mCurrentDuckingVolume;
    }

    double applyScheduledJump(double sourceFrame) {
        if (mHasJump.load()) {
            const double jumpAt = mJumpAtSourceFrame.load();
            if (sourceFrame + kJumpFrameEpsilon >= jumpAt) {
                mHasJump.store(false);
                if (sourceFrame - jumpAt <= kMaxLateJumpFrames) {
                    const double targetFrame = mJumpToFrame.load();
                    publishJumpEvent(jumpAt, targetFrame);
                    return targetFrame;
                }
            }
        }
        if (mHasAnchorJump.load()) {
            const double anchorJumpAt = mAnchorJumpAtSourceFrame.load();
            if (sourceFrame + kJumpFrameEpsilon >= anchorJumpAt &&
                sourceFrame - anchorJumpAt <= kMaxLateJumpFrames) {
                mHasJump.store(false);
                const double targetFrame = mAnchorJumpToFrame.load();
                publishJumpEvent(anchorJumpAt, targetFrame);
                return targetFrame;
            }
        }
        return sourceFrame;
    }

    void publishJumpEvent(double sourceFrame, double targetFrame) {
        mPromotedJumpAtSourceFrame.store(sourceFrame);
        mPromotedJumpToFrame.store(targetFrame);
        mHasPromotedJump.store(true);
    }

    void clearPromotedJumpEvent() {
        mHasPromotedJump.store(false);
        mPromotedJumpAtSourceFrame.store(0.0);
        mPromotedJumpToFrame.store(0.0);
    }

    AudioModeSettings updateDspModeIfNeeded() {
        const int32_t packed = mAudioModeAndIntensity.load();
        const int32_t mode = sanitizeAudioModeCode(modeOfPacked(packed));
        const int32_t intensity = sanitizeIntensity(mode, intensityOfPacked(packed));
        if (mode == modeOfPacked(mConfiguredModeAndIntensity) &&
            intensity == intensityOfPacked(mConfiguredModeAndIntensity)) {
            return mConfiguredSettings;
        }
        const bool modeChanged = mode != modeOfPacked(mConfiguredModeAndIntensity);
        mConfiguredModeAndIntensity = packModeAndIntensity(mode, intensity);
        mConfiguredSettings = settingsForMode(mode, intensity);
        // An intensity-only change retunes the running graph in place; a full
        // reset here would audibly cut reverb tails mid-playback.
        if (modeChanged) {
            resetDspStateLocked();
        }
        if (mConfiguredSettings.highPassFrequency > 0.0f) {
            mHighPass.configure(
                BiquadFilter::Type::HighPass,
                mConfiguredSettings.highPassFrequency,
                mSampleRate);
        }
        if (mConfiguredSettings.lowPassFrequency > 0.0f) {
            mToneFilter.configure(
                mConfiguredSettings.useBandPass ? BiquadFilter::Type::BandPass
                                                 : BiquadFilter::Type::LowPass,
                mConfiguredSettings.lowPassFrequency,
                mSampleRate);
        }
        mReverb.setMix(mConfiguredSettings.cathedralReverb ? 0.0f : mConfiguredSettings.reverbMix);
        mCathedralReverb.setActive(mConfiguredSettings.cathedralReverb);
        return mConfiguredSettings;
    }

    float processDspSample(float input, int32_t channel, const AudioModeSettings& settings) {
        float sample = input;
        if (settings.bitCrush) {
            sample = quantizeEightBitSample(sample);
        }
        if (settings.highPassFrequency > 0.0f) {
            sample = mHighPass.process(sample, channel);
        }
        if (settings.lowPassFrequency > 0.0f) {
            sample = mToneFilter.process(sample, channel);
        }
        if (settings.cathedralReverb) {
            const float wet = mCathedralReverb.processWet(sample, channel);
            return sample * settings.dryMix + wet * settings.reverbMix;
        }
        if (settings.reverbMix > 0.0f) {
            sample = mReverb.process(sample, channel);
        }
        return sample;
    }

    void advanceDspFrame(const AudioModeSettings& settings) {
        if (settings.cathedralReverb) {
            mCathedralReverb.advanceFrame();
        } else {
            mReverb.advanceFrame();
        }
    }

    void applyPan(int16_t* frame, int32_t channels, const AudioModeSettings& settings) {
        if (!settings.pan || channels < 2) {
            return;
        }
        const float pan = std::sin(static_cast<float>(mPanAngle));
        const float angle = (pan + 1.0f) * static_cast<float>(kPi) * 0.25f;
        const float leftGain = std::cos(angle);
        const float rightGain = std::sin(angle);
        frame[0] = floatToInt16(static_cast<float>(frame[0]) / 32768.0f * leftGain);
        frame[1] = floatToInt16(static_cast<float>(frame[1]) / 32768.0f * rightGain);
        mPanAngle += kPanRadiansPerSecond / static_cast<double>(mSampleRate);
        if (mPanAngle > kPi * 2.0) {
            mPanAngle -= kPi * 2.0;
        }
    }

    int16_t floatToInt16(float sample) {
        return floatToInt16Sample(sample);
    }

    void resetDspState() {
        std::lock_guard<std::mutex> lock(mDspMutex);
        resetDspStateLocked();
    }

    void resetDspStateLocked() {
        mHighPass.reset();
        mToneFilter.reset();
        mReverb.reset();
        mCathedralReverb.reset();
        mPanAngle = 0.0;
        mLimiterEnvelope = 0.0f;
    }

    int32_t mSampleRate = 44100;
    int32_t mChannelCount = 2;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::string mLastStartFailure;
    std::mutex mStreamMutex;
    std::vector<int16_t> mAudioData;
    std::vector<int16_t> mEightBitAudioData;
    std::vector<CowbellSample> mCowbellSamples =
        std::vector<CowbellSample>(static_cast<size_t>(kCowbellSampleCount));
    std::vector<CowbellHit> mCowbellHits;
    std::mutex mDataMutex;
    int64_t mTotalFrames = 0;
    std::atomic<double> mReadFrame{0.0};
    std::atomic<int64_t> mAudioFrame{0};
    std::atomic<double> mJumpAtSourceFrame{0.0};
    std::atomic<double> mJumpToFrame{0.0};
    std::atomic<bool> mHasJump{false};
    std::atomic<double> mAnchorJumpAtSourceFrame{0.0};
    std::atomic<double> mAnchorJumpToFrame{0.0};
    std::atomic<bool> mHasAnchorJump{false};
    std::atomic<double> mPromotedJumpAtSourceFrame{0.0};
    std::atomic<double> mPromotedJumpToFrame{0.0};
    std::atomic<bool> mHasPromotedJump{false};
    std::atomic<bool> mIsPlaying{false};
    std::atomic<float> mGain{1.0f};
    std::atomic<float> mDuckingTargetVolume{kNormalDuckingVolume};
    float mCurrentDuckingVolume = kNormalDuckingVolume;
    std::atomic<int32_t> mAudioModeAndIntensity{
        packModeAndIntensity(static_cast<int32_t>(AudioMode::Off), kDefaultIntensity)};
    int32_t mConfiguredModeAndIntensity = -1;
    AudioModeSettings mConfiguredSettings;
    std::mutex mDspMutex;
    BiquadFilter mHighPass;
    BiquadFilter mToneFilter;
    SimpleReverb mReverb;
    CathedralReverb mCathedralReverb;
    double mPanAngle = 0.0;
    float mLimiterEnvelope = 0.0f;
    float mLimiterAttackCoeff = 0.0f;
    float mLimiterReleaseCoeff = 0.0f;
    std::vector<float> mMixScratch;
};

OboePlayer* toPlayer(jlong handle) {
    return reinterpret_cast<OboePlayer*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeCreatePlayer(
    JNIEnv*, jobject, jint sampleRate, jint channelCount) {
    auto* player = new OboePlayer(sampleRate, channelCount);
    if (!player->open()) {
        delete player;
        return 0;
    }
    return reinterpret_cast<jlong>(player);
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeLoadPcm(
    JNIEnv* env, jobject, jlong handle, jbyteArray data, jint length) {
    auto* player = toPlayer(handle);
    if (!player || !data || length <= 0) return;
    // The backing array may be larger than the valid PCM range, so honour the
    // explicit length and only copy whole 16-bit samples.
    const jsize available = std::min<jsize>(length, env->GetArrayLength(data));
    const jsize evenLength = available & ~static_cast<jsize>(1);
    if (evenLength <= 0) return;
    std::vector<int16_t> pcm(static_cast<size_t>(evenLength / 2));
    env->GetByteArrayRegion(data, 0, evenLength,
                            reinterpret_cast<jbyte*>(pcm.data()));
    player->loadPcm(std::move(pcm));
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativePlay(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (player) player->play();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeGetLastStartFailure(
    JNIEnv* env, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    const std::string reason = player ? player->lastStartFailure() : std::string();
    return env->NewStringUTF(reason.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativePause(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (player) player->pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeStop(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (player) player->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeSeek(
    JNIEnv*, jobject, jlong handle, jdouble timeSeconds) {
    auto* player = toPlayer(handle);
    if (player) player->seekSeconds(timeSeconds);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeScheduleJump(
    JNIEnv*, jobject, jlong handle, jdouble targetTime, jdouble audioStart) {
    auto* player = toPlayer(handle);
    return player && player->scheduleJump(targetTime, audioStart);
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeCancelScheduledJump(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (player) player->cancelScheduledJump();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeSetAnchorJump(
    JNIEnv*, jobject, jlong handle, jdouble targetTime, jdouble audioStart) {
    auto* player = toPlayer(handle);
    return player && player->setAnchorJump(targetTime, audioStart);
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeClearAnchorJump(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (player) player->clearAnchorJump();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeConsumeJumpEvent(
    JNIEnv* env, jobject, jlong handle, jdoubleArray event) {
    auto* player = toPlayer(handle);
    if (!player || !event || env->GetArrayLength(event) < 2) {
        return false;
    }
    double sourceStartTime = 0.0;
    double targetTime = 0.0;
    if (!player->consumeJumpEvent(&sourceStartTime, &targetTime)) {
        return false;
    }
    const jdouble values[2] = {sourceStartTime, targetTime};
    env->SetDoubleArrayRegion(event, 0, 2, values);
    return true;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeGetCurrentTime(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    return player ? player->getCurrentTimeSeconds() : 0.0;
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeGetAudioTime(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    return player ? player->getAudioTimeSeconds() : 0.0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeIsPlaying(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    return player && player->isPlaying();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeHasAudio(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    return player && player->hasAudio();
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeSetGain(
    JNIEnv*, jobject, jlong handle, jfloat gain) {
    auto* player = toPlayer(handle);
    if (player) player->setGain(gain);
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeSetDucking(
    JNIEnv*, jobject, jlong handle, jboolean active) {
    auto* player = toPlayer(handle);
    if (player) player->setDucking(active == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeSetJukeboxAudioMode(
    JNIEnv*, jobject, jlong handle, jint mode, jint intensity) {
    auto* player = toPlayer(handle);
    if (player) player->setJukeboxAudioMode(mode, intensity);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeGetPlaybackRate(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    return player ? player->getPlaybackRate() : 1.0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeCloneAudioFrom(
    JNIEnv*, jobject, jlong handle, jlong sourceHandle) {
    auto* player = toPlayer(handle);
    auto* source = toPlayer(sourceHandle);
    if (!player || !source) return JNI_FALSE;
    player->cloneAudioFrom(*source);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeLoadCowbellSample(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint sampleIndex,
    jbyteArray data,
    jint sampleRate,
    jint channelCount) {
    auto* player = toPlayer(handle);
    if (!player || !data) return;
    jsize length = env->GetArrayLength(data);
    if (length <= 0) return;
    std::vector<int16_t> pcm(static_cast<size_t>(length / 2));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(pcm.data()));
    player->loadCowbellSample(sampleIndex, std::move(pcm), sampleRate, channelCount);
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeScheduleCowbellHit(
    JNIEnv*,
    jobject,
    jlong handle,
    jint sampleIndex,
    jdouble targetTimeSeconds,
    jfloat leftVolume,
    jfloat rightVolume) {
    auto* player = toPlayer(handle);
    if (player) {
        player->scheduleCowbellHit(sampleIndex, targetTimeSeconds, leftVolume, rightVolume);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeCancelCowbellHits(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (player) player->cancelCowbellHits();
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeCancelPendingCowbellHits(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (player) player->cancelPendingCowbellHits();
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (!player) return;
    player->close();
    delete player;
}
