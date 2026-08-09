#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def patch(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}\n--- needle ---\n{old[:800]}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")
    print(f"patched {path}")


# 1) Realtime recording: input owns its own callback instead of being polled from
# the output callback with zero padding on short non-blocking reads.
audio_cpp = "app/src/main/cpp/studio/studio_audio_engine.cpp"
patch(
    audio_cpp,
    '''        for (int attempt = 0; attempt < 8 && !inputScratch_.empty(); ++attempt) {
            const int32_t drainFrames = std::min<int32_t>(1024, static_cast<int32_t>(inputScratch_.size()));
            auto drained = inputStream_->read(inputScratch_.data(), drainFrames, 0);
            if (drained != oboe::Result::OK || drained.value() <= 0) break;
        }

''',
    '''''',
)
patch(
    audio_cpp,
    '''    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override {
        if (audioData == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;
        const int64_t requestedSeek = pendingSeekFrame_.exchange(kNoSeek);
''',
    '''    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override {
        if (audioData == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;
        if (audioStream == inputStream_.get()) {
            return captureInputCallback(audioData, numFrames);
        }
        const int64_t requestedSeek = pendingSeekFrame_.exchange(kNoSeek);
''',
)
patch(audio_cpp, '''\n        captureInput(numFrames);\n\n''', '''\n''')
patch(
    audio_cpp,
    '''        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        builder.setErrorCallback(this);
''',
    '''        builder.setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        builder.setDataCallback(this);
        builder.setErrorCallback(this);
''',
)
patch(
    audio_cpp,
    '''    void captureInput(int32_t requestedFrames) {
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
''',
    '''    oboe::DataCallbackResult captureInputCallback(void* audioData, int32_t numFrames) {
        if (!recording_.load() || audioData == nullptr || numFrames <= 0 || pcm16Scratch_.empty()) {
            return oboe::DataCallbackResult::Continue;
        }
        inputReadActive_.store(true, std::memory_order_release);
        const int32_t framesToCapture = std::min<int32_t>(numFrames, static_cast<int32_t>(pcm16Scratch_.size()));
        const auto* input = static_cast<const float*>(audioData);
        for (int32_t frame = 0; frame < framesToCapture; ++frame) {
            pcm16Scratch_[static_cast<size_t>(frame)] = floatToPcm16(input[static_cast<size_t>(frame)]);
        }
        inputReadFrames_.fetch_add(framesToCapture);
        const size_t accepted = ringBuffer_.push(pcm16Scratch_.data(), static_cast<size_t>(framesToCapture));
        if (accepted < static_cast<size_t>(framesToCapture)) {
            ringOverrunFrames_.fetch_add(static_cast<int64_t>(framesToCapture) - static_cast<int64_t>(accepted));
        }
        if (framesToCapture < numFrames) {
            ringOverrunFrames_.fetch_add(static_cast<int64_t>(numFrames - framesToCapture));
        }
        inputReadActive_.store(false, std::memory_order_release);
        return oboe::DataCallbackResult::Continue;
    }
''',
)
patch(
    audio_cpp,
    '''        constexpr jsize kFieldCount = 22;
''',
    '''        constexpr jsize kFieldCount = 23;
''',
)
patch(
    audio_cpp,
    '''        values[21] = arrangement ? arrangement->durationFrames() : 0;

        auto array = env->NewLongArray(kFieldCount);
''',
    '''        values[21] = arrangement ? arrangement->durationFrames() : 0;
        values[22] = inputReadFrames_.load();

        auto array = env->NewLongArray(kFieldCount);
''',
)
patch(
    audio_cpp,
    '''            if (recordedFrames_.load() * static_cast<int64_t>(sizeof(int16_t)) > 0xfffffff0LL) {
''',
    '''            if (recordedFrames_.load() * static_cast<int64_t>(sizeof(int16_t)) > 0xffffffdaLL) {
''',
)

# 2) Kotlin diagnostics follows the native field expansion.
native_kt = "app/src/main/java/com/aistudio/mediatool/feature/studio/audio/StudioNativeAudio.kt"
patch(
    native_kt,
    '''            arrangementDurationFrames = values[21],
        )
''',
    '''            arrangementDurationFrames = values[21],
            inputCapturedFrames = values[22],
        )
''',
)
patch(
    native_kt,
    '''        private const val DIAGNOSTIC_FIELD_COUNT = 22
''',
    '''        private const val DIAGNOSTIC_FIELD_COUNT = 23
''',
)
patch(
    native_kt,
    '''    val arrangementClipCount: Int,
    val arrangementDurationFrames: Long,
) {
''',
    '''    val arrangementClipCount: Int,
    val arrangementDurationFrames: Long,
    val inputCapturedFrames: Long,
) {
''',
)

