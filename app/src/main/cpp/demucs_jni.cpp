#include <jni.h>
#include <android/log.h>

#include "model.hpp"
#include "tensor.hpp"

#include <Eigen/Core>
#include <Eigen/Dense>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <exception>
#include <fstream>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
constexpr const char *TAG = "MediaToolDemucs";
constexpr int CHANNELS = 2;
constexpr int VOCALS_SOURCE = 3;
std::atomic_bool g_cancelled{false};
std::mutex g_inference_mutex;

class Cancelled final : public std::exception {
public:
    const char *what() const noexcept override { return "CANCELLED"; }
};

class UtfChars final {
public:
    UtfChars(JNIEnv *env, jstring value) : env_(env), value_(value) {
        chars_ = value == nullptr ? nullptr : env->GetStringUTFChars(value, nullptr);
    }
    ~UtfChars() {
        if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
    }
    std::string str() const { return chars_ == nullptr ? std::string() : std::string(chars_); }
private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_ = nullptr;
};

void log_error(const std::string &message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

void notify_progress(JNIEnv *env, jobject callback, jmethodID method, float progress, const std::string &message) {
    if (callback == nullptr || method == nullptr) return;
    jstring j_message = env->NewStringUTF(message.c_str());
    env->CallVoidMethod(callback, method, std::clamp(progress, 0.0f, 1.0f), j_message);
    env->DeleteLocalRef(j_message);
    if (env->ExceptionCheck()) {
        throw std::runtime_error("Kotlin progress callback failed");
    }
}

Eigen::MatrixXf read_pcm(const std::string &path) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) throw std::runtime_error("Cannot open input PCM");
    const std::streamoff size = input.tellg();
    const std::streamoff bytes_per_frame = CHANNELS * static_cast<std::streamoff>(sizeof(float));
    if (size <= 0 || size % bytes_per_frame != 0) {
        throw std::runtime_error("Input PCM size is invalid");
    }
    const std::uint64_t frames64 = static_cast<std::uint64_t>(size / bytes_per_frame);
    if (frames64 > static_cast<std::uint64_t>(std::numeric_limits<int>::max())) {
        throw std::runtime_error("Input PCM is too long");
    }
    Eigen::MatrixXf audio(CHANNELS, static_cast<Eigen::Index>(frames64));
    input.seekg(0, std::ios::beg);
    input.read(reinterpret_cast<char *>(audio.data()), size);
    if (!input) throw std::runtime_error("Cannot read complete input PCM");
    return audio;
}

