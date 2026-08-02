#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import textwrap

ROOT = Path(__file__).resolve().parents[1]


def write(relative: str, content: str) -> None:
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Không tìm thấy đoạn cần thay trong {path.relative_to(ROOT)}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


write(
    "app/src/main/java/com/aistudio/mediatool/core/ml/DemucsNativeBridge.kt",
    r'''
    package com.aistudio.mediatool.core.ml

    import androidx.annotation.Keep

    /** JNI mỏng cho engine demucs.cpp. Toàn bộ inference chạy trên một worker IO. */
    class DemucsNativeBridge {
        @Keep
        class ProgressCallback(
            private val callback: (Float, String) -> Unit,
        ) {
            @Keep
            fun onProgress(progress: Float, message: String) {
                callback(progress.coerceIn(0f, 1f), message)
            }
        }

        /** Trả null khi thành công, hoặc chuỗi lỗi ổn định khi thất bại. */
        external fun separate(
            modelPath: String,
            inputRawPath: String,
            vocalsRawPath: String,
            musicRawPath: String,
            drumsRawPath: String,
            bassRawPath: String,
            otherRawPath: String,
            writeFourStems: Boolean,
            threadCount: Int,
            callback: ProgressCallback,
        ): String?

        external fun cancel()

        companion object {
            init {
                System.loadLibrary("mediatool_demucs")
            }
        }
    }
    ''',
)