# 3) Realtime clip lookup: pre-index clips into one-second immutable buckets so
# the audio callback does not scan the entire arrangement for every sample.
arrangement_h = "app/src/main/cpp/studio/studio_arrangement.h"
patch(
    arrangement_h,
    '''    int64_t timelineEndFrame() const {
        return timelineStartFrame_ + timelineLengthFrames_;
    }
''',
    '''    int64_t timelineStartFrame() const { return timelineStartFrame_; }

    int64_t timelineEndFrame() const {
        return timelineStartFrame_ + timelineLengthFrames_;
    }
''',
)
patch(
    arrangement_h,
    '''            arrangement->clips_.push_back(std::move(clip));
        }
        return arrangement;
    }

    void mix(int64_t projectFrame, float& left, float& right) const {
        for (const auto& clip : clips_) clip.mix(projectFrame, left, right);
    }
''',
    '''            arrangement->clips_.push_back(std::move(clip));
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
''',
)
patch(
    arrangement_h,
    '''    std::vector<PlaybackClip> clips_;
    int64_t durationFrames_ = 0;
''',
    '''    std::vector<PlaybackClip> clips_;
    std::vector<std::vector<size_t>> clipBuckets_;
    int64_t bucketFrames_ = 48'000;
    int64_t durationFrames_ = 0;
''',
)