void write_outputs(
    const Eigen::MatrixXf &mix,
    const Eigen::Tensor3dXf &targets,
    const std::string &vocals_path,
    const std::string &music_path,
    const std::string &drums_path,
    const std::string &bass_path,
    const std::string &other_path,
    bool four_stems,
    JNIEnv *env,
    jobject callback,
    jmethodID progress_method
) {
    std::ofstream vocals(vocals_path, std::ios::binary);
    std::ofstream music(music_path, std::ios::binary);
    std::ofstream drums;
    std::ofstream bass;
    std::ofstream other;
    if (!vocals || !music) throw std::runtime_error("Cannot open two-stem output PCM");
    if (four_stems) {
        drums.open(drums_path, std::ios::binary);
        bass.open(bass_path, std::ios::binary);
        other.open(other_path, std::ios::binary);
        if (!drums || !bass || !other) throw std::runtime_error("Cannot open four-stem output PCM");
    }

    constexpr Eigen::Index BLOCK_FRAMES = 8192;
    std::vector<float> vocals_buffer(BLOCK_FRAMES * CHANNELS);
    std::vector<float> music_buffer(BLOCK_FRAMES * CHANNELS);
    std::vector<float> drums_buffer(four_stems ? BLOCK_FRAMES * CHANNELS : 0);
    std::vector<float> bass_buffer(four_stems ? BLOCK_FRAMES * CHANNELS : 0);
    std::vector<float> other_buffer(four_stems ? BLOCK_FRAMES * CHANNELS : 0);
    const Eigen::Index total_frames = mix.cols();

    for (Eigen::Index offset = 0; offset < total_frames; offset += BLOCK_FRAMES) {
        if (g_cancelled.load(std::memory_order_relaxed)) throw Cancelled();
        const Eigen::Index count = std::min(BLOCK_FRAMES, total_frames - offset);
        for (Eigen::Index frame = 0; frame < count; ++frame) {
            for (int channel = 0; channel < CHANNELS; ++channel) {
                const Eigen::Index index = frame * CHANNELS + channel;
                const float vocal = targets(VOCALS_SOURCE, channel, offset + frame);
                vocals_buffer[index] = vocal;
                music_buffer[index] = mix(channel, offset + frame) - vocal;
                if (four_stems) {
                    drums_buffer[index] = targets(0, channel, offset + frame);
                    bass_buffer[index] = targets(1, channel, offset + frame);
                    other_buffer[index] = targets(2, channel, offset + frame);
                }
            }
        }
        const std::streamsize bytes = static_cast<std::streamsize>(count * CHANNELS * sizeof(float));
        vocals.write(reinterpret_cast<const char *>(vocals_buffer.data()), bytes);
        music.write(reinterpret_cast<const char *>(music_buffer.data()), bytes);
        if (four_stems) {
            drums.write(reinterpret_cast<const char *>(drums_buffer.data()), bytes);
            bass.write(reinterpret_cast<const char *>(bass_buffer.data()), bytes);
            other.write(reinterpret_cast<const char *>(other_buffer.data()), bytes);
        }
        if (!vocals || !music || (four_stems && (!drums || !bass || !other))) {
            throw std::runtime_error("Cannot write output PCM");
        }
        const float ratio = total_frames > 0
            ? static_cast<float>(offset + count) / static_cast<float>(total_frames)
            : 1.0f;
        notify_progress(env, callback, progress_method, 0.96f + ratio * 0.04f, "Writing output PCM");
    }
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_aistudio_mediatool_core_ml_DemucsNativeBridge_separate(
    JNIEnv *env,
    jobject,
    jstring model_path,
    jstring input_raw_path,
    jstring vocals_raw_path,
    jstring music_raw_path,
    jstring drums_raw_path,
    jstring bass_raw_path,
    jstring other_raw_path,
    jboolean write_four_stems,
    jint thread_count,
    jobject callback
) {
    std::lock_guard<std::mutex> lock(g_inference_mutex);
    g_cancelled.store(false, std::memory_order_relaxed);
    try {
        const std::string model = UtfChars(env, model_path).str();
        const std::string input = UtfChars(env, input_raw_path).str();
        const std::string vocals = UtfChars(env, vocals_raw_path).str();
        const std::string music = UtfChars(env, music_raw_path).str();
        const std::string drums = UtfChars(env, drums_raw_path).str();
        const std::string bass = UtfChars(env, bass_raw_path).str();
        const std::string other = UtfChars(env, other_raw_path).str();

        jclass callback_class = env->GetObjectClass(callback);
        jmethodID progress_method = env->GetMethodID(callback_class, "onProgress", "(FLjava/lang/String;)V");
        if (progress_method == nullptr) throw std::runtime_error("Progress callback method is missing");

        Eigen::setNbThreads(std::clamp(static_cast<int>(thread_count), 1, 4));
        notify_progress(env, callback, progress_method, 0.01f, "Reading PCM");
        Eigen::MatrixXf audio = read_pcm(input);

        notify_progress(env, callback, progress_method, 0.03f, "Loading Demucs model");
        demucscpp::demucs_model demucs_model{};
        if (!demucscpp::load_demucs_model(model, &demucs_model)) {
            throw std::runtime_error("Cannot load verified Demucs model");
        }
        if (!demucs_model.is_4sources) {
            throw std::runtime_error("Model is not a four-source Demucs model");
        }

        demucscpp::ProgressCallback progress_callback =
            [env, callback, progress_method](float progress, const std::string &message) {
                if (g_cancelled.load(std::memory_order_relaxed)) throw Cancelled();
                notify_progress(env, callback, progress_method, 0.04f + progress * 0.92f, message);
            };

        Eigen::Tensor3dXf targets = demucscpp::demucs_inference(
            demucs_model,
            audio,
            progress_callback
        );
        if (g_cancelled.load(std::memory_order_relaxed)) throw Cancelled();
        if (targets.dimension(0) < 4 || targets.dimension(1) != CHANNELS || targets.dimension(2) != audio.cols()) {
            throw std::runtime_error("Demucs output tensor shape is invalid");
        }

        write_outputs(
            audio,
            targets,
            vocals,
            music,
            drums,
            bass,
            other,
            write_four_stems == JNI_TRUE,
            env,
            callback,
            progress_method
        );
        env->DeleteLocalRef(callback_class);
        return nullptr;
    } catch (const Cancelled &) {
        return env->NewStringUTF("CANCELLED");
    } catch (const std::bad_alloc &) {
        log_error("Native Demucs ran out of memory");
        return env->NewStringUTF("OUT_OF_MEMORY");
    } catch (const std::exception &error) {
        log_error(error.what());
        return env->NewStringUTF(error.what());
    } catch (...) {
        log_error("Unknown native Demucs error");
        return env->NewStringUTF("UNKNOWN_NATIVE_ERROR");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_core_ml_DemucsNativeBridge_cancel(JNIEnv *, jobject) {
    g_cancelled.store(true, std::memory_order_relaxed);
}
