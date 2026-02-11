#include <oboe/Oboe.h>
#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <vector>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NativeAudio", __VA_ARGS__)
constexpr uint16_t SAMPLE_RATE = 44100;
constexpr uint8_t CHANNELS = 1;
constexpr uint8_t DURATION_SECONDS = 30;
constexpr uint64_t NANOS_PER_SEC = 1000000000ULL;

static std::shared_ptr<oboe::AudioStream> stream;
static std::atomic<bool> gRecordingActive{false};
static std::atomic<uint64_t> gLastCallbackNs{0};

static inline int64_t nowNs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * NANOS_PER_SEC + ts.tv_nsec;
}

class InputCallback : public oboe::AudioStreamCallback {
public:
    InputCallback()
            : mBuffer(SAMPLE_RATE * DURATION_SECONDS * CHANNELS),
              mWriteIndex(0) {}

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream*,
            void* audioData,
            int32_t numFrames) override {

        gRecordingActive.store(true, std::memory_order_relaxed);
        gLastCallbackNs.store(
                nowNs(),
                std::memory_order_relaxed
        );

        int16_t* input = static_cast<int16_t*>(audioData);
        int samples = numFrames * CHANNELS;

        int write = mWriteIndex.load(std::memory_order_relaxed);
        for (int i = 0; i < samples; ++i) {
            mBuffer[write] = input[i];
            write = (write + 1) % mBuffer.size();
        }
        mWriteIndex.store(write, std::memory_order_release);

        return oboe::DataCallbackResult::Continue;
    }

    // Copies the last 'frames' into 'out'
    void copySnapshot(int16_t* out, int frames) {
        int samples = frames * CHANNELS;
        int write = mWriteIndex.load(std::memory_order_acquire);
        int start = write - samples;
        if (start < 0) start += mBuffer.size();

        for (int i = 0; i < samples; ++i) {
            out[i] = mBuffer[(start + i) % mBuffer.size()];
        }
    }

    std::atomic<int> mWriteIndex;
    std::vector<int16_t> mBuffer;
};

static InputCallback callback;

extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio_start(JNIEnv*, jobject) {

    gRecordingActive.store(false, std::memory_order_relaxed);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(CHANNELS)
            ->setSampleRate(SAMPLE_RATE)
            ->setCallback(&callback);

    oboe::Result r = builder.openStream(stream);
    if (r != oboe::Result::OK || !stream) {
        LOGI("Failed to open stream: %d", r);
        return;
    }

    stream->requestStart();
    LOGI("Stream started");
}

extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio_stop(JNIEnv*, jobject) {
    gRecordingActive.store(false, std::memory_order_relaxed);

    if (stream) {
        stream->requestStop();
        stream->close();
        stream.reset();
        LOGI("Stream stopped");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_rylry_clip_NativeAudio_isRecordingActive(JNIEnv*, jobject) {

    if (!gRecordingActive.load(std::memory_order_relaxed)) {
        return JNI_FALSE;
    }

    uint64_t lastNs = gLastCallbackNs.load(std::memory_order_relaxed);

    // No callbacks in the last 2 seconds → dead
    constexpr uint64_t TIMEOUT_NS = NANOS_PER_SEC * 2;

    return (nowNs() - lastNs) < TIMEOUT_NS ? JNI_TRUE : JNI_FALSE;
}


// JNI function to copy the last 30 seconds into a Java short array
extern "C" JNIEXPORT void JNICALL
Java_dev_rylry_clip_NativeAudio_copySnapshot(JNIEnv* env, jobject, jbyteArray outArray) {
    if (!outArray) return;

    jsize len = env->GetArrayLength(outArray);
    jbyte* buffer = env->GetByteArrayElements(outArray, nullptr);
    if (!buffer) return;

    size_t frames = len / 2; // 2 bytes per frame (mono PCM16)
    size_t write = callback.mWriteIndex.load(std::memory_order_acquire);
    int totalSamples = frames; // 1 sample per frame for mono

    size_t start = write - totalSamples;
    if (start < 0) start += callback.mBuffer.size();

    for (size_t i = 0; i < totalSamples; ++i) {
        int16_t sample = callback.mBuffer[(start + i) % callback.mBuffer.size()];
        buffer[2*i]     = static_cast<jbyte>(sample & 0xFF);
        buffer[2*i + 1] = static_cast<jbyte>((sample >> 8) & 0xFF);
    }

    env->ReleaseByteArrayElements(outArray, buffer, 0);
}
