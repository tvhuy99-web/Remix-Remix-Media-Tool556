#include <jni.h>
#include <android/log.h>

#include "model.hpp"
#include "tensor.hpp"

#include <Eigen/Core>
#include <Eigen/Dense>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <exception>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr const char *TAG = "MediaToolDemucs";
constexpr int CHANNELS = 2;
constexpr int VOCALS_SOURCE = 3;
constexpr int MAX_PARALLEL_WORKERS = 4;
constexpr int MIN_CORE_SECONDS_PER_WORKER = 4;
constexpr int OVERLAP_FRAMES = 33'075;  // 0.75 s at 44.1 kHz.
constexpr float HALF_PI = 1.57079632679489661923f;
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

struct WorkerState {
    int core_start = 0;
    int core_end = 0;
    int segment_start = 0;
    int segment_end = 0;
    Eigen::MatrixXf segment;
    Eigen::Tensor3dXf output;
    std::atomic<float> progress{0.0f};
    std::exception_ptr error;
};

void log_error(const std::string &message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

void log_info(const std::string &message) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", message.c_str());
}

void notify_progress(
    JNIEnv *env,
    jobject callback,
    jmethodID method,
    float progress,
    const std::string &message
) {
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
    const std::streamoff bytes_per_frame =
        CHANNELS * static_cast<std::streamoff>(sizeof(float));
    if (size <= 0 || size % bytes_per_frame != 0) {
        throw std::runtime_error("Input PCM size is invalid");
    }
    const std::uint64_t frames64 =
        static_cast<std::uint64_t>(size / bytes_per_frame);
    if (frames64 > static_cast<std::uint64_t>(std::numeric_limits<int>::max())) {
        throw std::runtime_error("Input PCM is too long");
    }
    Eigen::MatrixXf audio(CHANNELS, static_cast<Eigen::Index>(frames64));
    input.seekg(0, std::ios::beg);
    input.read(reinterpret_cast<char *>(audio.data()), size);
    if (!input) throw std::runtime_error("Cannot read complete input PCM");
    return audio;
}

int effective_worker_count(int requested_workers, int total_frames) {
    const int requested = std::clamp(requested_workers, 1, MAX_PARALLEL_WORKERS);
    const int min_frames = MIN_CORE_SECONDS_PER_WORKER * demucscpp::SUPPORTED_SAMPLE_RATE;
    const int duration_bound = std::clamp(
        static_cast<int>(
            std::ceil(static_cast<double>(total_frames) / static_cast<double>(min_frames))
        ),
        1,
        MAX_PARALLEL_WORKERS
    );
    return std::min(requested, duration_bound);
}

float fade_in_weight(int global_frame, int segment_start, int core_start) {
    const int width = core_start - segment_start;
    if (width <= 0) return 1.0f;
    const float t = std::clamp(
        static_cast<float>(global_frame - segment_start + 1) /
            static_cast<float>(width + 1),
        0.0f,
        1.0f
    );
    const float s = std::sin(t * HALF_PI);
    return s * s;
}

float fade_out_weight(int global_frame, int core_end, int segment_end) {
    const int width = segment_end - core_end;
    if (width <= 0) return 1.0f;
    const float t = std::clamp(
        static_cast<float>(segment_end - global_frame) /
            static_cast<float>(width + 1),
        0.0f,
        1.0f
    );
    const float s = std::sin(t * HALF_PI);
    return s * s;
}