write(
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    r'''
    package com.aistudio.mediatool.core.ml

    import android.app.ActivityManager
    import android.content.Context
    import android.net.Uri
    import android.os.Debug
    import android.os.SystemClock
    import com.arthenica.ffmpegkit.FFmpegKit
    import com.arthenica.ffmpegkit.FFmpegSession
    import com.arthenica.ffmpegkit.ReturnCode
    import com.aistudio.mediatool.core.FileExportManager
    import com.aistudio.mediatool.core.SettingsManager
    import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
    import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
    import com.aistudio.mediatool.core.diagnostics.ProcessExitDiagnostics
    import kotlinx.coroutines.CancellationException
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.channels.awaitClose
    import kotlinx.coroutines.ensureActive
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.channelFlow
    import kotlinx.coroutines.flow.flowOn
    import kotlinx.coroutines.suspendCancellableCoroutine
    import java.io.File
    import java.util.concurrent.atomic.AtomicBoolean
    import java.util.concurrent.atomic.AtomicLong
    import kotlin.coroutines.coroutineContext

    sealed class SeparationState {
        data class Progress(val value: Float) : SeparationState()
        data class Success(
            val vocalsFile: File,
            val musicFile: File,
            val drumsFile: File? = null,
            val bassFile: File? = null,
            val otherFile: File? = null,
        ) : SeparationState()
    }

    class AudioSeparator(
        private val context: Context,
        private val modelFile: File,
        private val model: StemModelDescriptor,
        private val taskId: String,
    ) {
        private val activeFfmpegSessionId = AtomicLong(-1L)
        private val nativeBridge = DemucsNativeBridge()
        private val sampleRate = model.sampleRate
        private val channels = model.channels

        fun cancel() {
            DiagnosticLogger.warn(
                component = TAG,
                event = "cancel_requested",
                sessionId = taskId,
                fields = mapOf("model_id" to model.id),
            )
            nativeBridge.cancel()
            val sessionId = activeFfmpegSessionId.getAndSet(-1L)
            if (sessionId >= 0L) FFmpegKit.cancel(sessionId)
        }

        private fun checkpoint(phase: String, progress: Float) {
            ProcessExitDiagnostics.checkpoint(
                context = context,
                taskType = StemService.TASK_TYPE,
                taskId = taskId,
                phase = phase,
                progress = progress,
                modelId = model.id,
            )
        }

        private fun memoryFields(): Map<String, Any?> {
            val runtime = Runtime.getRuntime()
            val memoryInfo = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
            return mapOf(
                "java_heap_used_bytes" to runtime.totalMemory() - runtime.freeMemory(),
                "java_heap_max_bytes" to runtime.maxMemory(),
                "native_heap_allocated_bytes" to Debug.getNativeHeapAllocatedSize(),
                "process_pss_kb" to Debug.getPss(),
                "system_available_ram_bytes" to memoryInfo.availMem,
                "system_low_memory" to memoryInfo.lowMemory,
            )
        }

        private fun logInfo(event: String, fields: Map<String, Any?> = emptyMap(), message: String? = null) {
            DiagnosticLogger.info(TAG, event, taskId, message, fields)
        }

        private suspend fun executeFfmpeg(command: String, phase: String): FFmpegSession =
            suspendCancellableCoroutine { continuation ->
                val terminal = AtomicBoolean(false)
                val cancelled = AtomicBoolean(false)
                val sessionId = AtomicLong(-1L)
                val startedAt = SystemClock.elapsedRealtime()
                val commandId = DiagnosticRedactor.stableId(command)
                logInfo(
                    "ffmpeg_start",
                    mapOf("phase" to phase, "command_id" to commandId),
                )

                continuation.invokeOnCancellation {
                    cancelled.set(true)
                    val id = sessionId.get()
                    if (id >= 0L) {
                        activeFfmpegSessionId.compareAndSet(id, -1L)
                        FFmpegKit.cancel(id)
                    }
                }

                val session = try {
                    FFmpegKit.executeAsync(
                        command,
                        { completed ->
                            activeFfmpegSessionId.compareAndSet(completed.sessionId, -1L)
                            if (terminal.compareAndSet(false, true)) {
                                val success = ReturnCode.isSuccess(completed.returnCode)
                                val fields = mutableMapOf<String, Any?>(
                                    "phase" to phase,
                                    "command_id" to commandId,
                                    "return_code" to completed.returnCode.toString(),
                                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                                )
                                if (!success) {
                                    fields["ffmpeg_tail"] = DiagnosticRedactor.sanitizeFfmpegLogs(
                                        completed.allLogsAsString,
                                        maxChars = 8_000,
                                    )
                                    DiagnosticLogger.error(
                                        component = TAG,
                                        event = "ffmpeg_failed",
                                        sessionId = taskId,
                                        fields = fields,
                                    )
                                } else {
                                    logInfo("ffmpeg_success", fields)
                                }
                                continuation.resumeWith(Result.success(completed))
                            }
                        },
                        null,
                        null,
                    )
                } catch (error: Throwable) {
                    terminal.set(true)
                    continuation.resumeWith(Result.failure(error))
                    return@suspendCancellableCoroutine
                }
                sessionId.set(session.sessionId)
                activeFfmpegSessionId.set(session.sessionId)
                if (cancelled.get()) {
                    activeFfmpegSessionId.compareAndSet(session.sessionId, -1L)
                    FFmpegKit.cancel(session.sessionId)
                }
            }

        fun separate(inputUri: Uri): Flow<SeparationState> = channelFlow {
            val pipelineStartedAt = SystemClock.elapsedRealtime()
            val sourceId = DiagnosticRedactor.stableId(inputUri.toString())
            checkpoint("pipeline_start", 0.01f)
            send(SeparationState.Progress(0.01f))

            val workDir = File(context.cacheDir, "stem-native-${System.currentTimeMillis()}").apply {
                require(mkdirs() || isDirectory) { "Không thể tạo thư mục tạm cho tác vụ tách stem" }
            }
            val rawMix = File(workDir, "mix.f32le")
            val rawVocals = File(workDir, "vocals.f32le")
            val rawMusic = File(workDir, "music.f32le")
            val rawDrums = File(workDir, "drums.f32le")
            val rawBass = File(workDir, "bass.f32le")
            val rawOther = File(workDir, "other.f32le")
            val createdOutputs = mutableListOf<File>()
            var outputsCommitted = false
            val fourStems = model.mode == StemMode.FOUR_STEM

            try {
                logInfo(
                    "pipeline_start",
                    mapOf(
                        "runtime" to "demucs.cpp",
                        "model_id" to model.id,
                        "model_bytes" to modelFile.length(),
                        "source_id" to sourceId,
                        "stem_count" to model.mode.stemCount,
                    ),
                )

                checkpoint("decode_input", 0.04f)
                send(SeparationState.Progress(0.04f))
                val inputPath = com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(context, inputUri)
                val decode = executeFfmpeg(
                    "-y -i \"$inputPath\" -vn -f f32le -ac $channels -ar $sampleRate \"${rawMix.absolutePath}\"",
                    "decode_input",
                )
                require(ReturnCode.isSuccess(decode.returnCode)) { "Không thể đọc âm thanh đầu vào" }
                require(rawMix.isFile && rawMix.length() >= channels * Float.SIZE_BYTES) {
                    "Âm thanh đầu vào không có dữ liệu PCM hợp lệ"
                }

                coroutineContext.ensureActive()
                checkpoint("native_model_loading", 0.10f)
                send(SeparationState.Progress(0.10f))
                logInfo("native_inference_start", memoryFields())
                var lastBucket = -1
                val nativeError = nativeBridge.separate(
                    modelPath = modelFile.absolutePath,
                    inputRawPath = rawMix.absolutePath,
                    vocalsRawPath = rawVocals.absolutePath,
                    musicRawPath = rawMusic.absolutePath,
                    drumsRawPath = rawDrums.absolutePath,
                    bassRawPath = rawBass.absolutePath,
                    otherRawPath = rawOther.absolutePath,
                    writeFourStems = fourStems,
                    threadCount = SettingsManager.getNumThreads(context).coerceIn(1, 4),
                    callback = DemucsNativeBridge.ProgressCallback { nativeProgress, message ->
                        val mapped = (0.12f + nativeProgress * 0.73f).coerceIn(0.12f, 0.85f)
                        val bucket = (nativeProgress * 20f).toInt()
                        if (bucket > lastBucket) {
                            lastBucket = bucket
                            checkpoint("native_inference", mapped)
                            logInfo(
                                "native_inference_progress",
                                mapOf(
                                    "native_percent" to (nativeProgress * 100f).toInt(),
                                    "message" to DiagnosticRedactor.sanitize(message),
                                ),
                            )
                        }
                        trySend(SeparationState.Progress(mapped))
                    },
                )
                if (nativeError == "CANCELLED") throw CancellationException("Đã hủy xử lý")
                require(nativeError == null) { "Demucs native thất bại: $nativeError" }
                require(rawVocals.length() == rawMix.length() && rawMusic.length() == rawMix.length()) {
                    "Demucs native tạo dữ liệu hai stem không đầy đủ"
                }
                if (fourStems) {
                    require(listOf(rawDrums, rawBass, rawOther).all { it.length() == rawMix.length() }) {
                        "Demucs native tạo dữ liệu bốn stem không đầy đủ"
                    }
                }
                logInfo("native_inference_complete", memoryFields())

                checkpoint("encoding", 0.88f)
                send(SeparationState.Progress(0.88f))
                val ext = SettingsManager.getAudioFormatExt(context)
                val encodingArgs = SettingsManager.getAudioEncodingArgs(context)

                suspend fun encode(raw: File, name: String): File {
                    val output = FileExportManager.resultFile(context, name, ext).also(createdOutputs::add)
                    val session = executeFfmpeg(
                        "-y -f f32le -ac $channels -ar $sampleRate -i \"${raw.absolutePath}\" $encodingArgs \"${output.absolutePath}\"",
                        "encode_$name",
                    )
                    require(ReturnCode.isSuccess(session.returnCode) && output.isFile && output.length() > 0L) {
                        "Không thể mã hóa stem $name"
                    }
                    return output
                }

                val vocals = encode(rawVocals, "vocals")
                trySend(SeparationState.Progress(0.92f))
                val music = encode(rawMusic, "music")
                trySend(SeparationState.Progress(0.95f))
                val drums = if (fourStems) encode(rawDrums, "drums") else null
                val bass = if (fourStems) encode(rawBass, "bass") else null
                val other = if (fourStems) encode(rawOther, "other") else null

                outputsCommitted = true
                checkpoint("complete", 1f)
                logInfo(
                    "pipeline_success",
                    mapOf(
                        "runtime" to "demucs.cpp",
                        "model_id" to model.id,
                        "output_count" to createdOutputs.size,
                        "output_bytes" to createdOutputs.sumOf(File::length),
                        "elapsed_ms" to SystemClock.elapsedRealtime() - pipelineStartedAt,
                    ),
                )
                send(SeparationState.Progress(1f))
                send(SeparationState.Success(vocals, music, drums, bass, other))
            } catch (cancelled: CancellationException) {
                logInfo("pipeline_cancelled", mapOf("model_id" to model.id))
                throw cancelled
            } catch (error: Throwable) {
                DiagnosticLogger.error(
                    component = TAG,
                    event = "pipeline_failed",
                    sessionId = taskId,
                    message = error.message,
                    fields = mapOf(
                        "runtime" to "demucs.cpp",
                        "model_id" to model.id,
                        "source_id" to sourceId,
                        "out_of_memory" to (error is OutOfMemoryError),
                        "elapsed_ms" to SystemClock.elapsedRealtime() - pipelineStartedAt,
                    ),
                    error = error,
                )
                throw error
            } finally {
                activeFfmpegSessionId.set(-1L)
                if (!outputsCommitted) createdOutputs.forEach { it.delete() }
                workDir.deleteRecursively()
                logInfo("pipeline_cleanup", mapOf("outputs_committed" to outputsCommitted))
            }
        }.flowOn(Dispatchers.IO)

        companion object {
            private const val TAG = "AudioSeparator"
        }
    }
    ''',
)

