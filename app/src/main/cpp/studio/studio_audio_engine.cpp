#include <jni.h>
#include <oboe/Oboe.h>

#include "studio_arrangement.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

using mediatool::studio::PlaybackClipDefinition;
using mediatool::studio::StudioArrangement;

constexpr int32_t kDefaultProjectSampleRate = 48'000;
constexpr int32_t kRecordingChannelCount = 1;
constexpr int64_t kNoSeek = -1;
constexpr int64_t kNoPunch = -1;
constexpr int32_t kCustomErrorFile = -10'001;
constexpr int32_t kCustomErrorBeatFormat = -10'002;
constexpr int32_t kCustomErrorRecordingState = -10'004;
constexpr int32_t kCustomErrorWriter = -10'005;
constexpr int32_t kCustomErrorArrangement = -10'006;
constexpr size_t kWavHeaderBytes = 44;

class SpscPcm16RingBuffer {
public:
    void reset(size_t requestedCapacity) {
        size_t capacity = 1;
        while (capacity < requestedCapacity) capacity <<= 1U;
        data_.assign(std::max<size_t>(capacity, 1024), 0);
        mask_ = data_.size() - 1U;
        readIndex_.store(0, std::memory_order_relaxed);
        writeIndex_.store(0, std::memory_order_relaxed);
    }

    size_t push(const int16_t* source, size_t count) {
        if (data_.empty() || source == nullptr || count == 0) return 0;
        const uint64_t read = readIndex_.load(std::memory_order_acquire);
        const uint64_t write = writeIndex_.load(std::memory_order_relaxed);
        const uint64_t used = write - read;
        const size_t free = used >= data_.size() ? 0 : data_.size() - static_cast<size_t>(used);
        const size_t accepted = std::min(count, free);
        for (size_t index = 0; index < accepted; ++index) {
            data_[(write + index) & mask_] = source[index];
        }
        writeIndex_.store(write + accepted, std::memory_order_release);
        return accepted;
    }

    size_t pop(int16_t* target, size_t capacity) {
        if (data_.empty() || target == nullptr || capacity == 0) return 0;
        const uint64_t write = writeIndex_.load(std::memory_order_acquire);
        const uint64_t read = readIndex_.load(std::memory_order_relaxed);
        const size_t availableFrames = static_cast<size_t>(write - read);
        const size_t count = std::min(availableFrames, capacity);
        for (size_t index = 0; index < count; ++index) {
            target[index] = data_[(read + index) & mask_];
        }
        readIndex_.store(read + count, std::memory_order_release);
        return count;
    }

    size_t available() const {
        const uint64_t write = writeIndex_.load(std::memory_order_acquire);
        const uint64_t read = readIndex_.load(std::memory_order_acquire);
        return static_cast<size_t>(write - read);
    }

private:
    std::vector<int16_t> data_;
    size_t mask_ = 0;
    std::atomic<uint64_t> readIndex_{0};
    std::atomic<uint64_t> writeIndex_{0};
};

void putLe16(uint8_t* target, size_t offset, uint16_t value) {
    target[offset] = static_cast<uint8_t>(value & 0xffU);
    target[offset + 1] = static_cast<uint8_t>((value >> 8U) & 0xffU);
}

void putLe32(uint8_t* target, size_t offset, uint32_t value) {
    target[offset] = static_cast<uint8_t>(value & 0xffU);
    target[offset + 1] = static_cast<uint8_t>((value >> 8U) & 0xffU);
    target[offset + 2] = static_cast<uint8_t>((value >> 16U) & 0xffU);
    target[offset + 3] = static_cast<uint8_t>((value >> 24U) & 0xffU);
}

