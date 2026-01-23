#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <vector>

#include <android/log.h>
#include <jni.h>
#include <oboe/Oboe.h>

namespace {

constexpr const char* kLogTag = "FJOboe";

class OboePlayer : public oboe::AudioStreamDataCallback {
public:
    OboePlayer(int32_t sampleRate, int32_t channelCount)
        : mSampleRate(sampleRate), mChannelCount(channelCount) {}

    bool open() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setSampleRate(mSampleRate)
            ->setChannelCount(mChannelCount)
            ->setFormat(oboe::AudioFormat::I16)
            ->setDataCallback(this);

        if (builder.openStream(mStream) != oboe::Result::OK) {
            builder.setSharingMode(oboe::SharingMode::Shared);
            if (builder.openStream(mStream) != oboe::Result::OK) {
                __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to open Oboe stream");
                return false;
            }
        }
        const int32_t burst = mStream->getFramesPerBurst();
        if (burst > 0) {
            mStream->setBufferSizeInFrames(burst);
        }
        return true;
    }

    void close() {
        if (mStream) {
            mStream->requestStop();
            mStream->close();
            mStream.reset();
        }
    }

    void loadPcm(const int16_t* data, size_t frames) {
        std::lock_guard<std::mutex> lock(mDataMutex);
        mAudioData.assign(data, data + frames * static_cast<size_t>(mChannelCount));
        mTotalFrames = static_cast<int64_t>(frames);
        mReadFrame.store(0);
        mHasJump.store(false);
    }

    void play() {
        if (mStream) {
            mStream->requestStart();
            mIsPlaying.store(true);
        }
    }

    void pause() {
        if (mStream) {
            mStream->requestPause();
            mIsPlaying.store(false);
        }
    }

    void stop() {
        if (mStream) {
            mStream->requestStop();
        }
        mReadFrame.store(0);
        mIsPlaying.store(false);
    }

    void seekSeconds(double seconds) {
        const int64_t frame = static_cast<int64_t>(seconds * static_cast<double>(mSampleRate));
        mReadFrame.store(frame < 0 ? 0 : frame);
        mHasJump.store(false);
    }

    void scheduleJump(double targetTime, double transitionTime) {
        const int64_t targetFrame =
            static_cast<int64_t>(targetTime * static_cast<double>(mSampleRate));
        const int64_t transitionFrame =
            static_cast<int64_t>(transitionTime * static_cast<double>(mSampleRate));
        mJumpToFrame.store(targetFrame < 0 ? 0 : targetFrame);
        mJumpAtFrame.store(transitionFrame < 0 ? 0 : transitionFrame);
        mHasJump.store(true);
    }

    double getCurrentTimeSeconds() const {
        const int64_t frame = mReadFrame.load();
        return static_cast<double>(frame) / static_cast<double>(mSampleRate);
    }

    bool isPlaying() const {
        return mIsPlaying.load();
    }

    int32_t getChannelCount() const {
        return mChannelCount;
    }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream*,
        void* audioData,
        int32_t numFrames) override {
        auto* output = static_cast<int16_t*>(audioData);
        int64_t currentFrame = mReadFrame.load();

        if (mHasJump.load()) {
            const int64_t jumpAt = mJumpAtFrame.load();
            if (jumpAt <= currentFrame) {
                currentFrame = mJumpToFrame.load();
                mHasJump.store(false);
            }
        }

        int32_t framesRemaining = numFrames;
        while (framesRemaining > 0) {
            int32_t chunkFrames = framesRemaining;
            if (mHasJump.load()) {
                const int64_t jumpAt = mJumpAtFrame.load();
                if (jumpAt >= currentFrame && jumpAt < currentFrame + framesRemaining) {
                    chunkFrames = static_cast<int32_t>(jumpAt - currentFrame);
                }
            }

            renderFrames(output, currentFrame, chunkFrames);
            currentFrame += chunkFrames;
            output += chunkFrames * mChannelCount;
            framesRemaining -= chunkFrames;

            if (mHasJump.load()) {
                const int64_t jumpAt = mJumpAtFrame.load();
                if (jumpAt == currentFrame) {
                    currentFrame = mJumpToFrame.load();
                    mHasJump.store(false);
                }
            }
        }

        mReadFrame.store(currentFrame);
        return oboe::DataCallbackResult::Continue;
    }

private:
    void renderFrames(int16_t* output, int64_t startFrame, int32_t frames) {
        const int64_t totalFrames = mTotalFrames;
        const int32_t channels = mChannelCount;
        int32_t framesWritten = 0;

        if (frames <= 0) return;

        std::lock_guard<std::mutex> lock(mDataMutex);
        while (framesWritten < frames) {
            if (startFrame >= totalFrames || mAudioData.empty()) {
                const int32_t remaining = (frames - framesWritten) * channels;
                std::fill(output, output + remaining, 0);
                return;
            }
            const int64_t framesAvailable = totalFrames - startFrame;
            const int32_t framesToCopy = static_cast<int32_t>(
                std::min<int64_t>(frames - framesWritten, framesAvailable));
            const size_t offset = static_cast<size_t>(startFrame * channels);
            const size_t samplesToCopy = static_cast<size_t>(framesToCopy * channels);
            std::copy(
                mAudioData.begin() + offset,
                mAudioData.begin() + offset + samplesToCopy,
                output);
            output += samplesToCopy;
            framesWritten += framesToCopy;
            startFrame += framesToCopy;
        }
    }

    int32_t mSampleRate = 44100;
    int32_t mChannelCount = 2;
    std::shared_ptr<oboe::AudioStream> mStream;
    std::vector<int16_t> mAudioData;
    std::mutex mDataMutex;
    int64_t mTotalFrames = 0;
    std::atomic<int64_t> mReadFrame{0};
    std::atomic<int64_t> mJumpAtFrame{0};
    std::atomic<int64_t> mJumpToFrame{0};
    std::atomic<bool> mHasJump{false};
    std::atomic<bool> mIsPlaying{false};
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
    const size_t frames = pcm.size() / static_cast<size_t>(player->getChannelCount());
    player->loadPcm(pcm.data(), frames);
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
    JNIEnv*, jobject, jlong handle, jdouble targetTime, jdouble transitionTime) {
    auto* player = toPlayer(handle);
    if (player) player->scheduleJump(targetTime, transitionTime);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeGetCurrentTime(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    return player ? player->getCurrentTimeSeconds() : 0.0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeIsPlaying(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    return player && player->isPlaying();
}

extern "C" JNIEXPORT void JNICALL
Java_com_foreverjukebox_app_audio_BufferedAudioPlayer_nativeRelease(
    JNIEnv*, jobject, jlong handle) {
    auto* player = toPlayer(handle);
    if (!player) return;
    player->close();
    delete player;
}