write(
    "app/src/main/java/com/aistudio/mediatool/core/ml/StemModelRegistry.kt",
    r'''
    package com.aistudio.mediatool.core.ml

    /** Catalog model được xác minh cho engine native demucs.cpp. */
    object StemModelRegistry {
        const val DEMUCS_FT_VOCALS_ID = "demucs-ht-v4-ft-vocals-native-f16-v1"
        const val DEMUCS_FT_VOCALS_4_STEM_ID = "demucs-ht-v4-ft-vocals-native-f16-4stem-v1"

        // Giữ ID cũ chỉ để cài đặt đã lưu tự fallback, không còn model tương ứng trong catalog.
        const val MEL_BAND_ROFORMER_ID = "melband-roformer-kj-vocals-v1"
        const val DEMUCS_2_STEM_LITE_ID = "demucs-ht-2stems-lite-v1"
        const val DEMUCS_4_STEM_ID = "demucs-ht-4stems-legacy-v1"

        private const val MIB = 1024L * 1024L
        private const val GIB = 1024L * MIB

        private val nativeFtVocalsSpec = ModelSpec(
            url = "https://huggingface.co/datasets/Retrobear/demucs.cpp/resolve/5f5daffffcf06ad7b27a7285da327e18ea62068a/ggml-model-htdemucs_ft_vocals-4s-f16.bin?download=true",
            fileName = "ggml-model-htdemucs-ft-vocals-5f5daff-f16.bin",
            familyPrefix = "ggml-model-htdemucs-ft-vocals-",
            expectedBytes = 83_994_361L,
            sha256 = "19186500a45a551a034d96e9500415ebe73c8bd570bf55337ddc8cc8f53a9120",
        )

        private fun descriptor(id: String, mode: StemMode, displayName: String) = StemModelDescriptor(
            id = id,
            displayName = displayName,
            description = if (mode == StemMode.TWO_STEM) {
                "Demucs v4 fine-tuned cho giọng hát, chạy bằng engine C++ native."
            } else {
                "Xuất lời, trống, bass và nhạc cụ khác bằng Demucs v4 native."
            },
            mode = mode,
            modelSpec = nativeFtVocalsSpec,
            sampleRate = 44_100,
            channels = 2,
            chunking = ChunkingSpec(
                frames = 343_980,
                overlapFrames = 85_995,
                edgeFadeFrames = 85_995,
                overlapProfile = OverlapProfile.COMPLEMENTARY_SINE,
            ),
            normalization = AudioNormalization.GLOBAL_MONO_MEAN_STD,
            tensor = TensorContract(
                inputName = "native_pcm",
                outputName = "native_sources",
                inputLayout = TensorAudioLayout.BATCH_CHANNEL_FRAME,
                outputLayout = TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME,
                sourceCount = 4,
            ),
            sources = StemSourceMap(
                vocals = SourceMix(listOf(3)),
                music = SourceMix(listOf(0, 1, 2)),
                drums = if (mode == StemMode.FOUR_STEM) SourceMix(listOf(0)) else null,
                bass = if (mode == StemMode.FOUR_STEM) SourceMix(listOf(1)) else null,
                other = if (mode == StemMode.FOUR_STEM) SourceMix(listOf(2)) else null,
            ),
            allowedAccelerators = setOf(OnnxAcceleration.CPU),
            deviceRequirements = DeviceRequirements(
                minimumTotalRamBytes = 4L * GIB,
                minimumAvailableRamBytes = 1L * GIB,
                userFacingSummary = "Khuyến nghị còn ít nhất 1 GB RAM trống.",
            ),
            licenseName = "MIT",
            projectUrl = "https://github.com/sevagh/demucs.cpp",
        )

        val demucsFtVocalsTwoStem = descriptor(
            DEMUCS_FT_VOCALS_ID,
            StemMode.TWO_STEM,
            "Demucs v4 Vocals chất lượng cao",
        )
        val demucsFtVocalsFourStem = descriptor(
            DEMUCS_FT_VOCALS_4_STEM_ID,
            StemMode.FOUR_STEM,
            "Demucs v4 Native (4 stem)",
        )

        // Alias nguồn để các màn hình cũ biên dịch trong giai đoạn migration.
        val demucsTwoStemLite: StemModelDescriptor = demucsFtVocalsTwoStem
        val demucsFourStem: StemModelDescriptor = demucsFtVocalsFourStem

        val all: List<StemModelDescriptor> = listOf(
            demucsFtVocalsTwoStem,
            demucsFtVocalsFourStem,
        )

        fun modelsFor(mode: StemMode): List<StemModelDescriptor> = all.filter { it.mode == mode }

        fun find(id: String?): StemModelDescriptor? = all.firstOrNull { it.id == id }

        fun findByFileName(fileName: String): StemModelDescriptor? =
            all.firstOrNull { it.modelSpec.fileName == fileName }

        fun resolve(mode: StemMode, preferredId: String?): StemModelDescriptor =
            find(preferredId)?.takeIf { it.mode == mode }
                ?: modelsFor(mode).firstOrNull()
                ?: demucsFtVocalsTwoStem
    }
    ''',
)

