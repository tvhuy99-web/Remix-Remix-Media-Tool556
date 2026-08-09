#include <jni.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <thread>
#include <vector>

namespace {

constexpr int64_t kCalibrationTimeoutMs = 4'000;
constexpr int32_t kErrorOpenOutput = -20'001;
constexpr int32_t kErrorOpenInput = -20'002;
constexpr int32_t kErrorStart = -20'003;
constexpr int32_t kErrorTimeout = -20'004;
constexpr int32_t kErrorSignal = -20'005;
constexpr int32_t kErrorRateMismatch = -20'006;

struct CalibrationResult {
    int32_t status = kErrorSignal;
    int64_t latencyFrames = 0;
    int32_t sampleRate = 0;
    int32_t outputDeviceId = -1;
    int32_t inputDeviceId = -1;
    int32_t confidenceMilli = 0;
};

class LatencyCalibrator final : public oboe::AudioStreamDataCallback,
                                public oboe::AudioStreamErrorCallback {
public:
    CalibrationResult measure(int32_t preferredInputDeviceId, int32_t preferredOutputDeviceId, int32_t inputMode) {
        CalibrationResult result;
        auto openResult = openOutput(preferredOutputDeviceId);
        if (openResult != oboe::Result::OK || !output_) {
            result.status = kErrorOpenOutput;
            close();
            return result;
        }
        sampleRate_ = output_->getSampleRate();
        result.sampleRate = sampleRate_;
        result.outputDeviceId = output_->getDeviceId();

        openResult = openInput(preferredInputDeviceId, inputMode);
        if (openResult != oboe::Result::OK || !input_) {
            result.status = kErrorOpenInput;
            close();
            return result;
        }
        result.inputDeviceId = input_->getDeviceId();
        if (input_->getSampleRate() != sampleRate_) {
            result.status = kErrorRateMismatch;
            close();
            return result;
        }

        const int32_t maxCallback = std::max<int32_t>(
            8'192,
            std::max(output_->getBufferCapacityInFrames(), input_->getBufferCapacityInFrames()) * 2
        );
        inputScratch_.assign(static_cast<size_t>(maxCallback), 0.0f);
        totalFrames_ = std::max<int64_t>(sampleRate_ * 2LL, 1LL);
        capture_.assign(static_cast<size_t>(totalFrames_), 0.0f);
        cursor_.store(0);
        completed_.store(false);
        disconnected_.store(false);

        if (input_->requestStart() != oboe::Result::OK || output_->requestStart() != oboe::Result::OK) {
            result.status = kErrorStart;
            close();
            return result;
        }

        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(kCalibrationTimeoutMs);
        while (!completed_.load() && !disconnected_.load() && std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
        }
        output_->requestStop();
        input_->requestStop();

        if (!completed_.load() || disconnected_.load()) {
            result.status = kErrorTimeout;
            close();
            return result;
        }

        const auto estimate = estimateLatency();
        if (estimate.first < 0) {
            result.status = kErrorSignal;
        } else {
            result.status = 0;
            result.latencyFrames = estimate.first;
            result.confidenceMilli = estimate.second;
        }
        close();
        return result;
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override {
        if (audioData == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;
        const int32_t bytesPerFrame = std::max(0, stream->getBytesPerFrame());
        if (completed_.load() || disconnected_.load()) {
            if (bytesPerFrame > 0) {
                std::memset(audioData, 0, static_cast<size_t>(numFrames) * static_cast<size_t>(bytesPerFrame));
            }
            return oboe::DataCallbackResult::Continue;
        }
        auto* output = static_cast<float*>(audioData);
        const int32_t channels = std::max(1, stream->getChannelCount());
        const int32_t frames = std::min<int32_t>(numFrames, static_cast<int32_t>(inputScratch_.size()));

        int32_t framesRead = 0;
        if (input_ && frames > 0) {
            auto read = input_->read(inputScratch_.data(), frames, 0);
            if (read == oboe::Result::OK) framesRead = std::max(0, std::min(frames, read.value()));
            else if (read == oboe::Result::ErrorDisconnected) disconnected_.store(true);
        }

        const int64_t start = cursor_.load();
        for (int32_t i = 0; i < numFrames; ++i) {
            const int64_t globalFrame = start + i;
            const float reference = referenceAt(globalFrame);
            const size_t base = static_cast<size_t>(i) * static_cast<size_t>(channels);
            for (int32_t channel = 0; channel < channels; ++channel) output[base + channel] = reference;
            if (globalFrame < totalFrames_) {
                capture_[static_cast<size_t>(globalFrame)] = i < framesRead ? inputScratch_[static_cast<size_t>(i)] : 0.0f;
            }
        }
        const int64_t next = start + numFrames;
        cursor_.store(next);
        if (next >= totalFrames_) completed_.store(true);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        if (error == oboe::Result::ErrorDisconnected) disconnected_.store(true);
    }

private:
    oboe::Result openOutput(int32_t deviceId) {
        auto result = openOutputWithMode(deviceId, oboe::SharingMode::Exclusive);
        if (result != oboe::Result::OK) {
            output_.reset();
            result = openOutputWithMode(deviceId, oboe::SharingMode::Shared);
        }
        return result;
    }

    oboe::Result openOutputWithMode(int32_t deviceId, oboe::SharingMode sharingMode) {
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
        if (deviceId >= 0) builder.setDeviceId(deviceId);
        auto result = builder.openStream(output_);
        if (result == oboe::Result::OK && output_) {
            const auto burst = output_->getFramesPerBurst();
            if (burst > 0) output_->setBufferSizeInFrames(burst * 2);
        }
        return result;
    }

    oboe::Result openInput(int32_t deviceId, int32_t inputMode) {
        const auto presets = presetsForMode(inputMode);
        oboe::Result last = oboe::Result::ErrorInternal;
        for (const auto preset : presets) {
            last = openInputWithMode(deviceId, preset, oboe::SharingMode::Exclusive);
            if (last != oboe::Result::OK) {
                input_.reset();
                last = openInputWithMode(deviceId, preset, oboe::SharingMode::Shared);
            }
            if (last == oboe::Result::OK && input_) return last;
            input_.reset();
        }
        return last;
    }

    oboe::Result openInputWithMode(int32_t deviceId, oboe::InputPreset preset, oboe::SharingMode sharingMode) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(sharingMode);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(1);
        builder.setSampleRate(sampleRate_);
        builder.setInputPreset(preset);
        builder.setChannelConversionAllowed(true);
        builder.setFormatConversionAllowed(true);
        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        builder.setErrorCallback(this);
        if (deviceId >= 0) builder.setDeviceId(deviceId);
        auto result = builder.openStream(input_);
        if (result == oboe::Result::OK && input_) {
            const auto burst = input_->getFramesPerBurst();
            if (burst > 0) input_->setBufferSizeInFrames(burst * 2);
        }
        return result;
    }

    std::vector<oboe::InputPreset> presetsForMode(int32_t inputMode) const {
        if (inputMode == 1) return {oboe::InputPreset::Unprocessed, oboe::InputPreset::Generic};
        if (inputMode == 2) {
            return {oboe::InputPreset::VoicePerformance, oboe::InputPreset::VoiceRecognition, oboe::InputPreset::Generic};
        }
        return {
            oboe::InputPreset::Unprocessed,
            oboe::InputPreset::VoicePerformance,
            oboe::InputPreset::VoiceRecognition,
            oboe::InputPreset::Generic,
        };
    }

    float referenceAt(int64_t frame) const {
        if (sampleRate_ <= 0) return 0.0f;
        static constexpr double pulseSeconds[] = {0.250, 0.301, 0.389, 0.527, 0.733};
        static constexpr float signs[] = {1.0f, -0.85f, 0.72f, -0.92f, 0.78f};
        constexpr int32_t pulseLength = 96;
        for (size_t pulse = 0; pulse < 5; ++pulse) {
            const int64_t start = static_cast<int64_t>(std::llround(pulseSeconds[pulse] * sampleRate_));
            const int64_t offset = frame - start;
            if (offset >= 0 && offset < pulseLength) {
                const double phase = 2.0 * 3.14159265358979323846 * 2'100.0 * static_cast<double>(offset) /
                    static_cast<double>(sampleRate_);
                const float envelope = static_cast<float>(1.0 - static_cast<double>(offset) / pulseLength);
                return 0.28f * signs[pulse] * envelope * static_cast<float>(std::sin(phase));
            }
        }
        return 0.0f;
    }

    std::pair<int64_t, int32_t> estimateLatency() const {
        if (sampleRate_ <= 0 || capture_.empty()) return {-1, 0};
        const int64_t minLag = sampleRate_ / 200;
        const int64_t maxLag = std::min<int64_t>(sampleRate_, totalFrames_ / 2);
        constexpr int32_t pulseLength = 96;
        static constexpr double pulseSeconds[] = {0.250, 0.301, 0.389, 0.527, 0.733};

        double referenceEnergy = 0.0;
        for (size_t pulse = 0; pulse < 5; ++pulse) {
            for (int32_t i = 0; i < pulseLength; ++i) {
                const int64_t frame = static_cast<int64_t>(std::llround(pulseSeconds[pulse] * sampleRate_)) + i;
                const float value = referenceAt(frame);
                referenceEnergy += static_cast<double>(value) * value;
            }
        }
        if (referenceEnergy <= 0.0) return {-1, 0};

        double bestScore = 0.0;
        int64_t bestLag = -1;
        for (int64_t lag = minLag; lag <= maxLag; ++lag) {
            double correlation = 0.0;
            double captureEnergy = 0.0;
            bool valid = true;
            for (size_t pulse = 0; pulse < 5 && valid; ++pulse) {
                const int64_t pulseStart = static_cast<int64_t>(std::llround(pulseSeconds[pulse] * sampleRate_));
                for (int32_t i = 0; i < pulseLength; ++i) {
                    const int64_t referenceFrame = pulseStart + i;
                    const int64_t captureFrame = referenceFrame + lag;
                    if (captureFrame < 0 || captureFrame >= static_cast<int64_t>(capture_.size())) {
                        valid = false;
                        break;
                    }
                    const double reference = static_cast<double>(referenceAt(referenceFrame));
                    const double captured = static_cast<double>(capture_[static_cast<size_t>(captureFrame)]);
                    correlation += reference * captured;
                    captureEnergy += captured * captured;
                }
            }
            if (!valid || captureEnergy <= 1e-10) continue;
            const double score = std::abs(correlation) / std::sqrt(referenceEnergy * captureEnergy);
            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }
        if (bestLag < 0 || bestScore < 0.18) return {-1, static_cast<int32_t>(bestScore * 1'000.0)};
        return {bestLag, static_cast<int32_t>(std::min(1.0, bestScore) * 1'000.0)};
    }

    void close() {
        if (output_) {
            output_->requestStop();
            output_->close();
            output_.reset();
        }
        if (input_) {
            input_->requestStop();
            input_->close();
            input_.reset();
        }
    }

    std::shared_ptr<oboe::AudioStream> output_;
    std::shared_ptr<oboe::AudioStream> input_;
    std::vector<float> inputScratch_;
    std::vector<float> capture_;
    int32_t sampleRate_ = 0;
    int64_t totalFrames_ = 0;
    std::atomic<int64_t> cursor_{0};
    std::atomic<bool> completed_{false};
    std::atomic<bool> disconnected_{false};
};

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioLatencyNative_nativeMeasure(
    JNIEnv* env,
    jobject,
    jint preferredInputDeviceId,
    jint preferredOutputDeviceId,
    jint inputMode
) {
    LatencyCalibrator calibrator;
    const auto result = calibrator.measure(preferredInputDeviceId, preferredOutputDeviceId, inputMode);
    jlong values[6] = {
        result.status,
        result.latencyFrames,
        result.sampleRate,
        result.outputDeviceId,
        result.inputDeviceId,
        result.confidenceMilli,
    };
    auto array = env->NewLongArray(6);
    if (array != nullptr) env->SetLongArrayRegion(array, 0, 6, values);
    return array;
}