Eigen::Tensor3dXf parallel_inference(
    const demucscpp::demucs_model &model,
    const Eigen::MatrixXf &audio,
    int requested_workers,
    JNIEnv *env,
    jobject callback,
    jmethodID progress_method
) {
    const int total_frames = static_cast<int>(audio.cols());
    const int worker_count = effective_worker_count(requested_workers, total_frames);

    if (worker_count == 1) {
        demucscpp::ProgressCallback progress_callback =
            [env, callback, progress_method](float progress, const std::string &message) {
                if (g_cancelled.load(std::memory_order_relaxed)) throw Cancelled();
                notify_progress(
                    env,
                    callback,
                    progress_method,
                    0.04f + progress * 0.92f,
                    message
                );
            };
        return demucscpp::demucs_inference(model, audio, progress_callback);
    }

    log_info("Starting parallel Demucs with " + std::to_string(worker_count) + " workers");
    std::vector<std::unique_ptr<WorkerState>> states;
    states.reserve(worker_count);

    for (int index = 0; index < worker_count; ++index) {
        auto state = std::make_unique<WorkerState>();
        state->core_start =
            static_cast<int>((static_cast<std::int64_t>(total_frames) * index) / worker_count);
        state->core_end =
            static_cast<int>((static_cast<std::int64_t>(total_frames) * (index + 1)) / worker_count);
        state->segment_start = std::max(0, state->core_start - OVERLAP_FRAMES);
        state->segment_end = std::min(total_frames, state->core_end + OVERLAP_FRAMES);
        const int segment_frames = state->segment_end - state->segment_start;
        if (segment_frames <= 0) throw std::runtime_error("Parallel segment is empty");
        state->segment = audio.block(
            0,
            state->segment_start,
            CHANNELS,
            segment_frames
        );
        states.push_back(std::move(state));
    }

    std::atomic<int> completed_workers{0};
    std::vector<std::thread> threads;
    threads.reserve(worker_count);

    for (int index = 0; index < worker_count; ++index) {
        WorkerState *state = states[index].get();
        threads.emplace_back([&model, state, &completed_workers]() {
            try {
                demucscpp::ProgressCallback worker_progress =
                    [state](float progress, const std::string &) {
                        if (g_cancelled.load(std::memory_order_relaxed)) throw Cancelled();
                        state->progress.store(
                            std::clamp(progress, 0.0f, 1.0f),
                            std::memory_order_relaxed
                        );
                    };
                state->output =
                    demucscpp::demucs_inference(model, state->segment, worker_progress);
                state->progress.store(1.0f, std::memory_order_relaxed);
            } catch (...) {
                state->error = std::current_exception();
            }
            completed_workers.fetch_add(1, std::memory_order_release);
        });
    }

    while (completed_workers.load(std::memory_order_acquire) < worker_count) {
        float total_progress = 0.0f;
        for (const auto &state : states) {
            total_progress += state->progress.load(std::memory_order_relaxed);
        }
        const float average = total_progress / static_cast<float>(worker_count);
        notify_progress(
            env,
            callback,
            progress_method,
            0.04f + average * 0.88f,
            "Demucs parallel: " + std::to_string(worker_count) + " workers"
        );
        std::this_thread::sleep_for(std::chrono::milliseconds(120));
    }

    for (auto &thread : threads) {
        if (thread.joinable()) thread.join();
    }
    if (g_cancelled.load(std::memory_order_relaxed)) throw Cancelled();
    for (const auto &state : states) {
        if (state->error) std::rethrow_exception(state->error);
        if (
            state->output.dimension(0) < 4 ||
            state->output.dimension(1) != CHANNELS ||
            state->output.dimension(2) != state->segment.cols()
        ) {
            throw std::runtime_error("Parallel Demucs output shape is invalid");
        }
    }

    Eigen::Tensor3dXf final_output(4, CHANNELS, total_frames);
    final_output.setZero();
    Eigen::VectorXf sum_weights = Eigen::VectorXf::Zero(total_frames);

    for (const auto &state : states) {
        const int local_frames = state->segment_end - state->segment_start;
        for (int local = 0; local < local_frames; ++local) {
            const int global = state->segment_start + local;
            float weight = 1.0f;
            if (global < state->core_start) {
                weight = fade_in_weight(global, state->segment_start, state->core_start);
            } else if (global >= state->core_end) {
                weight = fade_out_weight(global, state->core_end, state->segment_end);
            }
            sum_weights(global) += weight;
            for (int source = 0; source < 4; ++source) {
                for (int channel = 0; channel < CHANNELS; ++channel) {
                    final_output(source, channel, global) +=
                        state->output(source, channel, local) * weight;
                }
            }
        }
    }

    for (int frame = 0; frame < total_frames; ++frame) {
        const float divisor = std::max(sum_weights(frame), 1.0e-8f);
        for (int source = 0; source < 4; ++source) {
            for (int channel = 0; channel < CHANNELS; ++channel) {
                final_output(source, channel, frame) /= divisor;
            }
        }
    }
    notify_progress(
        env,
        callback,
        progress_method,
        0.95f,
        "Merging parallel Demucs segments"
    );
    return final_output;
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
        if (!drums || !bass || !other) {
            throw std::runtime_error("Cannot open four-stem output PCM");
        }
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
        const std::streamsize bytes =
            static_cast<std::streamsize>(count * CHANNELS * sizeof(float));
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
        notify_progress(
            env,
            callback,
            progress_method,
            0.96f + ratio * 0.04f,
            "Writing output PCM"
        );
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
        jmethodID progress_method =
            env->GetMethodID(callback_class, "onProgress", "(FLjava/lang/String;)V");
        if (progress_method == nullptr) {
            throw std::runtime_error("Progress callback method is missing");
        }

        // Parallelism is controlled by independent Demucs workers. Keep Eigen itself
        // single-threaded to avoid N x M oversubscription on big.LITTLE mobile CPUs.
        Eigen::setNbThreads(1);
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

        Eigen::Tensor3dXf targets = parallel_inference(
            demucs_model,
            audio,
            static_cast<int>(thread_count),
            env,
            callback,
            progress_method
        );
        if (g_cancelled.load(std::memory_order_relaxed)) throw Cancelled();
        if (
            targets.dimension(0) < 4 ||
            targets.dimension(1) != CHANNELS ||
            targets.dimension(2) != audio.cols()
        ) {
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