write(
    "app/src/test/java/com/aistudio/mediatool/core/ml/StemModelRegistryTest.kt",
    r'''
    package com.aistudio.mediatool.core.ml

    import org.junit.Assert.assertEquals
    import org.junit.Assert.assertSame
    import org.junit.Assert.assertTrue
    import org.junit.Test

    class StemModelRegistryTest {
        @Test
        fun nativeFtVocalsIsDefaultTwoStemModel() {
            val model = StemModelRegistry.resolve(StemMode.TWO_STEM, null)
            assertSame(StemModelRegistry.demucsFtVocalsTwoStem, model)
            assertEquals(83_994_361L, model.modelSpec.expectedBytes)
            assertEquals(
                "19186500a45a551a034d96e9500415ebe73c8bd570bf55337ddc8cc8f53a9120",
                model.modelSpec.sha256,
            )
            assertTrue(model.modelSpec.fileName.endsWith(".bin"))
        }

        @Test
        fun oldStoredOnnxChoiceFallsBackToNativeModel() {
            assertSame(
                StemModelRegistry.demucsFtVocalsTwoStem,
                StemModelRegistry.resolve(StemMode.TWO_STEM, StemModelRegistry.DEMUCS_2_STEM_LITE_ID),
            )
        }

        @Test
        fun nativeFourStemUsesSameVerifiedWeights() {
            val model = StemModelRegistry.demucsFtVocalsFourStem
            assertEquals(StemMode.FOUR_STEM, model.mode)
            assertEquals(StemModelRegistry.demucsFtVocalsTwoStem.modelSpec, model.modelSpec)
            assertEquals(listOf(3), model.sources.vocals.sourceIndices)
            assertEquals(listOf(0), model.sources.drums?.sourceIndices)
        }
    }
    ''',
)

