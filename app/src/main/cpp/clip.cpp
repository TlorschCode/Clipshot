#include <oboe/Oboe.h>
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <array>
#include <cstdint>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NativeAudio", __VA_ARGS__)

constexpr int SAMPLE_RATE = 44100;
constexpr int CHANNELS = 1;
constexpr int DURATION_SECONDS = 30;
constexpr int BUFFER_SIZE = SAMPLE_RATE * CHANNELS * DURATION_SECONDS;

static std::shared_ptr<oboe::AudioStream> gStream;
static std::atomic<bool> gRecordingActive{false};
static std::atomic<int64_t> gLastCallbackNs{0};

// high-resolution timestamp
static inline int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL + ts.tv_nsec;
}

// Thread-safe circular buffer for input audio
class InputCallback : public oboe::AudioStreamCallback {
public:
    InputCallback() : mWriteIndex(0) {
        mBuffer.fill(0);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*,
                                          void* audioData,
                                          int32_t numFrames) override {
        if (!audioData) return oboe::DataCallbackResult::Continue;

        gRecordingActive.store(true, std::memory_order_relaxed);
        gLastCallbackNs.store(nowNs(), std::memory_order_relaxed);

        int16_t* input = static_cast<int16_t*>(audioData);
        int samples = numFrames * CHANNELS;

        int write = mWriteIndex.load(std::memory_order_relaxed);

        // safe circular buffer write
        for (int i = 0; i < samples; ++i) {
            mBuffer[write] = input[i];
            write = (write + 1) % BUFFER_SIZE;
        }

        mWriteIndex.store(write, std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }

    // Copy the last 'frames' frames into 'out'. Thread-safe.
    void copySnapshot(int16_t* out, int frames) {
        if (!out || frames <= 0) return;

        int totalSamples = std::min(frames * CHANNELS, BUFFER_SIZE);

        int write = mWriteIndex.load(std::memory_order_acquire);
        int start = (write - totalSamples + BUFFER_SIZE) % BUFFER_SIZE;

        for (int i = 0; i < totalSamples; ++i) {
            out[i] = mBuffer[(start + i) % BUFFER_SIZE];
        }
    }

private:
    std::array<int16_t, BUFFER_SIZE> mBuffer;
    std::atomic<int> mWriteIndex;
};

static InputCallback gCallback;

// --- JNI functions ---

extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio_start(JNIEnv*, jobject) {
    if (gStream) return; // already running

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(CHANNELS)
            ->setSampleRate(SAMPLE_RATE)
            ->setCallback(&gCallback);

    oboe::Result r = builder.openStream(gStream);
    if (r != oboe::Result::OK || !gStream) {
        LOGI("Failed to open stream: %d", r);
        return;
    }

    gStream->requestStart();
    LOGI("Audio stream started");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio_stop(JNIEnv*, jobject) {
    if (!gStream) return;

    gStream->requestStop();
    gStream->close();
    gStream.reset();

    gRecordingActive.store(false, std::memory_order_relaxed);
    LOGI("Audio stream stopped");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio_copySnapshot(JNIEnv* env, jobject, jbyteArray outArray) {
    if (!outArray) return;

    jsize len = env->GetArrayLength(outArray);
    jbyte* buffer = env->GetByteArrayElements(outArray, nullptr);
    if (!buffer) return;

    int frames = len / 2; // 2 bytes per sample (PCM16)
    std::vector<int16_t> temp(frames);

    gCallback.copySnapshot(temp.data(), frames);

    for (int i = 0; i < frames; ++i) {
        int16_t sample = temp[i];
        buffer[2*i]     = sample & 0xFF;
        buffer[2*i + 1] = (sample >> 8) & 0xFF;
    }

    env->ReleaseByteArrayElements(outArray, buffer, 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rylry_clip_NativeAudio_isRecordingActive(JNIEnv*, jobject) {
    if (!gRecordingActive.load(std::memory_order_relaxed)) return JNI_FALSE;

    constexpr int64_t TIMEOUT_NS = 2'000'000'000LL; // 2 seconds
    int64_t last = gLastCallbackNs.load(std::memory_order_relaxed);
    int64_t now  = nowNs();

    return (now - last) < TIMEOUT_NS ? JNI_TRUE : JNI_FALSE;
}
