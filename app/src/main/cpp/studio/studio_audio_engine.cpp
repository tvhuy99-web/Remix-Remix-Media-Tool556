#include <jni.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>

namespace {

class StudioAudioEngine final : public oboe::AudioStreamDataCallback,
                                public oboe::AudioStreamErrorCallback {
public:
    ~StudioAudioEngine() override {
        close();
    }

    oboe::Result openOutput(int32_t preferredDeviceId) {
        close();
        callbackFrames_.store(0);
        disconnectCount_.store(0);

        auto result = openOutputWithSharingMode(preferredDeviceId, oboe::SharingMode::Exclusive);
        if (result != oboe::Result::OK) {
            outputStream_.reset();
            result = openOutputWithSharingMode(preferredDeviceId, oboe::SharingMode::Shared);
        }
        return result;
    }

    oboe::Result start() {
        if (!outputStream_) return oboe::Result::ErrorInvalidState;
        const auto result = outputStream_->requestStart();
        if (result == oboe::Result::OK) running_.store(true);
        return result;
    }

    oboe::Result stop() {
        running_.store(false);
        if (!outputStream_) return oboe::Result::OK;
        return outputStream_->requestStop();
    }

    void close() {
        running_.store(false);
        auto stream = outputStream_;
        outputStream_.reset();
        if (stream) {
            stream->requestStop();
            stream->close();
        }
    }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override {
        if (!running_.load()) {
            std::memset(audioData, 0, static_cast<size_t>(numFrames) * audioStream->getBytesPerFrame());
            return oboe::DataCallbackResult::Continue;
        }

        // Step 2 intentionally renders silence. Beat playback and duplex capture
        // are layered on this clock in the Studio Recording step.
        std::memset(audioData, 0, static_cast<size_t>(numFrames) * audioStream->getBytesPerFrame());
        callbackFrames_.fetch_add(numFrames);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        running_.store(false);
        if (error == oboe::Result::ErrorDisconnected) {
            disconnectCount_.fetch_add(1);
        }
    }

    jlongArray diagnostics(JNIEnv* env) const {
        constexpr jsize kFieldCount = 11;
        jlong values[kFieldCount] = {};

        auto stream = outputStream_;
        if (stream) {
            values[0] = stream->getSampleRate();
            values[1] = stream->getChannelCount();
            values[2] = stream->getFramesPerBurst();
            values[3] = stream->getBufferSizeInFrames();
            values[4] = stream->getBufferCapacityInFrames();
            values[5] = stream->getDeviceId();
            values[6] = static_cast<int32_t>(stream->getAudioApi());
            values[7] = static_cast<int32_t>(stream->getSharingMode());
            values[8] = static_cast<int32_t>(stream->getPerformanceMode());
        }
        values[9] = callbackFrames_.load();
        values[10] = disconnectCount_.load();

        auto array = env->NewLongArray(kFieldCount);
        if (array != nullptr) {
            env->SetLongArrayRegion(array, 0, kFieldCount, values);
        }
        return array;
    }

private:
    oboe::Result openOutputWithSharingMode(
        int32_t preferredDeviceId,
        oboe::SharingMode sharingMode
    ) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(sharingMode);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(2);
        builder.setUsage(oboe::Usage::Media);
        builder.setContentType(oboe::ContentType::Music);
        builder.setChannelConversionAllowed(true);
        builder.setFormatConversionAllowed(true);
        builder.setDataCallback(this);
        builder.setErrorCallback(this);
        if (preferredDeviceId >= 0) {
            builder.setDeviceId(preferredDeviceId);
        }

        const auto result = builder.openStream(outputStream_);
        if (result != oboe::Result::OK || !outputStream_) return result;

        const auto framesPerBurst = outputStream_->getFramesPerBurst();
        if (framesPerBurst > 0) {
            outputStream_->setBufferSizeInFrames(framesPerBurst * 2);
        }
        return oboe::Result::OK;
    }

    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::atomic<bool> running_{false};
    std::atomic<int64_t> callbackFrames_{0};
    std::atomic<int64_t> disconnectCount_{0};
};

StudioAudioEngine* fromHandle(jlong handle) {
    return reinterpret_cast<StudioAudioEngine*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeCreate(
    JNIEnv*,
    jobject
) {
    return reinterpret_cast<jlong>(new StudioAudioEngine());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeOpenOutput(
    JNIEnv*,
    jobject,
    jlong handle,
    jint preferredDeviceId
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    return static_cast<jint>(engine->openOutput(preferredDeviceId));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeStart(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    return static_cast<jint>(engine->start());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeStop(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    return static_cast<jint>(engine->stop());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeClose(
    JNIEnv*,
    jobject,
    jlong handle
) {
    if (auto* engine = fromHandle(handle)) engine->close();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeDiagnostics(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return env->NewLongArray(0);
    return engine->diagnostics(env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeRelease(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete fromHandle(handle);
}