# 4) Timeline duration mirrors what is actually selected for a non-materialized
# track. Old inactive takes must not keep the UI/playhead artificially long.
runtime_kt = "app/src/main/java/com/aistudio/mediatool/feature/studio/audio/StudioSessionRuntime.kt"
patch(
    runtime_kt,
    '''import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
''',
    '''import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
''',
)
patch(
    runtime_kt,
    '''import kotlinx.coroutines.launch
''',
    '''import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
''',
)
patch(
    runtime_kt,
    '''            StudioLatencyNative.measure(
                preferredInputDeviceId = current.selectedInputDeviceId,
                preferredOutputDeviceId = current.selectedOutputDeviceId,
                inputMode = mode,
            ).onSuccess { measurement = it }
                .onFailure { measurementError = it }
''',
    '''            val calibrationResult = coroutineScope {
                val calibrationTask = async(Dispatchers.IO) {
                    StudioLatencyNative.measure(
                        preferredInputDeviceId = current.selectedInputDeviceId,
                        preferredOutputDeviceId = current.selectedOutputDeviceId,
                        inputMode = mode,
                    )
                }
                try {
                    calibrationTask.await()
                } catch (cancelled: CancellationException) {
                    StudioLatencyNative.cancel()
                    withContext(NonCancellable) { calibrationTask.join() }
                    throw cancelled
                }
            }
            calibrationResult.onSuccess { measurement = it }
                .onFailure { measurementError = it }
''',
)
patch(
    runtime_kt,
    '''        } catch (cancelled: CancellationException) {
            audioFocusManager?.abandon()
            throw cancelled
        } catch (error: Throwable) {
            audioFocusManager?.abandon()
            _state.value = _state.value.copy(
                status = StudioSessionStatus.ERROR,
                message = null,
                errorMessage = error.message ?: "Không thể khôi phục Studio sau hiệu chỉnh latency",
            )
        }
    }

    private fun switchOutputRouteInternal(deviceId: Int?) {
''',
    '''        } catch (cancelled: CancellationException) {
            StudioLatencyNative.cancel()
            restoreAfterCalibrationInterruption(
                context = context,
                repo = repo,
                waves = waves,
                native = native,
                projectId = project.id,
                frame = frame,
                outputDeviceId = current.selectedOutputDeviceId,
            )
            audioFocusManager?.abandon()
            throw cancelled
        } catch (error: Throwable) {
            restoreAfterCalibrationInterruption(
                context = context,
                repo = repo,
                waves = waves,
                native = native,
                projectId = project.id,
                frame = frame,
                outputDeviceId = current.selectedOutputDeviceId,
            )
            audioFocusManager?.abandon()
            _state.value = _state.value.copy(
                message = null,
                errorMessage = error.message ?: "Không thể khôi phục Studio sau hiệu chỉnh latency",
            )
        }
    }

    private suspend fun restoreAfterCalibrationInterruption(
        context: Context,
        repo: StudioProjectRepository,
        waves: StudioWaveformStore,
        native: StudioNativeAudio,
        projectId: String,
        frame: Long,
        outputDeviceId: Int?,
    ) = withContext(NonCancellable) {
        runCatching {
            val prepared = StudioBeatPreparer(context, repo, waves).prepare(projectId)
            val restoredProject = prepared.project
            requireSuccess(native.openOutput(outputDeviceId), "Không thể mở lại output Studio")
            requireSuccess(
                native.loadBeat(prepared.pcmFile, restoredProject.timelineSampleRate, 2),
                "Không thể nạp lại beat Studio",
            )
            requireSuccess(
                native.setPlaybackPlan(StudioPlaybackPlanner.build(restoredProject, repo), restoredProject.timelineSampleRate),
                "Không thể nạp lại bản phối Studio",
            )
            requireSuccess(native.start(), "Không thể khởi động lại Studio")
            outputSuspendedForBackground = false
            native.seek(frame)
            native.setPlaying(false)
            if (!uiVisible) {
                native.stop()
                outputSuspendedForBackground = true
            }
            _state.value = _state.value.copy(
                project = restoredProject,
                status = StudioSessionStatus.READY,
                transportFrame = frame,
                durationFrames = projectDurationFrames(restoredProject),
                diagnostics = native.diagnostics(),
                message = null,
            )
        }.onFailure { restoreError ->
            _state.value = _state.value.copy(
                status = StudioSessionStatus.ERROR,
                message = null,
                errorMessage = restoreError.message ?: "Không thể khôi phục Studio sau khi dừng căn tiếng",
            )
        }
        Unit
    }

    private fun switchOutputRouteInternal(deviceId: Int?) {
''',
)
patch(
    runtime_kt,
    '''    private fun projectDurationFrames(project: StudioProject): Long {
        var duration = project.beatAsset()?.let { asset ->
            sourceFramesToTimeline(
                asset.durationFrames ?: 0L,
                asset.sampleRate ?: project.timelineSampleRate,
                project.timelineSampleRate,
            )
        } ?: 0L
        for (track in project.tracks) {
            for (take in track.takes) {
                val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)
                val sourceLength = (placement.sourceEndFrame - placement.sourceStartFrame).coerceAtLeast(0L)
                val length = sourceFramesToTimeline(sourceLength, take.inputSampleRate, project.timelineSampleRate)
                duration = max(duration, placement.timelineStartFrame + length)
            }
            for (clip in track.clips) {
                duration = max(duration, StudioEditEngine.timelineEnd(project, clip))
            }
        }
        return duration.coerceAtLeast(project.timelineSampleRate.toLong())
    }
''',
    '''    internal fun projectDurationFrames(project: StudioProject): Long {
        var duration = project.beatAsset()?.let { asset ->
            sourceFramesToTimeline(
                asset.durationFrames ?: 0L,
                asset.sampleRate ?: project.timelineSampleRate,
                project.timelineSampleRate,
            )
        } ?: 0L
        for (track in project.tracks) {
            if (track.type == StudioTrackType.BEAT) continue
            if (track.clips.isNotEmpty()) {
                for (clip in track.clips) {
                    duration = max(duration, StudioEditEngine.timelineEnd(project, clip))
                }
                continue
            }
            val activeTake = track.activeTakeId
                ?.let { id -> track.takes.firstOrNull { it.id == id } }
                ?: track.takes.lastOrNull()
                ?: continue
            val placement = activeTake.latencyCompensatedPlacement(project.timelineSampleRate)
            val sourceLength = (placement.sourceEndFrame - placement.sourceStartFrame).coerceAtLeast(0L)
            val length = sourceFramesToTimeline(sourceLength, activeTake.inputSampleRate, project.timelineSampleRate)
            duration = max(duration, placement.timelineStartFrame + length)
        }
        return duration.coerceAtLeast(project.timelineSampleRate.toLong())
    }
''',
)

