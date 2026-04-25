#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
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
constexpr float kBiquadQ = 0.70710678f;
constexpr double kPanRadiansPerSecond = 0.42;

enum class AudioMode {
    Off = 0,
    Nightcore = 1,
    Daycore = 2,
    Vaporwave = 3,
    EightD = 4,
    Lofi = 5
};

struct AudioModeSettings {
    double rate = 1.0;
    float highPassFrequency = 0.0f;
    float lowPassFrequency = 0.0f;
    bool useBandPass = false;
    float reverbMix = 0.0f;
    bool pan = false;
};

AudioModeSettings settingsForMode(int32_t mode) {
    switch (static_cast<AudioMode>(mode)) {
        case AudioMode::Nightcore:
            return {1.2, 150.0f, 0.0f, false, 0.0f, false};
        case AudioMode::Daycore:
            return {0.8, 0.0f, 0.0f, false, 0.4f, false};
        case AudioMode::Vaporwave:
            return {0.65, 0.0f, 1000.0f, false, 0.6f, false};
        case AudioMode::EightD:
            return {1.0, 0.0f, 0.0f, false, 0.5f, true};
        case AudioMode::Lofi:
            return {1.0, 0.0f, 2000.0f, true, 0.1f, false};
        case AudioMode::Off:
        default:
            return {};
    }
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
        const float alpha = sinOmega / (2.0f * kBiquadQ);

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

class OboePlayer : public oboe::AudioStreamDataCallback,
                   public oboe::AudioStreamErrorCallback {
public:
    OboePlayer(int32_t sampleRate, int32_t channelCount)
        : mSampleRate(sampleRate),
          mChannelCount(channelCount),
          mHighPass(channelCount),
          mToneFilter(channelCount),
          mReverb(sampleRate, channelCount) {}

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
            closeLocked();
            if (ensureStreamLocked()) {
                result = mStream->requestStart();
            }
        }
        mIsPlaying.store(result == oboe::Result::OK);
    }

    void pause() {
        std::lock_guard<std::mutex> lock(mStreamMutex);
        if (mStream) {
            auto result = mStream->requestPause();
            if (result != oboe::Result::OK) {
                closeLocked();
            }
        }
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
        resetDspState();
        mIsPlaying.store(false);
    }

    void loadPcm(std::vector<int16_t>&& data) {
        {
            std::lock_guard<std::mutex> lock(mDataMutex);
            mAudioData = std::move(data);
            mTotalFrames =
                static_cast<int64_t>(mAudioData.size() / static_cast<size_t>(mChannelCount));
        }
        mReadFrame.store(0.0);
        mAudioFrame.store(0);
        mHasJump.store(false);
        resetDspState();
    }

    void setGain(float gain) {
        mGain.store(std::clamp(gain, 0.0f, 1.0f));
    }

    void setJukeboxAudioMode(int32_t mode) {
        mAudioMode.store(std::clamp<int32_t>(mode, 0, 5));
    }

    double getPlaybackRate() const {
        return settingsForMode(mAudioMode.load()).rate;
    }

    void cloneAudioFrom(OboePlayer& source) {
        if (this == &source) return;
        {
            std::scoped_lock lock(mDataMutex, source.mDataMutex);
            mAudioData = source.mAudioData;
            mTotalFrames = source.mTotalFrames;
        }
        mReadFrame.store(0.0);
        mAudioFrame.store(0);
        mJumpAtAudioFrame.store(0);
        mJumpToFrame.store(0.0);
        mHasJump.store(false);
        resetDspState();
    }

    void seekSeconds(double seconds) {
        const int64_t frame = static_cast<int64_t>(seconds * static_cast<double>(mSampleRate));
        mReadFrame.store(frame < 0 ? 0.0 : static_cast<double>(frame));
        mHasJump.store(false);
        resetDspState();
    }

    void scheduleJump(double targetTime, double audioStartTime) {
        const int64_t targetFrame =
            static_cast<int64_t>(targetTime * static_cast<double>(mSampleRate));
        const int64_t transitionFrame =
            static_cast<int64_t>(audioStartTime * static_cast<double>(mSampleRate));
        mJumpToFrame.store(targetFrame < 0 ? 0.0 : static_cast<double>(targetFrame));
        mJumpAtAudioFrame.store(transitionFrame < 0 ? 0 : transitionFrame);
        mHasJump.store(true);
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

        if (mHasJump.load()) {
            const int64_t jumpAt = mJumpAtAudioFrame.load();
            if (jumpAt <= audioFrame) {
                currentFrame = mJumpToFrame.load();
                mHasJump.store(false);
            }
        }

        int32_t framesRemaining = numFrames;
        while (framesRemaining > 0) {
            int32_t chunkFrames = framesRemaining;
            if (mHasJump.load()) {
                const int64_t jumpAt = mJumpAtAudioFrame.load();
                if (jumpAt >= audioFrame && jumpAt < audioFrame + framesRemaining) {
                    chunkFrames = static_cast<int32_t>(jumpAt - audioFrame);
                }
            }

            currentFrame = renderFrames(output, currentFrame, chunkFrames);
            audioFrame += chunkFrames;
            output += chunkFrames * mChannelCount;
            framesRemaining -= chunkFrames;

            if (mHasJump.load()) {
                const int64_t jumpAt = mJumpAtAudioFrame.load();
                if (jumpAt == audioFrame) {
                    currentFrame = mJumpToFrame.load();
                    mHasJump.store(false);
                }
            }
        }

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
    bool openLocked() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Shared)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setPackageName(kPackageName)
            ->setAttributionTag(kPlaybackAttributionTag)
            ->setSampleRate(mSampleRate)
            ->setChannelCount(mChannelCount)
            ->setFormat(oboe::AudioFormat::I16)
            ->setDataCallback(this)
            ->setErrorCallback(this);

        if (builder.openStream(mStream) != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to open Oboe stream");
            return false;
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
        double sourceFrame = startFrame;

        if (frames <= 0) return sourceFrame;

        std::lock_guard<std::mutex> lock(mDataMutex);
        const int64_t totalFrames = mTotalFrames;
        for (int32_t frame = 0; frame < frames; frame += 1) {
            if (sourceFrame >= static_cast<double>(totalFrames) || mAudioData.empty()) {
                std::fill(output, output + channels, 0);
                output += channels;
                sourceFrame += settings.rate;
                mReverb.advanceFrame();
                continue;
            }

            const int64_t frame0 = static_cast<int64_t>(std::floor(sourceFrame));
            const int64_t frame1 = std::min<int64_t>(frame0 + 1, totalFrames - 1);
            const float frac = static_cast<float>(sourceFrame - static_cast<double>(frame0));
            for (int32_t channel = 0; channel < channels; channel += 1) {
                const size_t offset0 = static_cast<size_t>(frame0 * channels + channel);
                const size_t offset1 = static_cast<size_t>(frame1 * channels + channel);
                const float s0 = static_cast<float>(mAudioData[offset0]) / 32768.0f;
                const float s1 = static_cast<float>(mAudioData[offset1]) / 32768.0f;
                float sample = s0 + (s1 - s0) * frac;
                sample = processDspSample(sample, channel, settings);
                output[channel] = floatToInt16(sample * mGain.load());
            }
            applyPan(output, channels, settings);
            output += channels;
            sourceFrame += settings.rate;
            mReverb.advanceFrame();
        }
        return sourceFrame;
    }

    AudioModeSettings updateDspModeIfNeeded() {
        const int32_t mode = mAudioMode.load();
        if (mode == mConfiguredAudioMode) {
            return mConfiguredSettings;
        }
        mConfiguredAudioMode = mode;
        mConfiguredSettings = settingsForMode(mode);
        resetDspStateLocked();
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
        mReverb.setMix(mConfiguredSettings.reverbMix);
        return mConfiguredSettings;
    }

    float processDspSample(float input, int32_t channel, const AudioModeSettings& settings) {
        float sample = input;
        if (settings.highPassFrequency > 0.0f) {
            sample = mHighPass.process(sample, channel);
        }
        if (settings.lowPassFrequency > 0.0f) {
            sample = mToneFilter.process(sample, channel);
        }
        if (settings.reverbMix > 0.0f) {
            sample = mReverb.process(sample, channel);
        }
        return sample;
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
        const float clamped = std::clamp(sample, -1.0f, 0.9999695f);
        return static_cast<int16_t>(std::lrint(clamped * 32768.0f));
    }

    void resetDspState() {
        std::lock_guard<std::mutex> lock(mDspMutex);
        resetDspStateLocked();
    }

    void resetDspStateLocked() {
        mHighPass.reset();
        mToneFilter.reset();
        mReverb.reset();
        mPanAngle = 0.0;
    }

    int32_t mSampleRate = 44100;
    int32_t mChannelCount = 2;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::mutex mStreamMutex;
    std::vector<int16_t> mAudioData;
    std::mutex mDataMutex;
    int64_t mTotalFrames = 0;
    std::atomic<double> mReadFrame{0.0};
    std::atomic<int64_t> mAudioFrame{0};
    std::atomic<int64_t> mJumpAtAudioFrame{0};
    std::atomic<double> mJumpToFrame{0.0};
    std::atomic<bool> mHasJump{false};
    std::atomic<bool> mIsPlaying{false};
    std::atomic<float> mGain{1.0f};
    std::atomic<int32_t> mAudioMode{0};
    int32_t mConfiguredAudioMode = -1;
    AudioModeSettings mConfiguredSettings;
    std::mutex mDspMutex;
    BiquadFilter mHighPass;
    BiquadFilter mToneFilter;
    SimpleReverb mReverb;
    double mPanAngle = 0.0;
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
    JNIEnv* env, jobject, jlong handle, jbyteArray data) {
    auto* player = toPlayer(handle);
    if (!player || !data) return;
    jsize length = env->GetArrayLength(data);
    if (length <= 0) return;
    std::vector<int16_t> pcm(static_cast<size_t>(length / 2));
    env->GetByteArrayRegion(data, 0, length,
                            reinterpret_cast<jbyte*>(pcm.data()));
    player->loadPcm(std::move(pcm));
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativePlay(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (player) player->play();
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

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeScheduleJump(
    JNIEnv*, jobject, jlong handle, jdouble targetTime, jdouble audioStart) {
    auto* player = toPlayer(handle);
    if (player) player->scheduleJump(targetTime, audioStart);
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
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeSetJukeboxAudioMode(
    JNIEnv*, jobject, jlong handle, jint mode) {
    auto* player = toPlayer(handle);
    if (player) player->setJukeboxAudioMode(mode);
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
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (!player) return;
    player->close();
    delete player;
}