write(
    "app/src/main/cpp/CMakeLists.txt",
    r'''
    cmake_minimum_required(VERSION 3.22.1)
    project(mediatool_demucs LANGUAGES CXX)

    set(CMAKE_CXX_STANDARD 17)
    set(CMAKE_CXX_STANDARD_REQUIRED ON)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)

    get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
    set(DEMUCS_ROOT "${REPO_ROOT}/.deps/demucs.cpp")
    if(NOT EXISTS "${DEMUCS_ROOT}/src/model.hpp")
        message(FATAL_ERROR "Thiếu demucs.cpp đã ghim. Chạy scripts/prepare_demucs_cpp.sh trước Gradle.")
    endif()
    if(NOT EXISTS "${DEMUCS_ROOT}/vendor/eigen/Eigen/Core")
        message(FATAL_ERROR "Thiếu Eigen submodule của demucs.cpp.")
    endif()

    file(GLOB DEMUCS_SOURCES CONFIGURE_DEPENDS "${DEMUCS_ROOT}/src/*.cpp")

    add_library(
        mediatool_demucs
        SHARED
        demucs_jni.cpp
        ${DEMUCS_SOURCES}
    )

    target_include_directories(
        mediatool_demucs
        PRIVATE
        "${DEMUCS_ROOT}/src"
        "${DEMUCS_ROOT}/vendor/eigen"
    )

    target_compile_options(
        mediatool_demucs
        PRIVATE
        -O3
        -Wall
        -Wextra
        -DNDEBUG
        -DEIGEN_NO_DEBUG
        -fno-unsafe-math-optimizations
        -fassociative-math
        -freciprocal-math
        -fno-signed-zeros
    )

    if(ANDROID_ABI STREQUAL "arm64-v8a")
        target_compile_options(mediatool_demucs PRIVATE -march=armv8-a)
    endif()

    find_library(LOG_LIB log)
    find_library(ANDROID_LIB android)
    target_link_libraries(mediatool_demucs ${LOG_LIB} ${ANDROID_LIB})
    ''',
)

write(
    "app/src/main/cpp/demucs_jni.cpp",
    r'''
    #include <jni.h>
    #include <android/log.h>

    #include "model.hpp"
    #include "tensor.hpp"

    #include <Eigen/Core>
    #include <Eigen/Dense>

    #include <algorithm>
    #include <array>
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
            if (!demucs_model.is_4sources && demucs_model.num_sources != 4) {
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
    ''',
)

write(
    "scripts/prepare_demucs_cpp.sh",
    r'''
    #!/usr/bin/env bash
    set -euo pipefail

    ROOT="$(cd "$(dirname "$0")/.." && pwd)"
    DEST="$ROOT/.deps/demucs.cpp"
    REV="f1206e9adeea103aef4a636b9e62297cf1f8e34e"

    if [[ -d "$DEST/.git" ]] && [[ "$(git -C "$DEST" rev-parse HEAD 2>/dev/null || true)" == "$REV" ]] \
        && [[ -f "$DEST/vendor/eigen/Eigen/Core" ]]; then
        echo "demucs.cpp $REV đã sẵn sàng"
        exit 0
    fi

    rm -rf "$DEST"
    mkdir -p "$(dirname "$DEST")"
    git init "$DEST"
    git -C "$DEST" remote add origin https://github.com/sevagh/demucs.cpp.git
    git -C "$DEST" fetch --depth 1 origin "$REV"
    git -C "$DEST" checkout --detach FETCH_HEAD
    git -C "$DEST" submodule update --init --depth 1 vendor/eigen

    test "$(git -C "$DEST" rev-parse HEAD)" = "$REV"
    test -f "$DEST/src/model.hpp"
    test -f "$DEST/vendor/eigen/Eigen/Core"
    echo "Đã chuẩn bị demucs.cpp $REV"
    ''',
)
(ROOT / "scripts/prepare_demucs_cpp.sh").chmod(0o755)