# 5) Native calibration can be cancelled from another coroutine/thread, and the
# runtime waits for the native streams to close before restoring Studio output.
latency_cpp = "app/src/main/cpp/studio/studio_latency_calibrator.cpp"
patch(
    latency_cpp,
    '''constexpr int32_t kErrorRateMismatch = -20'006;
''',
    '''constexpr int32_t kErrorRateMismatch = -20'006;
constexpr int32_t kErrorCancelled = -20'007;
std::atomic<bool> gCalibrationCancelled{false};
''',
)
patch(
    latency_cpp,
    '''    CalibrationResult measure(int32_t preferredInputDeviceId, int32_t preferredOutputDeviceId, int32_t inputMode) {
        CalibrationResult result;
''',
    '''    CalibrationResult measure(int32_t preferredInputDeviceId, int32_t preferredOutputDeviceId, int32_t inputMode) {
        gCalibrationCancelled.store(false, std::memory_order_release);
        CalibrationResult result;
''',
)
patch(
    latency_cpp,
    '''        while (!completed_.load() && !disconnected_.load() && std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
        }
        output_->requestStop();
        input_->requestStop();

        if (!completed_.load() || disconnected_.load()) {
''',
    '''        while (!completed_.load() && !disconnected_.load() && !gCalibrationCancelled.load(std::memory_order_acquire) &&
               std::chrono::steady_clock::now() < deadline) {
            std::this_thread::sleep_for(std::chrono::milliseconds(8));
        }
        output_->requestStop();
        input_->requestStop();

        if (gCalibrationCancelled.load(std::memory_order_acquire)) {
            result.status = kErrorCancelled;
            close();
            return result;
        }
        if (!completed_.load() || disconnected_.load()) {
''',
)
patch(
    latency_cpp,
    '''extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioLatencyNative_nativeMeasure(
''',
    '''extern "C" JNIEXPORT void JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioLatencyNative_nativeCancel(
    JNIEnv*, jobject
) {
    gCalibrationCancelled.store(true, std::memory_order_release);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_aistudio_mediatool_feature_studio_audio_StudioLatencyNative_nativeMeasure(
''',
)

latency_kt = "app/src/main/java/com/aistudio/mediatool/feature/studio/audio/StudioLatencyNative.kt"
patch(
    latency_kt,
    '''object StudioLatencyNative {
    fun measure(
''',
    '''object StudioLatencyNative {
    fun cancel() = nativeCancel()

    fun measure(
''',
)
patch(
    latency_kt,
    '''        -20_006 -> "Input và output không chạy cùng sample rate cho phép auto calibration"
''',
    '''        -20_006 -> "Input và output không chạy cùng sample rate cho phép auto calibration"
        -20_007 -> "Hiệu chỉnh đã được hủy"
''',
)
patch(
    latency_kt,
    '''    private external fun nativeMeasure(
''',
    '''    private external fun nativeCancel()

    private external fun nativeMeasure(
''',
)

# 6) Stem rendering cleans every intermediate WAV, including partially rendered
# files when FFmpeg fails.
render_kt = "app/src/main/java/com/aistudio/mediatool/feature/studio/render/StudioRenderEngine.kt"
patch(
    render_kt,
    '''    suspend fun renderStems(project: StudioProject): File = withContext(Dispatchers.IO) {
        val rendered = mutableListOf<File>()
        project.tracks
            .filter { !it.muted }
            .forEachIndexed { index, track ->
                val sources = buildTrackSources(project, track)
                if (sources.isEmpty()) return@forEachIndexed
                val safeName = DocumentUtils.sanitizeFileName(track.name).ifBlank { "Track_${index + 1}" }
                val target = FileExportManager.resultFile(appContext, "${project.name}_${safeName}", "wav")
                execute(
                    buildCommand(project, sources, target, StudioExportFormat.WAV, applyMaster = false),
                    "studio_export_stem",
                )
                if (target.isFile && target.length() > 0L) rendered += target
            }
        require(rendered.isNotEmpty()) { "Không có stem nào để xuất" }
        FileExportManager.zipFiles(appContext, rendered, "${project.name}_Studio_Stems")
    }
''',
    '''    suspend fun renderStems(project: StudioProject): File = withContext(Dispatchers.IO) {
        val rendered = mutableListOf<File>()
        val temporaryFiles = mutableListOf<File>()
        try {
            project.tracks
                .filter { !it.muted }
                .forEachIndexed { index, track ->
                    val sources = buildTrackSources(project, track)
                    if (sources.isEmpty()) return@forEachIndexed
                    val safeName = DocumentUtils.sanitizeFileName(track.name).ifBlank { "Track_${index + 1}" }
                    val target = FileExportManager.resultFile(appContext, "${project.name}_${safeName}", "wav")
                    temporaryFiles += target
                    execute(
                        buildCommand(project, sources, target, StudioExportFormat.WAV, applyMaster = false),
                        "studio_export_stem",
                    )
                    if (target.isFile && target.length() > 0L) rendered += target
                }
            require(rendered.isNotEmpty()) { "Không có stem nào để xuất" }
            FileExportManager.zipFiles(appContext, rendered, "${project.name}_Studio_Stems")
        } finally {
            temporaryFiles.forEach { file -> runCatching { file.delete() } }
        }
    }
''',
)

