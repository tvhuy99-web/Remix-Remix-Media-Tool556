#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <unordered_map>
#include <utility>
#include <vector>

namespace mediatool::studio {

inline uint16_t readLe16(const uint8_t* data, size_t offset) {
    return static_cast<uint16_t>(data[offset]) |
        (static_cast<uint16_t>(data[offset + 1]) << 8U);
}

inline uint32_t readLe32(const uint8_t* data, size_t offset) {
    return static_cast<uint32_t>(data[offset]) |
        (static_cast<uint32_t>(data[offset + 1]) << 8U) |
        (static_cast<uint32_t>(data[offset + 2]) << 16U) |
        (static_cast<uint32_t>(data[offset + 3]) << 24U);
}

class MappedCanonicalWav final {
public:
    ~MappedCanonicalWav() {
        if (mapping_ != nullptr && mappingBytes_ > 0) {
            ::munmap(mapping_, mappingBytes_);
        }
    }

    MappedCanonicalWav(const MappedCanonicalWav&) = delete;
    MappedCanonicalWav& operator=(const MappedCanonicalWav&) = delete;

    static std::shared_ptr<MappedCanonicalWav> open(const std::string& path) {
        const int fd = ::open(path.c_str(), O_RDONLY);
        if (fd < 0) return nullptr;
        struct stat info {};
        if (::fstat(fd, &info) != 0 || info.st_size < 44) {
            ::close(fd);
            return nullptr;
        }
        void* mapping = ::mmap(nullptr, static_cast<size_t>(info.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
        ::close(fd);
        if (mapping == MAP_FAILED) return nullptr;

        auto* bytes = static_cast<const uint8_t*>(mapping);
        const bool canonical =
            std::memcmp(bytes + 0, "RIFF", 4) == 0 &&
            std::memcmp(bytes + 8, "WAVE", 4) == 0 &&
            std::memcmp(bytes + 12, "fmt ", 4) == 0 &&
            std::memcmp(bytes + 36, "data", 4) == 0 &&
            readLe32(bytes, 16) == 16U &&
            readLe16(bytes, 20) == 1U &&
            readLe16(bytes, 34) == 16U;
        if (!canonical) {
            ::munmap(mapping, static_cast<size_t>(info.st_size));
            return nullptr;
        }

        const int32_t channels = static_cast<int32_t>(readLe16(bytes, 22));
        const int32_t sampleRate = static_cast<int32_t>(readLe32(bytes, 24));
        const uint32_t declaredDataBytes = readLe32(bytes, 40);
        if (channels < 1 || channels > 2 || sampleRate <= 0) {
            ::munmap(mapping, static_cast<size_t>(info.st_size));
            return nullptr;
        }
        const size_t availableBytes = static_cast<size_t>(info.st_size) - 44U;
        const size_t dataBytes = std::min<size_t>(declaredDataBytes, availableBytes);
        const size_t frameBytes = static_cast<size_t>(channels) * sizeof(int16_t);
        const int64_t frames = static_cast<int64_t>(dataBytes / frameBytes);
        if (frames <= 0) {
            ::munmap(mapping, static_cast<size_t>(info.st_size));
            return nullptr;
        }

        return std::shared_ptr<MappedCanonicalWav>(
            new MappedCanonicalWav(mapping, static_cast<size_t>(info.st_size), channels, sampleRate, frames)
        );
    }

    int32_t channels() const { return channels_; }
    int32_t sampleRate() const { return sampleRate_; }
    int64_t frameCount() const { return frameCount_; }

    void sample(double frame, float& left, float& right) const {
        if (samples_ == nullptr || frameCount_ <= 0) return;
        const double bounded = std::max(0.0, std::min(frame, static_cast<double>(frameCount_ - 1)));
        const int64_t index0 = static_cast<int64_t>(bounded);
        const int64_t index1 = std::min<int64_t>(index0 + 1, frameCount_ - 1);
        const float fraction = static_cast<float>(bounded - static_cast<double>(index0));
        const auto readChannel = [this, index0, index1, fraction](int32_t channel) {
            const int32_t sourceChannel = channels_ == 1 ? 0 : std::min(channel, channels_ - 1);
            const int16_t a = samples_[index0 * channels_ + sourceChannel];
            const int16_t b = samples_[index1 * channels_ + sourceChannel];
            return (static_cast<float>(a) + (static_cast<float>(b) - static_cast<float>(a)) * fraction) / 32768.0f;
        };
        left = readChannel(0);
        right = readChannel(1);
    }

private:
    MappedCanonicalWav(
        void* mapping,
        size_t mappingBytes,
        int32_t channels,
        int32_t sampleRate,
        int64_t frameCount
    ) : mapping_(mapping),
        mappingBytes_(mappingBytes),
        samples_(reinterpret_cast<const int16_t*>(static_cast<const uint8_t*>(mapping) + 44U)),
        channels_(channels),
        sampleRate_(sampleRate),
        frameCount_(frameCount) {}

    void* mapping_ = nullptr;
    size_t mappingBytes_ = 0;
    const int16_t* samples_ = nullptr;
    int32_t channels_ = 0;
    int32_t sampleRate_ = 0;
    int64_t frameCount_ = 0;
};

struct PlaybackClipDefinition {
    std::string path;
    int64_t timelineStartFrame = 0;
    int64_t sourceStartFrame = 0;
    int64_t sourceEndFrame = 0;
    float gainDb = 0.0f;
    float pan = 0.0f;
    int64_t fadeInFrames = 0;
    int64_t fadeOutFrames = 0;
};

class PlaybackClip final {
public:
    PlaybackClip(
        std::shared_ptr<MappedCanonicalWav> audio,
        const PlaybackClipDefinition& definition,
        int32_t projectSampleRate
    ) : audio_(std::move(audio)),
        timelineStartFrame_(std::max<int64_t>(0, definition.timelineStartFrame)),
        sourceStartFrame_(std::max<int64_t>(0, definition.sourceStartFrame)),
        sourceEndFrame_(std::min<int64_t>(audio_->frameCount(), definition.sourceEndFrame)),
        gainLinear_(std::pow(10.0f, std::max(-60.0f, std::min(18.0f, definition.gainDb)) / 20.0f)),
        pan_(std::max(-1.0f, std::min(1.0f, definition.pan))),
        fadeInFrames_(std::max<int64_t>(0, definition.fadeInFrames)),
        fadeOutFrames_(std::max<int64_t>(0, definition.fadeOutFrames)),
        projectSampleRate_(std::max(1, projectSampleRate)) {
        const int64_t sourceLength = std::max<int64_t>(0, sourceEndFrame_ - sourceStartFrame_);
        timelineLengthFrames_ = static_cast<int64_t>(std::llround(
            static_cast<double>(sourceLength) * static_cast<double>(projectSampleRate_) /
                static_cast<double>(std::max(1, audio_->sampleRate()))
        ));
        fadeInFrames_ = std::min(fadeInFrames_, sourceLength);
        fadeOutFrames_ = std::min(fadeOutFrames_, std::max<int64_t>(0, sourceLength - fadeInFrames_));
        leftPanScale_ = pan_ > 0.0f ? 1.0f - pan_ : 1.0f;
        rightPanScale_ = pan_ < 0.0f ? 1.0f + pan_ : 1.0f;
    }

    bool valid() const {
        return audio_ != nullptr && sourceEndFrame_ > sourceStartFrame_ && timelineLengthFrames_ > 0;
    }

    int64_t timelineStartFrame() const { return timelineStartFrame_; }

    int64_t timelineEndFrame() const {
        return timelineStartFrame_ + timelineLengthFrames_;
    }

    void mix(int64_t projectFrame, float& left, float& right) const {
        if (!valid() || projectFrame < timelineStartFrame_ || projectFrame >= timelineEndFrame()) return;
        const int64_t timelineDelta = projectFrame - timelineStartFrame_;
        const double sourceOffset = static_cast<double>(timelineDelta) *
            static_cast<double>(audio_->sampleRate()) / static_cast<double>(projectSampleRate_);
        const double sourceFrame = static_cast<double>(sourceStartFrame_) + sourceOffset;
        if (sourceFrame < static_cast<double>(sourceStartFrame_) || sourceFrame >= static_cast<double>(sourceEndFrame_)) return;

        float sampleLeft = 0.0f;
        float sampleRight = 0.0f;
        audio_->sample(sourceFrame, sampleLeft, sampleRight);
        float envelope = gainLinear_;
        const double fromStart = sourceFrame - static_cast<double>(sourceStartFrame_);
        const double toEnd = static_cast<double>(sourceEndFrame_) - sourceFrame;
        if (fadeInFrames_ > 0 && fromStart < static_cast<double>(fadeInFrames_)) {
            envelope *= static_cast<float>(std::max(0.0, fromStart / static_cast<double>(fadeInFrames_)));
        }
        if (fadeOutFrames_ > 0 && toEnd < static_cast<double>(fadeOutFrames_)) {
            envelope *= static_cast<float>(std::max(0.0, toEnd / static_cast<double>(fadeOutFrames_)));
        }
        left += sampleLeft * envelope * leftPanScale_;
        right += sampleRight * envelope * rightPanScale_;
    }

private:
    std::shared_ptr<MappedCanonicalWav> audio_;
    int64_t timelineStartFrame_ = 0;
    int64_t sourceStartFrame_ = 0;
    int64_t sourceEndFrame_ = 0;
    float gainLinear_ = 1.0f;
    float pan_ = 0.0f;
    float leftPanScale_ = 1.0f;
    float rightPanScale_ = 1.0f;
    int64_t fadeInFrames_ = 0;
    int64_t fadeOutFrames_ = 0;
    int32_t projectSampleRate_ = 48'000;
    int64_t timelineLengthFrames_ = 0;
};

class StudioArrangement final {
public:
    static std::shared_ptr<const StudioArrangement> build(
        const std::vector<PlaybackClipDefinition>& definitions,
        int32_t projectSampleRate
    ) {
        auto arrangement = std::shared_ptr<StudioArrangement>(new StudioArrangement());
        std::unordered_map<std::string, std::shared_ptr<MappedCanonicalWav>> cache;
        for (const auto& definition : definitions) {
            auto found = cache.find(definition.path);
            std::shared_ptr<MappedCanonicalWav> audio;
            if (found != cache.end()) {
                audio = found->second;
            } else {
                audio = MappedCanonicalWav::open(definition.path);
                if (audio != nullptr) cache.emplace(definition.path, audio);
            }
            if (audio == nullptr) return nullptr;
            PlaybackClip clip(audio, definition, projectSampleRate);
            if (!clip.valid()) return nullptr;
            arrangement->durationFrames_ = std::max(arrangement->durationFrames_, clip.timelineEndFrame());
            arrangement->clips_.push_back(std::move(clip));
        }
        arrangement->bucketFrames_ = std::max<int64_t>(1, projectSampleRate);
        const size_t bucketCount = arrangement->durationFrames_ > 0
            ? static_cast<size_t>((arrangement->durationFrames_ + arrangement->bucketFrames_ - 1) / arrangement->bucketFrames_)
            : 0U;
        arrangement->clipBuckets_.resize(bucketCount);
        for (size_t index = 0; index < arrangement->clips_.size(); ++index) {
            const auto& clip = arrangement->clips_[index];
            const int64_t start = std::max<int64_t>(0, clip.timelineStartFrame());
            const int64_t end = std::max<int64_t>(start + 1, clip.timelineEndFrame());
            const size_t firstBucket = static_cast<size_t>(start / arrangement->bucketFrames_);
            const size_t lastBucket = static_cast<size_t>((end - 1) / arrangement->bucketFrames_);
            for (size_t bucket = firstBucket; bucket <= lastBucket && bucket < arrangement->clipBuckets_.size(); ++bucket) {
                arrangement->clipBuckets_[bucket].push_back(index);
            }
        }
        return arrangement;
    }

    void mix(int64_t projectFrame, float& left, float& right) const {
        if (projectFrame < 0 || bucketFrames_ <= 0 || clipBuckets_.empty()) return;
        const size_t bucket = static_cast<size_t>(projectFrame / bucketFrames_);
        if (bucket >= clipBuckets_.size()) return;
        for (const size_t index : clipBuckets_[bucket]) clips_[index].mix(projectFrame, left, right);
    }

    size_t clipCount() const { return clips_.size(); }
    int64_t durationFrames() const { return durationFrames_; }

private:
    std::vector<PlaybackClip> clips_;
    std::vector<std::vector<size_t>> clipBuckets_;
    int64_t bucketFrames_ = 48'000;
    int64_t durationFrames_ = 0;
};

}  // namespace mediatool::studio