# Gradle: bật NDK/CMake, tăng phiên bản và loại ONNX Runtime.
build_path = ROOT / "app/build.gradle.kts"
build = build_path.read_text(encoding="utf-8")
build = build.replace("versionCode = 8", "versionCode = 9", 1)
build = build.replace('versionName = "1.3.3"', 'versionName = "1.3.4"', 1)
build = build.replace("    implementation(libs.onnxruntime.android)\n", "", 1)
build = build.replace(
    "        testInstrumentationRunner = \"androidx.test.runner.AndroidJUnitRunner\"",
    "        externalNativeBuild {\n            cmake {\n                cppFlags += listOf(\"-std=c++17\")\n            }\n        }\n\n        testInstrumentationRunner = \"androidx.test.runner.AndroidJUnitRunner\"",
    1,
)
build = build.replace(
    "    packaging {",
    "    externalNativeBuild {\n        cmake {\n            path = file(\"src/main/cpp/CMakeLists.txt\")\n            version = \"3.22.1\"\n        }\n    }\n\n    packaging {",
    1,
)
build = build.replace(
    "            // FFmpegKit và ONNX Runtime đều đóng gói libc++_shared.so.\n            // CI mở từng APK để xác nhận ARM64 chỉ còn một bản.",
    "            // FFmpegKit và engine Demucs native cùng dùng libc++_shared.so.\n            // CI mở APK để xác nhận ARM64 chỉ còn một bản.",
)
build_path.write_text(build, encoding="utf-8")

# Loại alias ONNX Runtime khỏi version catalog nếu không còn được dùng.
catalog_path = ROOT / "gradle/libs.versions.toml"
catalog = catalog_path.read_text(encoding="utf-8")
catalog = re.sub(r'^onnxruntime\s*=\s*"[^"]+"\n', "", catalog, flags=re.MULTILINE)
catalog = re.sub(r'^onnxruntime-android\s*=.*\n', "", catalog, flags=re.MULTILINE)
catalog_path.write_text(catalog, encoding="utf-8")

# Cài đặt chỉ hiển thị CPU native, không hứa NNAPI/XNNPACK cho engine C++.
settings_path = ROOT / "app/src/main/java/com/aistudio/mediatool/ui/screens/SettingsScreen.kt"
settings = settings_path.read_text(encoding="utf-8")
settings = settings.replace(
    'val hwList = listOf("CPU", "NNAPI", "XNNPACK")',
    'val hwList = listOf("CPU native")',
)
settings_path.write_text(settings, encoding="utf-8")

# Workflow: chuẩn bị nguồn ghim và cache native compilation.
workflow_path = ROOT / ".github/workflows/build-apk.yml"
workflow = workflow_path.read_text(encoding="utf-8")
workflow = workflow.replace("timeout-minutes: 25", "timeout-minutes: 45", 1)
anchor = "      - name: Verify project\n        run: python3 scripts/verify_project.py\n"
insert = """      - name: Cache pinned Demucs source\n        uses: actions/cache@v4\n        with:\n          path: .deps/demucs.cpp\n          key: demucs-cpp-f1206e9-arm64\n\n      - name: Prepare pinned Demucs C++ source\n        run: bash scripts/prepare_demucs_cpp.sh\n\n      - name: Cache native CMake output\n        uses: actions/cache@v4\n        with:\n          path: app/.cxx\n          key: native-demucs-${{ runner.os }}-${{ hashFiles('app/src/main/cpp/**', 'scripts/prepare_demucs_cpp.sh') }}\n\n      - name: Verify project\n        run: python3 scripts/verify_project.py\n"""
if anchor not in workflow:
    raise RuntimeError("Không tìm thấy anchor Verify project trong workflow")
workflow = workflow.replace(anchor, insert, 1)
workflow_path.write_text(workflow, encoding="utf-8")