# 7) Canonical RIFF WAV must reserve the 36-byte RIFF size overhead.
wav_kt = "app/src/main/java/com/aistudio/mediatool/feature/studio/data/StudioWavFile.kt"
patch(
    wav_kt,
    '''    const val HEADER_BYTES = 44
''',
    '''    const val HEADER_BYTES = 44
    private const val MAX_RIFF_DATA_BYTES = 0xffff_ffffL - 36L
''',
)
patch(
    wav_kt,
    '''        if (alignedBytes <= 0L || alignedBytes > UInt.MAX_VALUE.toLong()) return null
''',
    '''        if (alignedBytes <= 0L || alignedBytes > MAX_RIFF_DATA_BYTES) return null
''',
)
patch(
    wav_kt,
    '''        if (alignedBytes <= 0L || alignedBytes > UInt.MAX_VALUE.toLong()) return null
''',
    '''        if (alignedBytes <= 0L || alignedBytes > MAX_RIFF_DATA_BYTES) return null
''',
)

# Regression tests for active-take duration behavior.
test_path = ROOT / "app/src/test/java/com/aistudio/mediatool/feature/studio/audio/StudioProjectDurationTest.kt"
test_path.parent.mkdir(parents=True, exist_ok=True)
test_path.write_text(r'''package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioProjectDurationTest {
    @Test
    fun inactiveLongTakeDoesNotExtendTimeline() {
        val longTake = take("long", frames = 144_000L)
        val activeTake = take("active", frames = 48_000L)
        val project = project(
            track = StudioTrack(
                id = "vocal-track",
                type = StudioTrackType.VOCAL,
                name = "Vocal",
                activeTakeId = activeTake.id,
                takes = listOf(longTake, activeTake),
            ),
        )

        assertEquals(48_000L, StudioSessionRuntime.projectDurationFrames(project))
    }

    @Test
    fun materializedClipsDefineDurationInsteadOfHistoricalTakes() {
        val historical = take("historical", frames = 192_000L)
        val project = project(
            track = StudioTrack(
                id = "vocal-track",
                type = StudioTrackType.VOCAL,
                name = "Vocal",
                activeTakeId = historical.id,
                takes = listOf(historical),
                clips = listOf(
                    StudioClip(
                        id = "clip",
                        sourceAssetId = "take-asset",
                        sourceTakeId = historical.id,
                        timelineStartFrame = 24_000L,
                        sourceStartFrame = 0L,
                        sourceEndFrame = 48_000L,
                    ),
                ),
            ),
        )

        assertEquals(72_000L, StudioSessionRuntime.projectDurationFrames(project))
    }

    private fun project(track: StudioTrack): StudioProject = StudioProject(
        id = "project-duration-test",
        name = "Test",
        createdAt = 0L,
        updatedAt = 0L,
        beatAssetId = "beat",
        assets = listOf(
            StudioAsset(
                id = "beat",
                kind = StudioAssetKind.BEAT,
                relativePath = "beat.wav",
                displayName = "Beat",
                sampleRate = 48_000,
                channelCount = 2,
                durationFrames = 48_000L,
            ),
            StudioAsset(
                id = "take-asset",
                kind = StudioAssetKind.TAKE,
                relativePath = "take.wav",
                displayName = "Take",
                sampleRate = 48_000,
                channelCount = 1,
                durationFrames = 192_000L,
            ),
        ),
        tracks = listOf(
            StudioTrack(
                id = "beat-track",
                type = StudioTrackType.BEAT,
                name = "Beat",
                primaryAssetId = "beat",
            ),
            track,
        ),
    )

    private fun take(id: String, frames: Long): StudioTake = StudioTake(
        id = id,
        assetId = "take-asset",
        recordedTimelineFrame = 0L,
        recordedFrames = frames,
        inputSampleRate = 48_000,
        status = StudioTakeStatus.COMPLETE,
    )
}
''', encoding="utf-8")
print(f"created {test_path.relative_to(ROOT)}")

print("Studio hardening patch complete")