bool writeCanonicalWavHeader(FILE* file, int32_t sampleRate, int32_t channels, uint32_t dataBytes) {
    if (file == nullptr || sampleRate <= 0 || channels <= 0) return false;
    uint8_t header[kWavHeaderBytes] = {};
    std::memcpy(header + 0, "RIFF", 4);
    putLe32(header, 4, 36U + dataBytes);
    std::memcpy(header + 8, "WAVE", 4);
    std::memcpy(header + 12, "fmt ", 4);
    putLe32(header, 16, 16U);
    putLe16(header, 20, 1U);
    putLe16(header, 22, static_cast<uint16_t>(channels));
    putLe32(header, 24, static_cast<uint32_t>(sampleRate));
    putLe32(header, 28, static_cast<uint32_t>(sampleRate * channels * 2));
    putLe16(header, 32, static_cast<uint16_t>(channels * 2));
    putLe16(header, 34, 16U);
    std::memcpy(header + 36, "data", 4);
    putLe32(header, 40, dataBytes);
    if (std::fseek(file, 0, SEEK_SET) != 0) return false;
    return std::fwrite(header, 1, sizeof(header), file) == sizeof(header);
}

int16_t floatToPcm16(float value) {
    const float clamped = std::max(-1.0f, std::min(1.0f, value));
    const int32_t scaled = static_cast<int32_t>(std::lrint(clamped * 32767.0f));
    return static_cast<int16_t>(std::max(-32768, std::min(32767, scaled)));
}