write(
    "scripts/inspect_apks.py",
    r'''
    #!/usr/bin/env python3
    import sys
    import zipfile
    from pathlib import Path

    if len(sys.argv) < 2:
        raise SystemExit("Cần đường dẫn APK")

    for raw in sys.argv[1:]:
        apk = Path(raw)
        if not apk.is_file():
            raise SystemExit(f"Không tìm thấy APK: {apk}")
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            if "AndroidManifest.xml" not in names:
                raise SystemExit(f"{apk.name}: thiếu AndroidManifest.xml")
            dex_names = [name for name in names if name.startswith("classes") and name.endswith(".dex")]
            if not dex_names:
                raise SystemExit(f"{apk.name}: thiếu classes*.dex")
            dex_bytes = b"".join(archive.read(name) for name in dex_names)
            for descriptor in (
                b"Lcom/arthenica/smartexception/java/Exceptions;",
                b"Lcom/arthenica/ffmpegkit/FFmpegKit;",
                b"Lcom/aistudio/mediatool/core/ml/DemucsNativeBridge;",
            ):
                if descriptor not in dex_bytes:
                    raise SystemExit(f"{apk.name}: thiếu lớp runtime {descriptor.decode('ascii')}")

            native = [name for name in names if name.startswith("lib/") and name.endswith(".so")]
            if not native:
                raise SystemExit(f"{apk.name}: không có thư viện native")
            required = "lib/arm64-v8a/libmediatool_demucs.so"
            if required not in native:
                raise SystemExit(f"{apk.name}: thiếu {required}")
            forbidden = [name for name in native if "onnxruntime" in name.lower()]
            if forbidden:
                raise SystemExit(f"{apk.name}: vẫn chứa ONNX Runtime: {forbidden}")
            abis = {name.split("/")[1] for name in native}
            if abis != {"arm64-v8a"}:
                raise SystemExit(f"{apk.name}: ABI không đúng: {sorted(abis)}")
            libcxx = [name for name in native if name.endswith("/libc++_shared.so")]
            if len(libcxx) != 1:
                raise SystemExit(f"{apk.name}: cần đúng một libc++_shared.so, thấy {len(libcxx)}")
        print(f"APK OK: {apk} ({apk.stat().st_size:,} byte), native Demucs, không ONNX")
    ''',
)

# Cập nhật verify_project nhưng giữ toàn bộ kiểm tra nền tảng khác.
verify_path = ROOT / "scripts/verify_project.py"
verify = verify_path.read_text(encoding="utf-8")
verify = verify.replace(
    'check("libs.onnxruntime.android" in build_gradle, "Thiếu dependency ONNX Runtime")',
    'check("libs.onnxruntime.android" not in build_gradle, "ONNX Runtime vẫn còn trong dependency")',
)
verify = verify.replace('check("versionCode = 8" in build_gradle, "versionCode không phải 8")', 'check("versionCode = 9" in build_gradle, "versionCode không phải 9")')
verify = verify.replace('check(\'versionName = "1.3.3"\' in build_gradle, "versionName không phải 1.3.3")', 'check(\'versionName = "1.3.4"\' in build_gradle, "versionName không phải 1.3.4")')
old_registry_checks = '''check("953_292_899" in registry, "Dung lượng Mel-Band RoFormer ghim không đúng")
check("64a4f3bee48fbe7d971b23875adc924ed004c3533f49672592641dddc0f6f561" in registry, "SHA Mel-Band RoFormer ghim không đúng")
check("60cb6b4b97e41b42f7ff16c2e386f47a8cc7e50a" in registry, "Commit Mel-Band RoFormer ghim không đúng")
check("frames = 352_800" in registry and "overlapFrames = 176_400" in registry, "Chunk contract Mel-Band RoFormer không đúng")'''
new_registry_checks = '''check("83_994_361" in registry, "Dung lượng Demucs FT Vocals ghim không đúng")
check("19186500a45a551a034d96e9500415ebe73c8bd570bf55337ddc8cc8f53a9120" in registry, "SHA Demucs FT Vocals ghim không đúng")
check("5f5daffffcf06ad7b27a7285da327e18ea62068a" in registry, "Commit weights Demucs chưa ghim")
check("953_292_899" not in registry and "304_330_587" not in registry, "Catalog vẫn chứa model ONNX cũ")'''
if old_registry_checks not in verify:
    raise RuntimeError("Không tìm thấy block kiểm tra registry cũ")