class StudioAudioEngine final : public oboe::AudioStreamDataCallback,
                                public oboe::AudioStreamErrorCallback {
public:
    ~StudioAudioEngine() override {
        close();
    }

    oboe::Result openOutput(int32_t preferredDeviceId) {
        closeOutputOnly();
        callbackFrames_.store(0);
        disconnectCount_.store(0);
        transportFrame_.store(0);
        pendingSeekFrame_.store(0);
        playheadFrame_ = 0.0;

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
        if (result == oboe::Result::OK) streamRunning_.store(true);
        return result;
    }

    oboe::Result stop() {
        playing_.store(false);
        streamRunning_.store(false);
        if (!outputStream_) return oboe::Result::OK;
        return outputStream_->requestStop();
    }

    int32_t loadBeat(const char* path, int32_t sampleRate, int32_t channels) {
        unloadBeat();
        if (path == nullptr || sampleRate <= 0 || channels <= 0 || channels > 2) {
            return kCustomErrorBeatFormat;
        }
        const int fd = ::open(path, O_RDONLY);
        if (fd < 0) return kCustomErrorFile;
        struct stat info {};
        if (::fstat(fd, &info) != 0 || info.st_size <= 0) {
            ::close(fd);
            return kCustomErrorFile;
        }
        const int64_t frameBytes = static_cast<int64_t>(channels) * 2;
        const int64_t alignedBytes = static_cast<int64_t>(info.st_size) -
            (static_cast<int64_t>(info.st_size) % frameBytes);
        if (alignedBytes < frameBytes) {
            ::close(fd);
            return kCustomErrorBeatFormat;
        }
        void* mapped = ::mmap(nullptr, static_cast<size_t>(alignedBytes), PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        if (mapped == MAP_FAILED) return kCustomErrorFile;

        beatMapping_ = mapped;
        beatMappingBytes_ = static_cast<size_t>(alignedBytes);
        beatSamples_ = static_cast<const int16_t*>(mapped);
        beatSampleRate_ = sampleRate;
        beatChannelCount_ = channels;
        beatFrameCount_ = alignedBytes / frameBytes;
        projectSampleRate_ = sampleRate;
        transportFrame_.store(std::min<int64_t>(transportFrame_.load(), beatFrameCount_));
        pendingSeekFrame_.store(transportFrame_.load());
        return 0;
    }

    int32_t setArrangement(const std::vector<PlaybackClipDefinition>& definitions, int32_t projectSampleRate) {
        if (projectSampleRate <= 0) return kCustomErrorArrangement;
        if (definitions.empty()) {
            std::shared_ptr<const StudioArrangement> empty;
            std::atomic_store_explicit(&arrangement_, empty, std::memory_order_release);
            return 0;
        }
        auto prepared = StudioArrangement::build(definitions, projectSampleRate);
        if (!prepared) return kCustomErrorArrangement;
        std::atomic_store_explicit(&arrangement_, prepared, std::memory_order_release);
        return 0;
    }

    void setPunchMuteWindow(int64_t startFrame, int64_t endFrame) {
        if (startFrame >= 0 && endFrame > startFrame) {
            punchMuteStart_.store(startFrame);
            punchMuteEnd_.store(endFrame);
        } else {
            punchMuteStart_.store(kNoPunch);
            punchMuteEnd_.store(kNoPunch);
        }
    }

    void setPlaying(bool playing) {
        playing_.store(playing);
    }

    void seek(int64_t projectFrame) {
        const int64_t upper = maxTimelineDuration();
        const int64_t safe = upper > 0
            ? std::max<int64_t>(0, std::min(projectFrame, upper))
            : std::max<int64_t>(0, projectFrame);
        pendingSeekFrame_.store(safe);
    }

    int32_t prepareInput(int32_t preferredDeviceId, int32_t inputMode) {
        if (!outputStream_) return static_cast<int32_t>(oboe::Result::ErrorInvalidState);
        if (recording_.load()) return kCustomErrorRecordingState;
        closeInputOnly();

        const int32_t desiredSampleRate = outputStream_->getSampleRate();
        const std::vector<oboe::InputPreset> presets = presetsForMode(inputMode);
        oboe::Result lastResult = oboe::Result::ErrorInternal;
        for (const auto preset : presets) {
            lastResult = openInputWithPreset(
                preferredDeviceId,
                desiredSampleRate,
                preset,
                oboe::SharingMode::Exclusive
            );
            if (lastResult != oboe::Result::OK) {
                inputStream_.reset();
                lastResult = openInputWithPreset(
                    preferredDeviceId,
                    desiredSampleRate,
                    preset,
                    oboe::SharingMode::Shared
                );
            }
            if (lastResult == oboe::Result::OK && inputStream_) break;
            inputStream_.reset();
        }
        if (!inputStream_) return static_cast<int32_t>(lastResult);

        const int32_t capacity = std::max<int32_t>(
            8'192,
            std::max(inputStream_->getBufferCapacityInFrames(), outputStream_->getBufferCapacityInFrames()) * 2
        );
        inputScratch_.assign(static_cast<size_t>(capacity), 0.0f);
        pcm16Scratch_.assign(static_cast<size_t>(capacity), 0);
        const int32_t inputRate = std::max(1, inputStream_->getSampleRate());
        lastInputSampleRate_.store(inputRate);
        lastInputDeviceId_.store(inputStream_->getDeviceId());
        ringBuffer_.reset(static_cast<size_t>(inputRate) * 8U);
        ringOverrunFrames_.store(0);
        inputReadFrames_.store(0);
        inputReadActive_.store(false);

        const auto startResult = inputStream_->requestStart();
        if (startResult != oboe::Result::OK) {
            closeInputOnly();
            return static_cast<int32_t>(startResult);
        }
        return 0;
    }

    int32_t startRecording(const char* path) {
        if (path == nullptr || inputStream_ == nullptr || recording_.load() || writerThread_.joinable()) {
            return kCustomErrorRecordingState;
        }
        for (int attempt = 0; attempt < 8 && !inputScratch_.empty(); ++attempt) {
            const int32_t drainFrames = std::min<int32_t>(1024, static_cast<int32_t>(inputScratch_.size()));
            auto drained = inputStream_->read(inputScratch_.data(), drainFrames, 0);
            if (drained != oboe::Result::OK || drained.value() <= 0) break;
        }

        const int32_t sampleRate = std::max(1, inputStream_->getSampleRate());
        recordingSampleRate_.store(sampleRate);
        FILE* file = std::fopen(path, "wb+");
        if (file == nullptr) return kCustomErrorFile;
        if (!writeCanonicalWavHeader(file, sampleRate, kRecordingChannelCount, 0U)) {
            std::fclose(file);
            return kCustomErrorWriter;
        }
        std::fflush(file);
        ::fsync(::fileno(file));

        recordFile_ = file;
        recordedFrames_.store(0);
        writerError_.store(0);
        ringOverrunFrames_.store(0);
        inputReadFrames_.store(0);
        writerRunning_.store(true);
        recording_.store(true);
        writerThread_ = std::thread([this]() { writerLoop(); });
        playing_.store(true);
        return 0;
    }

    int32_t stopRecording() {
        recording_.store(false);
        for (int attempt = 0; attempt < 100 && inputReadActive_.load(); ++attempt) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        closeInputOnly();
        if (writerThread_.joinable()) writerThread_.join();
        writerRunning_.store(false);
        return writerError_.load();
    }

    void close() {
        playing_.store(false);
        recording_.store(false);
        stopRecording();
        closeOutputOnly();
        unloadBeat();
        setPunchMuteWindow(kNoPunch, kNoPunch);
        std::shared_ptr<const StudioArrangement> empty;
        std::atomic_store_explicit(&arrangement_, empty, std::memory_order_release);
    }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override {
        if (audioData == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;
        const int64_t requestedSeek = pendingSeekFrame_.exchange(kNoSeek);
        if (requestedSeek >= 0) {
            const int64_t upper = maxTimelineDuration();
            const int64_t safe = upper > 0 ? std::min(requestedSeek, upper) : requestedSeek;
            playheadFrame_ = static_cast<double>(std::max<int64_t>(0, safe));
            transportFrame_.store(static_cast<int64_t>(playheadFrame_));
        }

        captureInput(numFrames);

        if (audioStream->getFormat() != oboe::AudioFormat::Float) {
            std::memset(audioData, 0, static_cast<size_t>(numFrames) * audioStream->getBytesPerFrame());
            return oboe::DataCallbackResult::Continue;
        }
        auto* output = static_cast<float*>(audioData);
        const int32_t outputChannels = std::max(1, audioStream->getChannelCount());
        const int32_t outputSampleRate = std::max(1, audioStream->getSampleRate());
        const bool shouldAdvance = playing_.load() || recording_.load();
        const double step = static_cast<double>(std::max(1, projectSampleRate_)) /
            static_cast<double>(outputSampleRate);
        const auto arrangement = std::atomic_load_explicit(&arrangement_, std::memory_order_acquire);
        const int64_t punchStart = punchMuteStart_.load();
        const int64_t punchEnd = punchMuteEnd_.load();
        const int64_t duration = maxTimelineDuration(arrangement);

        for (int32_t frame = 0; frame < numFrames; ++frame) {
            float left = 0.0f;
            float right = 0.0f;
            const int64_t projectFrame = std::max<int64_t>(0, static_cast<int64_t>(std::llround(playheadFrame_)));
            if (shouldAdvance && beatSamples_ != nullptr && beatFrameCount_ > 0 && playheadFrame_ < beatFrameCount_) {
                sampleBeat(playheadFrame_, left, right);
            }
            const bool punchMuted = recording_.load() && punchStart >= 0 && punchEnd > punchStart &&
                projectFrame >= punchStart && projectFrame < punchEnd;
            if (shouldAdvance && arrangement && !punchMuted) {
                arrangement->mix(projectFrame, left, right);
            }
            left = std::max(-1.0f, std::min(1.0f, left));
            right = std::max(-1.0f, std::min(1.0f, right));

            const size_t base = static_cast<size_t>(frame) * static_cast<size_t>(outputChannels);
            if (outputChannels == 1) {
                output[base] = 0.5f * (left + right);
            } else {
                output[base] = left;
                output[base + 1] = right;
                for (int32_t channel = 2; channel < outputChannels; ++channel) {
                    output[base + static_cast<size_t>(channel)] = 0.5f * (left + right);
                }
            }

            if (shouldAdvance) {
                playheadFrame_ += step;
                if (!recording_.load() && duration > 0 && playheadFrame_ >= static_cast<double>(duration)) {
                    playheadFrame_ = static_cast<double>(duration);
                    playing_.store(false);
                }
            }
        }
        transportFrame_.store(std::max<int64_t>(0, static_cast<int64_t>(std::llround(playheadFrame_))));
        callbackFrames_.fetch_add(numFrames);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        if (error == oboe::Result::ErrorDisconnected) {
            disconnectCount_.fetch_add(1);
            playing_.store(false);
            recording_.store(false);
        }
    }

    jlongArray diagnostics(JNIEnv* env) const {
        constexpr jsize kFieldCount = 22;
        jlong values[kFieldCount] = {};
        values[5] = -1;
        values[13] = lastInputSampleRate_.load();
        values[14] = lastInputDeviceId_.load();

        auto output = outputStream_;
        if (output) {
            values[0] = output->getSampleRate();
            values[1] = output->getChannelCount();
            values[2] = output->getFramesPerBurst();
            values[3] = output->getBufferSizeInFrames();
            values[4] = output->getBufferCapacityInFrames();
            values[5] = output->getDeviceId();
            values[6] = static_cast<int32_t>(output->getAudioApi());
            values[7] = static_cast<int32_t>(output->getSharingMode());
            values[8] = static_cast<int32_t>(output->getPerformanceMode());
        }
        values[9] = callbackFrames_.load();
        values[10] = disconnectCount_.load();
        values[11] = transportFrame_.load();
        values[12] = beatFrameCount_;

        auto input = inputStream_;
        if (input) {
            values[13] = input->getSampleRate();
            values[14] = input->getDeviceId();
        }
        values[15] = recordedFrames_.load();
        values[16] = ringOverrunFrames_.load();
        values[17] = playing_.load() ? 1 : 0;
        values[18] = recording_.load() ? 1 : 0;
        values[19] = writerError_.load();
        const auto arrangement = std::atomic_load_explicit(&arrangement_, std::memory_order_acquire);
        values[20] = arrangement ? static_cast<jlong>(arrangement->clipCount()) : 0;
        values[21] = arrangement ? arrangement->durationFrames() : 0;

        auto array = env->NewLongArray(kFieldCount);
        if (array != nullptr) env->SetLongArrayRegion(array, 0, kFieldCount, values);
        return array;
    }

private:
    oboe::Result openOutputWithSharingMode(int32_t preferredDeviceId, oboe::SharingMode sharingMode) {
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
        if (preferredDeviceId >= 0) builder.setDeviceId(preferredDeviceId);

        const auto result = builder.openStream(outputStream_);
        if (result != oboe::Result::OK || !outputStream_) return result;
        const auto framesPerBurst = outputStream_->getFramesPerBurst();
        if (framesPerBurst > 0) outputStream_->setBufferSizeInFrames(framesPerBurst * 2);
        return oboe::Result::OK;
    }

    oboe::Result openInputWithPreset(
        int32_t preferredDeviceId,
        int32_t desiredSampleRate,
        oboe::InputPreset preset,
        oboe::SharingMode sharingMode
    ) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(sharingMode);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(kRecordingChannelCount);
        builder.setSampleRate(desiredSampleRate);
        builder.setInputPreset(preset);
        builder.setChannelConversionAllowed(true);
        builder.setFormatConversionAllowed(true);
        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        builder.setErrorCallback(this);
        if (preferredDeviceId >= 0) builder.setDeviceId(preferredDeviceId);
        const auto result = builder.openStream(inputStream_);
        if (result != oboe::Result::OK || !inputStream_) return result;
        const auto framesPerBurst = inputStream_->getFramesPerBurst();
        if (framesPerBurst > 0) inputStream_->setBufferSizeInFrames(framesPerBurst * 2);
        return oboe::Result::OK;
    }

    std::vector<oboe::InputPreset> presetsForMode(int32_t inputMode) const {
        switch (inputMode) {
            case 1:
                return {oboe::InputPreset::Unprocessed, oboe::InputPreset::Generic};
            case 2:
                return {
                    oboe::InputPreset::VoicePerformance,
                    oboe::InputPreset::VoiceRecognition,
                    oboe::InputPreset::Generic,
                };
            default:
                return {
                    oboe::InputPreset::Unprocessed,
                    oboe::InputPreset::VoicePerformance,
                    oboe::InputPreset::VoiceRecognition,
                    oboe::InputPreset::Generic,
                };
        }
    }

    void captureInput(int32_t requestedFrames) {
        if (!recording_.load() || !inputStream_ || inputScratch_.empty() || pcm16Scratch_.empty()) return;
        const int32_t framesToRead = std::min<int32_t>(requestedFrames, static_cast<int32_t>(inputScratch_.size()));
        if (framesToRead <= 0) return;
        inputReadActive_.store(true, std::memory_order_release);
        auto result = inputStream_->read(inputScratch_.data(), framesToRead, 0);
        int32_t framesRead = 0;
        if (result == oboe::Result::OK) {
            framesRead = std::max(0, std::min(framesToRead, result.value()));
            inputReadFrames_.fetch_add(framesRead);
        } else if (result == oboe::Result::ErrorDisconnected) {
            recording_.store(false);
            disconnectCount_.fetch_add(1);
            inputReadActive_.store(false, std::memory_order_release);
            return;
        }
        for (int32_t frame = 0; frame < framesRead; ++frame) {
            pcm16Scratch_[static_cast<size_t>(frame)] = floatToPcm16(inputScratch_[static_cast<size_t>(frame)]);
        }
        for (int32_t frame = framesRead; frame < framesToRead; ++frame) {
            pcm16Scratch_[static_cast<size_t>(frame)] = 0;
        }
        const size_t accepted = ringBuffer_.push(pcm16Scratch_.data(), static_cast<size_t>(framesToRead));
        if (accepted < static_cast<size_t>(framesToRead)) {
            ringOverrunFrames_.fetch_add(static_cast<int64_t>(framesToRead) - static_cast<int64_t>(accepted));
        }
        inputReadActive_.store(false, std::memory_order_release);
    }

    void sampleBeat(double sourceFrame, float& left, float& right) const {
        if (beatSamples_ == nullptr || beatFrameCount_ <= 0) return;
        const int64_t index0 = std::max<int64_t>(0, std::min<int64_t>(
            static_cast<int64_t>(sourceFrame), beatFrameCount_ - 1
        ));
        const int64_t index1 = std::min<int64_t>(index0 + 1, beatFrameCount_ - 1);
        const float fraction = static_cast<float>(sourceFrame - static_cast<double>(index0));
        const auto readChannel = [this, index0, index1, fraction](int32_t channel) -> float {
            const int32_t sourceChannel = beatChannelCount_ == 1 ? 0 : std::min(channel, beatChannelCount_ - 1);
            const int16_t a = beatSamples_[index0 * beatChannelCount_ + sourceChannel];
            const int16_t b = beatSamples_[index1 * beatChannelCount_ + sourceChannel];
            const float mixed = static_cast<float>(a) + (static_cast<float>(b) - static_cast<float>(a)) * fraction;
            return mixed / 32768.0f;
        };
        left = readChannel(0);
        right = readChannel(1);
    }

    int64_t maxTimelineDuration() const {
        return maxTimelineDuration(std::atomic_load_explicit(&arrangement_, std::memory_order_acquire));
    }

    int64_t maxTimelineDuration(const std::shared_ptr<const StudioArrangement>& arrangement) const {
        return std::max<int64_t>(beatFrameCount_, arrangement ? arrangement->durationFrames() : 0L);
    }

    void writerLoop() {
        std::vector<int16_t> localBuffer(8'192);
        int64_t framesSinceSync = 0;
        const int32_t sampleRate = std::max(1, recordingSampleRate_.load());
        while (recording_.load() || ringBuffer_.available() > 0) {
            const size_t count = ringBuffer_.pop(localBuffer.data(), localBuffer.size());
            if (count == 0) {
                std::this_thread::sleep_for(std::chrono::milliseconds(2));
                continue;
            }
            FILE* file = recordFile_;
            if (file == nullptr || std::fwrite(localBuffer.data(), sizeof(int16_t), count, file) != count) {
                writerError_.store(kCustomErrorWriter);
                recording_.store(false);
                break;
            }
            recordedFrames_.fetch_add(static_cast<int64_t>(count));
            framesSinceSync += static_cast<int64_t>(count);
            if (recordedFrames_.load() * static_cast<int64_t>(sizeof(int16_t)) > 0xfffffff0LL) {
                writerError_.store(kCustomErrorWriter);
                recording_.store(false);
                break;
            }
            if (framesSinceSync >= sampleRate) {
                std::fflush(file);
                ::fsync(::fileno(file));
                framesSinceSync = 0;
            }
        }

        FILE* file = recordFile_;
        if (file != nullptr) {
            const int64_t frameCount = recordedFrames_.load();
            const uint32_t dataBytes = static_cast<uint32_t>(std::max<int64_t>(0, frameCount) * sizeof(int16_t));
            if (!writeCanonicalWavHeader(file, sampleRate, kRecordingChannelCount, dataBytes)) {
                writerError_.store(kCustomErrorWriter);
            }
            std::fflush(file);
            ::fsync(::fileno(file));
            std::fclose(file);
            recordFile_ = nullptr;
        }
        writerRunning_.store(false);
    }

    void closeInputOnly() {
        if (inputStream_) {
            lastInputSampleRate_.store(inputStream_->getSampleRate());
            lastInputDeviceId_.store(inputStream_->getDeviceId());
            inputStream_->requestStop();
            inputStream_->close();
            inputStream_.reset();
        }
    }

    void closeOutputOnly() {
        streamRunning_.store(false);
        playing_.store(false);
        if (outputStream_) {
            outputStream_->requestStop();
            outputStream_->close();
            outputStream_.reset();
        }
    }

    void unloadBeat() {
        if (beatMapping_ != nullptr && beatMappingBytes_ > 0) {
            ::munmap(beatMapping_, beatMappingBytes_);
        }
        beatMapping_ = nullptr;
        beatMappingBytes_ = 0;
        beatSamples_ = nullptr;
        beatSampleRate_ = 0;
        beatChannelCount_ = 0;
        beatFrameCount_ = 0;
    }

    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<const StudioArrangement> arrangement_;

    void* beatMapping_ = nullptr;
    size_t beatMappingBytes_ = 0;
    const int16_t* beatSamples_ = nullptr;
    int32_t beatSampleRate_ = 0;
    int32_t beatChannelCount_ = 0;
    int64_t beatFrameCount_ = 0;
    int32_t projectSampleRate_ = kDefaultProjectSampleRate;
    double playheadFrame_ = 0.0;

    std::vector<float> inputScratch_;
    std::vector<int16_t> pcm16Scratch_;
    SpscPcm16RingBuffer ringBuffer_;
    FILE* recordFile_ = nullptr;
    std::thread writerThread_;

    std::atomic<bool> streamRunning_{false};
    std::atomic<bool> playing_{false};
    std::atomic<bool> recording_{false};
    std::atomic<bool> writerRunning_{false};
    std::atomic<bool> inputReadActive_{false};
    std::atomic<int64_t> callbackFrames_{0};
    std::atomic<int64_t> disconnectCount_{0};
    std::atomic<int64_t> transportFrame_{0};
    std::atomic<int64_t> pendingSeekFrame_{0};
    std::atomic<int64_t> recordedFrames_{0};
    std::atomic<int64_t> ringOverrunFrames_{0};
    std::atomic<int64_t> inputReadFrames_{0};
    std::atomic<int64_t> punchMuteStart_{kNoPunch};
    std::atomic<int64_t> punchMuteEnd_{kNoPunch};
    std::atomic<int32_t> writerError_{0};
    std::atomic<int32_t> lastInputSampleRate_{0};
    std::atomic<int32_t> lastInputDeviceId_{-1};
    std::atomic<int32_t> recordingSampleRate_{kDefaultProjectSampleRate};
};

StudioAudioEngine* fromHandle(jlong handle) {
    return reinterpret_cast<StudioAudioEngine*>(handle);
}

std::string jStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool sameLength(JNIEnv* env, jsize count, jarray array) {
    return array != nullptr && env->GetArrayLength(array) == count;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeConfigureDefaults(
    JNIEnv*, jobject, jint sampleRate, jint framesPerBurst
) {
    if (sampleRate > 0) oboe::DefaultStreamValues::SampleRate = sampleRate;
    if (framesPerBurst > 0) oboe::DefaultStreamValues::FramesPerBurst = framesPerBurst;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeCreate(
    JNIEnv*, jobject
) {
    return reinterpret_cast<jlong>(new StudioAudioEngine());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeOpenOutput(
    JNIEnv*, jobject, jlong handle, jint preferredDeviceId
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    return static_cast<jint>(engine->openOutput(preferredDeviceId));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeStart(
    JNIEnv*, jobject, jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    return static_cast<jint>(engine->start());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeStop(
    JNIEnv*, jobject, jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    return static_cast<jint>(engine->stop());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeLoadBeat(
    JNIEnv* env, jobject, jlong handle, jstring path, jint sampleRate, jint channels
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    const std::string nativePath = jStringToUtf8(env, path);
    return engine->loadBeat(nativePath.c_str(), sampleRate, channels);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeSetArrangement(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobjectArray paths,
    jlongArray timelineStarts,
    jlongArray sourceStarts,
    jlongArray sourceEnds,
    jfloatArray gainsDb,
    jlongArray fadeIns,
    jlongArray fadeOuts,
    jint projectSampleRate
) {
    auto* engine = fromHandle(handle);
    if (!engine || paths == nullptr) return -1;
    const jsize count = env->GetArrayLength(paths);
    if (!sameLength(env, count, timelineStarts) || !sameLength(env, count, sourceStarts) ||
        !sameLength(env, count, sourceEnds) || !sameLength(env, count, gainsDb) ||
        !sameLength(env, count, fadeIns) || !sameLength(env, count, fadeOuts)) {
        return kCustomErrorArrangement;
    }

    std::vector<jlong> timeline(static_cast<size_t>(count));
    std::vector<jlong> sourceStart(static_cast<size_t>(count));
    std::vector<jlong> sourceEnd(static_cast<size_t>(count));
    std::vector<jfloat> gain(static_cast<size_t>(count));
    std::vector<jlong> fadeIn(static_cast<size_t>(count));
    std::vector<jlong> fadeOut(static_cast<size_t>(count));
    if (count > 0) {
        env->GetLongArrayRegion(timelineStarts, 0, count, timeline.data());
        env->GetLongArrayRegion(sourceStarts, 0, count, sourceStart.data());
        env->GetLongArrayRegion(sourceEnds, 0, count, sourceEnd.data());
        env->GetFloatArrayRegion(gainsDb, 0, count, gain.data());
        env->GetLongArrayRegion(fadeIns, 0, count, fadeIn.data());
        env->GetLongArrayRegion(fadeOuts, 0, count, fadeOut.data());
        if (env->ExceptionCheck()) return kCustomErrorArrangement;
    }

    std::vector<PlaybackClipDefinition> definitions;
    definitions.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto pathValue = static_cast<jstring>(env->GetObjectArrayElement(paths, index));
        const std::string path = jStringToUtf8(env, pathValue);
        if (pathValue != nullptr) env->DeleteLocalRef(pathValue);
        if (path.empty()) return kCustomErrorArrangement;
        PlaybackClipDefinition definition;
        definition.path = path;
        definition.timelineStartFrame = timeline[static_cast<size_t>(index)];
        definition.sourceStartFrame = sourceStart[static_cast<size_t>(index)];
        definition.sourceEndFrame = sourceEnd[static_cast<size_t>(index)];
        definition.gainDb = gain[static_cast<size_t>(index)];
        definition.fadeInFrames = fadeIn[static_cast<size_t>(index)];
        definition.fadeOutFrames = fadeOut[static_cast<size_t>(index)];
        definitions.push_back(std::move(definition));
    }
    return engine->setArrangement(definitions, projectSampleRate);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeSetPunchMuteWindow(
    JNIEnv*, jobject, jlong handle, jlong startFrame, jlong endFrame
) {
    if (auto* engine = fromHandle(handle)) engine->setPunchMuteWindow(startFrame, endFrame);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeSetPlaying(
    JNIEnv*, jobject, jlong handle, jboolean playing
) {
    if (auto* engine = fromHandle(handle)) engine->setPlaying(playing == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeSeek(
    JNIEnv*, jobject, jlong handle, jlong projectFrame
) {
    if (auto* engine = fromHandle(handle)) engine->seek(projectFrame);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativePrepareInput(
    JNIEnv*, jobject, jlong handle, jint preferredDeviceId, jint inputMode
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    return engine->prepareInput(preferredDeviceId, inputMode);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeStartRecording(
    JNIEnv* env, jobject, jlong handle, jstring path
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    const std::string nativePath = jStringToUtf8(env, path);
    return engine->startRecording(nativePath.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeStopRecording(
    JNIEnv*, jobject, jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return -1;
    return engine->stopRecording();
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeClose(
    JNIEnv*, jobject, jlong handle
) {
    if (auto* engine = fromHandle(handle)) engine->close();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeDiagnostics(
    JNIEnv* env, jobject, jlong handle
) {
    auto* engine = fromHandle(handle);
    if (!engine) return env->NewLongArray(0);
    return engine->diagnostics(env);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioNativeAudio_nativeRelease(
    JNIEnv*, jobject, jlong handle
) {
    delete fromHandle(handle);
}