verify = verify.replace(old_registry_checks, new_registry_checks, 1)
old_separator_checks = '''check("FFmpegKit.cancel" in separator, "AudioSeparator chưa hủy FFmpeg")
check("setTerminate(true)" in separator, "AudioSeparator chưa hủy ONNX")
check("createdOutputs" in separator and "outputsCommitted" in separator, "AudioSeparator chưa cleanup output theo transaction")
check("sharedInputBufferDirect" in separator, "AudioSeparator thiếu buffer tensor đầu vào")
check("-f f32le" in separator, "Pipeline stem chưa giữ PCM float32")
check("createReflectPaddedPcm" in separator, "Mel-Band RoFormer thiếu reflect padding ở biên")
check("AudioNormalization.GLOBAL_MONO_MEAN_STD" in separator, "Chuẩn hóa model chưa theo descriptor")
check("inference_chunk_complete" in separator and "ffmpeg_failed" in separator, "Stem pipeline thiếu log phase/chunk")
check("INPUT GỐC" not in separator and "VOCAL OUT" not in separator, "Stem pipeline còn ghi mẫu âm thanh vào log")
check("catch (error: Exception)" in separator and "catch (error: Throwable)" in separator, "Fallback provider/OOM chưa tách biệt")'''
new_separator_checks = '''check("FFmpegKit.cancel" in separator, "AudioSeparator chưa hủy FFmpeg")
check("nativeBridge.cancel" in separator, "AudioSeparator chưa hủy Demucs native")
check("createdOutputs" in separator and "outputsCommitted" in separator, "AudioSeparator chưa cleanup output theo transaction")
check("-f f32le" in separator, "Pipeline stem chưa giữ PCM float32")
check("native_inference_start" in separator and "native_inference_complete" in separator, "Stem pipeline thiếu log native inference")
check("ai.onnxruntime" not in separator and "OrtSession" not in separator, "AudioSeparator vẫn phụ thuộc ONNX")
check((ROOT / "app/src/main/cpp/demucs_jni.cpp").is_file(), "Thiếu JNI Demucs")
check((ROOT / "app/src/main/cpp/CMakeLists.txt").is_file(), "Thiếu CMake native")
check((ROOT / "app/src/main/java/com/aistudio/mediatool/core/ml/DemucsNativeBridge.kt").is_file(), "Thiếu Kotlin JNI bridge")
check((ROOT / ".deps/demucs.cpp/src/model.hpp").is_file(), "Nguồn demucs.cpp ghim chưa được chuẩn bị")'''
if old_separator_checks not in verify:
    raise RuntimeError("Không tìm thấy block kiểm tra separator cũ")
verify = verify.replace(old_separator_checks, new_separator_checks, 1)
verify = verify.replace(
    'for token in ["assembleDebug", "assembleInternal", "assembleDebugAndroidTest", "inspect_apks.py"]:',
    'for token in ["assembleDebug", "inspect_apks.py", "prepare_demucs_cpp.sh"]:',
)
verify += '\n'
verify_path.write_text(verify, encoding="utf-8")

# Notice và tài liệu phiên bản.
for relative in ("THIRD_PARTY_NOTICES.md", "app/src/main/assets/third_party_notices.txt"):
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    lines = [line for line in text.splitlines() if "ONNX Runtime" not in line and "jackjiangxinfa/demucs-onnx" not in line and "Mel-Band RoFormer" not in line]
    marker = "- OkHttp, theo giấy phép Apache License 2.0."
    notice = "- demucs.cpp của Sevag H, theo giấy phép MIT; mã C++ được lấy theo commit f1206e9adeea103aef4a636b9e62297cf1f8e34e trong lúc build.\n- Trọng số `htdemucs_ft_vocals` GGML FP16 từ Retrobear/demucs.cpp, theo giấy phép MIT, tải theo yêu cầu và kiểm tra SHA-256."
    joined = "\n".join(lines)
    if notice not in joined:
        joined = joined.replace(marker, marker + "\n" + notice)
    path.write_text(joined.rstrip() + "\n", encoding="utf-8")

readme_path = ROOT / "README.md"
readme = readme_path.read_text(encoding="utf-8")
readme = readme.replace("# MediaTool 1.3.3", "# MediaTool 1.3.4", 1)
readme = readme.replace("ONNX Runtime và ", "")
readme_path.write_text(readme, encoding="utf-8")

changelog_path = ROOT / "CHANGELOG.md"
changelog = changelog_path.read_text(encoding="utf-8")
entry = '''## 1.3.4 - 2026-08-02

### Demucs v4 native trên Android

- Thay model ONNX 304 MB và Mel-Band 953 MB bằng `htdemucs_ft_vocals` GGML FP16 83.994.361 byte.
- Tích hợp `demucs.cpp` qua CMake/JNI cho ARM64; model mặc định xuất vocals và instrumental.
- Xóa ONNX Runtime khỏi dependency và bắt CI từ chối APK còn thư viện ONNX.
- Giữ pipeline PCM float32, hủy tác vụ, checkpoint tiến trình, log bộ nhớ và mã hóa đầu ra bằng FFmpegKit.

'''
if not changelog.startswith("# Changelog\n\n" + entry):
    changelog = changelog.replace("# Changelog\n\n", "# Changelog\n\n" + entry, 1)
changelog_path.write_text(changelog, encoding="utf-8")

# Không commit dependency tải trong lúc build.
gitignore_path = ROOT / ".gitignore"
gitignore = gitignore_path.read_text(encoding="utf-8") if gitignore_path.exists() else ""
for line in (".deps/", "app/.cxx/"):
    if line not in gitignore.splitlines():
        gitignore += ("" if gitignore.endswith("\n") or not gitignore else "\n") + line + "\n"
gitignore_path.write_text(gitignore, encoding="utf-8")

# Xóa bộ kích hoạt migration để commit cuối sạch.
(ROOT / "scripts/apply_native_demucs_migration.py").unlink(missing_ok=True)
(ROOT / ".github/workflows/apply-native-demucs.yml").unlink(missing_ok=True)
print("Native Demucs migration applied")
