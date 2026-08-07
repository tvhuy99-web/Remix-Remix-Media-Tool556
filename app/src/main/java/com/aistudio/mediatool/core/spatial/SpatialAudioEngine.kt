package com.aistudio.mediatool.core.spatial

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.SystemClock
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.media.MediaEngine
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SpatialAudioEngine(
    private val context: Context,
    private val mediaEngine: MediaEngine,
) {
    sealed interface State {
        data class Progress(val percent: Float, val message: String) : State
        data class Success(val metrics: SpatialRenderMetrics) : State
        data class Error(val message: String) : State
    }

    fun process(
        inputSaf: String,
        output: File,
        sourceDurationMs: Long,
        config: SpatialAudioConfig,
        preFilters: List<String>,
        isVideoMode: Boolean,
        modeIndex: Int,
        extension: String,
        preview: Boolean,
    ): Flow<State> = flow {
        val taskId = UUID.randomUUID().toString()
        val value = config.normalized()
        val workDir = File(context.cacheDir, "spatial_${System.currentTimeMillis()}_${taskId.take(8)}")
        val localSource = File(workDir, "source_local.bin")
        val decoded = File(workDir, "decoded_stereo_48k.f32")
        val rendered = File(workDir, "rendered_binaural_stereo_48k.f32")
        workDir.mkdirs()
        output.delete()
        val sourceId = DiagnosticRedactor.stableId(inputSaf)
        val expectedDurationMs = if (preview) sourceDurationMs.coerceAtMost(10_000L) else sourceDurationMs
        val safeFilters = preFilters.filterNot { it.startsWith("alimiter=") }
        val sourceCopyStartedAt = SystemClock.elapsedRealtime()
        val materializedSource = try {
            emit(State.Progress(2f, "Đang chuẩn bị nguồn Spatial Audio cục bộ"))
            withContext(Dispatchers.IO) {
                mediaEngine.materializeInput(inputSaf, localSource)
            }.also { materialized ->
                DiagnosticLogger.info(
                    component = TAG,
                    event = "spatial_source_materialized",
                    sessionId = taskId,
                    fields = mapOf(
                        "source_id" to sourceId,
                        "local_source_bytes" to materialized.bytes,
                        "source_transport" to materialized.transport,
                        "copy_elapsed_ms" to SystemClock.elapsedRealtime() - sourceCopyStartedAt,
                    ),
                )
            }
        } catch (error: Exception) {
            DiagnosticLogger.error(
                component = TAG,
                event = "spatial_source_materialization_failed",
                sessionId = taskId,
                message = error.message,
                fields = mapOf(
                    "source_id" to sourceId,
                    "copy_elapsed_ms" to SystemClock.elapsedRealtime() - sourceCopyStartedAt,
                    "failure_type" to error.javaClass.name,
                ),
                error = error,
            )
            workDir.deleteRecursively()
            emit(State.Error(error.message ?: "Không thể chuẩn bị tệp nguồn Spatial Audio"))
            return@flow
        }
        val sourceInfo = withContext(Dispatchers.IO) { probeAudio(localSource.absolutePath, taskId, "source") }
        val runtimeBefore = runtimeSnapshot("before")

        DiagnosticLogger.info(
            component = TAG,
            event = "spatial_render_start",
            sessionId = taskId,
            fields = value.diagnosticFields() + sourceInfo.diagnosticFields("source") + runtimeBefore + mapOf(
                "source_id" to sourceId,
                "source_duration_ms" to sourceDurationMs,
                "expected_duration_ms" to expectedDurationMs,
                "preview" to preview,
                "video_mode" to isVideoMode,
                "mode_index" to modeIndex,
                "pre_filter_count" to safeFilters.size,
                "requested_decode_channels" to 2,
                "requested_decode_sample_rate" to 48_000,
                "local_source_bytes" to materializedSource.bytes,
                "source_transport" to materializedSource.transport,
            ),
        )

        try {
            emit(State.Progress(5f, "Đang giải mã nguồn stereo 48 kHz"))
            val decodeCommand = buildString {
                append("-y -i \"").append(localSource.absolutePath).append("\" ")
                if (preview) append("-t 10 ")
                append("-map 0:a:0? -vn ")
                if (safeFilters.isNotEmpty()) {
                    append("-af \"").append(safeFilters.joinToString(",")).append("\" ")
                }
                append("-ac 2 -ar 48000 -c:a pcm_f32le -f f32le \"")
                    .append(decoded.absolutePath)
                    .append("\"")
            }
            runFfmpeg(
                command = decodeCommand,
                phase = "spatial_decode_stereo",
                startPercent = 5f,
                endPercent = 30f,
                expectedDurationMs = expectedDurationMs,
                startupTimeoutMs = DECODE_STARTUP_TIMEOUT_MS,
            ) { progress, message -> emit(State.Progress(progress, message)) }
            require(decoded.isFile && decoded.length() >= 8L) { "Không giải mã được PCM stereo cho spatial audio" }

            val decodedDurationMs = decoded.length() * 1_000L / PCM_BYTES_PER_SECOND
            val inputLoudness = analyzeLoudness(
                command = "-hide_banner -f f32le -ar 48000 -ac 2 -i \"${decoded.absolutePath}\" " +
                    "-af \"loudnorm=I=-16:TP=-1:LRA=11:print_format=json\" -f null -",
                phase = "spatial_input_loudness",
                taskId = taskId,
            )
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_input_quality",
                sessionId = taskId,
                fields = sourceInfo.diagnosticFields("source") + inputLoudness.diagnosticFields("input") + mapOf(
                    "decoded_channels" to 2,
                    "decoded_sample_rate" to 48_000,
                    "decoded_bytes" to decoded.length(),
                    "decoded_duration_ms" to decodedDurationMs,
                ),
            )

            emit(State.Progress(36f, "Đang dựng ${value.stereoMode.label}"))
            val nativeBefore = runtimeSnapshot("native_before")
            val metrics = renderStereoMode(
                decoded = decoded,
                rendered = rendered,
                config = value,
                workDir = workDir,
                expectedDurationMs = expectedDurationMs,
            ) { progress, message -> emit(State.Progress(progress, message)) }
            val nativeAfter = runtimeSnapshot("native_after")
            require(rendered.isFile && rendered.length() >= 8L) { "Renderer không tạo PCM stereo" }
            val renderedDurationMs = rendered.length() * 1_000L / PCM_BYTES_PER_SECOND
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_native_complete",
                sessionId = taskId,
                fields = metrics.diagnosticFields() + nativeBefore + nativeAfter + mapOf(
                    "decoded_bytes" to decoded.length(),
                    "rendered_bytes" to rendered.length(),
                    "decoded_duration_ms" to decodedDurationMs,
                    "rendered_duration_ms" to renderedDurationMs,
                    "render_realtime_factor" to if (expectedDurationMs > 0L) {
                        metrics.renderMs.toDouble() / expectedDurationMs.toDouble()
                    } else null,
                    "hard_bypass" to (value.spatialBlend <= BYPASS_EPSILON),
                    "mix_limiter_latency_compensated" to true,
                    "dual_object_branch_gain" to DUAL_OBJECT_BRANCH_GAIN,
                ),
            )

            emit(State.Progress(80f, "Đang mã hóa kết quả binaural stereo"))
            val encodeCommand = buildEncodeCommand(
                inputPath = localSource.absolutePath,
                rendered = rendered,
                output = output,
                isVideoMode = isVideoMode,
                modeIndex = modeIndex,
                extension = extension,
                preview = preview,
            )
            runFfmpeg(
                command = encodeCommand,
                phase = "spatial_encode_stereo",
                startPercent = 80f,
                endPercent = 95f,
                expectedDurationMs = expectedDurationMs,
            ) { progress, message -> emit(State.Progress(progress, message)) }
            require(output.isFile && output.length() > 0L) { "Không tạo được tệp spatial audio đầu ra" }

            emit(State.Progress(97f, "Đang đo loudness và true peak"))
            val outputLoudness = analyzeLoudness(
                command = "-hide_banner -i \"${output.absolutePath}\" -map 0:a:0? " +
                    "-af \"loudnorm=I=-16:TP=-1:LRA=11:print_format=json\" -f null -",
                phase = "spatial_output_loudness",
                taskId = taskId,
            )
            val outputInfo = withContext(Dispatchers.IO) { probeAudio(output.absolutePath, taskId, "output") }
            val runtimeAfter = runtimeSnapshot("after")
            val qualityDelta = qualityDeltaFields(inputLoudness, outputLoudness)

            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_output_quality",
                sessionId = taskId,
                fields = inputLoudness.diagnosticFields("input") +
                    outputLoudness.diagnosticFields("output") +
                    outputInfo.diagnosticFields("output") +
                    qualityDelta + runtimeAfter + mapOf(
                        "output_bytes" to output.length(),
                        "output_extension" to extension,
                        "stereo_mode" to value.stereoMode.name,
                    ),
            )
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_render_success",
                sessionId = taskId,
                fields = metrics.diagnosticFields() + inputLoudness.diagnosticFields("input") +
                    outputLoudness.diagnosticFields("output") + qualityDelta + mapOf(
                        "output_bytes" to output.length(),
                        "output_extension" to extension,
                        "output_channels" to outputInfo.channels,
                        "output_sample_rate" to outputInfo.sampleRate,
                        "output_duration_ms" to outputInfo.durationMs,
                    ),
            )
            emit(State.Progress(100f, "Spatial audio stereo hoàn tất"))
            emit(State.Success(metrics))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            output.delete()
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_render_cancelled",
                sessionId = taskId,
                fields = mapOf("source_id" to sourceId),
            )
            throw cancelled
        } catch (error: LinkageError) {
            output.delete()
            DiagnosticLogger.error(
                component = TAG,
                event = "spatial_render_failed",
                sessionId = taskId,
                message = error.message,
                fields = value.diagnosticFields() + mapOf(
                    "source_id" to sourceId,
                    "failure_type" to error.javaClass.name,
                ),
                error = error,
            )
            emit(State.Error(error.message ?: "Thành phần native spatial audio chưa sẵn sàng"))
        } catch (error: Exception) {
            output.delete()
            DiagnosticLogger.error(
                component = TAG,
                event = "spatial_render_failed",
                sessionId = taskId,
                message = error.message,
                fields = value.diagnosticFields() + mapOf(
                    "source_id" to sourceId,
                    "failure_type" to error.javaClass.name,
                ),
                error = error,
            )
            emit(State.Error(error.message ?: "Không thể xử lý spatial audio"))
        } finally {
            workDir.deleteRecursively()
        }
    }

    private suspend fun renderStereoMode(
        decoded: File,
        rendered: File,
        config: SpatialAudioConfig,
        workDir: File,
        expectedDurationMs: Long,
        onProgress: suspend (Float, String) -> Unit,
    ): SpatialRenderMetrics {
        if (config.spatialBlend <= BYPASS_EPSILON) {
            val startedAt = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) { decoded.copyTo(rendered, overwrite = true) }
            onProgress(72f, "Cường độ 0%: bypass PCM nguyên bản")
            return bypassMetrics(
                decoded = decoded,
                renderMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }

        val fullySpatial = config.copy(spatialBlend = 1f).normalized()
        val spatialComposite = File(workDir, "spatial_composite.f32")
        val metrics = when (config.stereoMode) {
            SpatialStereoMode.SHARED_POSITION -> {
                onProgress(42f, "Render stereo cùng một vị trí")
                val native = withContext(Dispatchers.Default) {
                    SteamAudioBridge.render(decoded, spatialComposite, fullySpatial)
                }
                native.copy(stereoMode = "shared_position")
            }

            SpatialStereoMode.MID_SIDE -> {
                val midInput = File(workDir, "mid_input.f32")
                val midRendered = File(workDir, "mid_rendered.f32")
                runRawPcmFilter(
                    input = decoded,
                    output = midInput,
                    filter = "pan=stereo|c0=0.5*FL+0.5*FR|c1=0.5*FL+0.5*FR",
                    phase = "spatial_mid_extract",
                    startPercent = 38f,
                    endPercent = 43f,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = onProgress,
                )
                onProgress(48f, "Spatialize kênh Mid, giữ Side riêng")
                val native = withContext(Dispatchers.Default) {
                    SteamAudioBridge.render(midInput, midRendered, fullySpatial)
                }
                runRawPcmComposite(
                    inputs = listOf(decoded, midRendered),
                    output = spatialComposite,
                    filterComplex = "[0:a]pan=stereo|c0=0.5*FL-0.5*FR|c1=-0.5*FL+0.5*FR[side];" +
                        "[1:a][side]amix=inputs=2:duration=longest:normalize=0[out]",
                    phase = "spatial_mid_side_rebuild",
                    startPercent = 56f,
                    endPercent = 63f,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = onProgress,
                )
                native.copy(stereoMode = "mid_side")
            }

            SpatialStereoMode.DUAL_OBJECT -> {
                val leftInput = File(workDir, "left_object_input.f32")
                val rightInput = File(workDir, "right_object_input.f32")
                val leftRendered = File(workDir, "left_object_rendered.f32")
                val rightRendered = File(workDir, "right_object_rendered.f32")
                runRawPcmFilter(
                    input = decoded,
                    output = leftInput,
                    filter = "pan=stereo|c0=FL|c1=0*FR",
                    phase = "spatial_left_object_extract",
                    startPercent = 38f,
                    endPercent = 41f,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = onProgress,
                )
                runRawPcmFilter(
                    input = decoded,
                    output = rightInput,
                    filter = "pan=stereo|c0=0*FL|c1=FR",
                    phase = "spatial_right_object_extract",
                    startPercent = 41f,
                    endPercent = 44f,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = onProgress,
                )
                val offset = config.stereoObjectHalfAngleDeg
                val leftConfig = fullySpatial.copy(
                    startAzimuthDeg = fullySpatial.startAzimuthDeg - offset,
                    endAzimuthDeg = fullySpatial.endAzimuthDeg - offset,
                ).normalized()
                val rightConfig = fullySpatial.copy(
                    startAzimuthDeg = fullySpatial.startAzimuthDeg + offset,
                    endAzimuthDeg = fullySpatial.endAzimuthDeg + offset,
                ).normalized()
                onProgress(48f, "Render object L lệch -${formatAngle(offset)}°")
                val leftMetrics = withContext(Dispatchers.Default) {
                    SteamAudioBridge.render(leftInput, leftRendered, leftConfig)
                }
                onProgress(57f, "Render object R lệch +${formatAngle(offset)}°")
                val rightMetrics = withContext(Dispatchers.Default) {
                    SteamAudioBridge.render(rightInput, rightRendered, rightConfig)
                }
                runRawPcmComposite(
                    inputs = listOf(leftRendered, rightRendered),
                    output = spatialComposite,
                    filterComplex = "[0:a]volume=$DUAL_OBJECT_BRANCH_GAIN[left];" +
                        "[1:a]volume=$DUAL_OBJECT_BRANCH_GAIN[right];" +
                        "[left][right]amix=inputs=2:duration=longest:normalize=0[out]",
                    phase = "spatial_dual_object_mix",
                    startPercent = 63f,
                    endPercent = 68f,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = onProgress,
                )
                leftMetrics.copy(
                    renderMs = leftMetrics.renderMs + rightMetrics.renderMs,
                    tailFrames = maxOf(leftMetrics.tailFrames, rightMetrics.tailFrames),
                    stereoMode = "dual_object",
                )
            }
        }

        blendOriginalAndSpatial(
            original = decoded,
            spatial = spatialComposite,
            output = rendered,
            intensity = config.spatialBlend,
            expectedDurationMs = expectedDurationMs,
            onProgress = onProgress,
        )
        return metrics
    }

    private suspend fun blendOriginalAndSpatial(
        original: File,
        spatial: File,
        output: File,
        intensity: Float,
        expectedDurationMs: Long,
        onProgress: suspend (Float, String) -> Unit,
    ) {
        val wet = intensity.coerceIn(0f, 1f)
        val dry = 1f - wet
        runRawPcmComposite(
            inputs = listOf(original, spatial),
            output = output,
            filterComplex = "[0:a]volume=$dry[dry];[1:a]volume=$wet[wet];" +
                "[dry][wet]amix=inputs=2:duration=longest:normalize=0," +
                "alimiter=limit=$SAMPLE_PEAK_LIMIT:level=false:latency=1[out]",
            phase = "spatial_master_blend",
            startPercent = 69f,
            endPercent = 76f,
            expectedDurationMs = expectedDurationMs,
            onProgress = onProgress,
        )
    }

    private suspend fun runRawPcmFilter(
        input: File,
        output: File,
        filter: String,
        phase: String,
        startPercent: Float,
        endPercent: Float,
        expectedDurationMs: Long,
        onProgress: suspend (Float, String) -> Unit,
    ) {
        val command = "-y ${rawPcmInput(input)} -af \"$filter\" -c:a pcm_f32le -f f32le \"${output.absolutePath}\""
        runFfmpeg(command, phase, startPercent, endPercent, expectedDurationMs, onProgress = onProgress)
        require(output.isFile && output.length() >= 8L) { "Không tạo được PCM ở $phase" }
    }

    private suspend fun runRawPcmComposite(
        inputs: List<File>,
        output: File,
        filterComplex: String,
        phase: String,
        startPercent: Float,
        endPercent: Float,
        expectedDurationMs: Long,
        onProgress: suspend (Float, String) -> Unit,
    ) {
        val command = buildString {
            append("-y ")
            inputs.forEach { append(rawPcmInput(it)).append(' ') }
            append("-filter_complex \"").append(filterComplex).append("\" ")
            append("-map \"[out]\" -c:a pcm_f32le -f f32le \"")
                .append(output.absolutePath)
                .append("\"")
        }
        runFfmpeg(command, phase, startPercent, endPercent, expectedDurationMs, onProgress = onProgress)
        require(output.isFile && output.length() >= 8L) { "Không tạo được PCM ở $phase" }
    }

    private fun rawPcmInput(file: File): String =
        "-f f32le -ar 48000 -ac 2 -i \"${file.absolutePath}\""

    private fun bypassMetrics(decoded: File, renderMs: Long): SpatialRenderMetrics = SpatialRenderMetrics(
        frames = decoded.length() / 8L,
        blocks = 0L,
        tailFrames = 0L,
        renderMs = renderMs,
        inputChannels = 2,
        outputChannels = 2,
        stereoMode = "bypass",
        inputPeak = 0f,
        inputPeakLeft = 0f,
        inputPeakRight = 0f,
        inputRmsDbfs = -160f,
        inputRmsLeftDbfs = -160f,
        inputRmsRightDbfs = -160f,
        inputCorrelation = 0f,
        inputBalanceDb = 0f,
        inputDifferenceRmsDbfs = -160f,
        inputDualMono = false,
        peakBeforeGain = 0f,
        peakAfterGain = 0f,
        peakAfterGainLeft = 0f,
        peakAfterGainRight = 0f,
        outputMainRmsBeforeGainDbfs = -160f,
        outputMainRmsAfterGainDbfs = -160f,
        outputTotalRmsDbfs = -160f,
        outputRmsLeftDbfs = -160f,
        outputRmsRightDbfs = -160f,
        outputCorrelation = 0f,
        outputBalanceDb = 0f,
        automaticMakeupGainDb = 0f,
        manualOutputGainDb = 0f,
        peakLimiterGainDb = 0f,
        appliedGainDb = 0f,
        estimatedLoudnessDeltaDb = 0f,
        peakCeilingDbfs = -1f,
        nonFiniteSamples = 0L,
        clippedSamplesBeforeGain = 0L,
        hrtfType = "bypass",
        steamAudioVersion = "bypass",
    )

    private suspend fun runFfmpeg(
        command: String,
        phase: String,
        startPercent: Float,
        endPercent: Float,
        expectedDurationMs: Long,
        startupTimeoutMs: Long? = null,
        onProgress: suspend (Float, String) -> Unit,
    ): String {
        var outputLog = ""
        mediaEngine.executeFFmpegCommand(
            command = command,
            diagnosticPhase = phase,
            startupTimeoutMs = startupTimeoutMs,
        ).collect { state ->
            when (state) {
                is MediaEngine.ExecutionState.Connecting ->
                    onProgress(startPercent, "Đang khởi tạo $phase")

                is MediaEngine.ExecutionState.Progress -> {
                    val ratio = if (expectedDurationMs > 0L) {
                        state.timeInMilliseconds.toFloat() / expectedDurationMs.toFloat()
                    } else 0.5f
                    val percent = startPercent + (endPercent - startPercent) * ratio.coerceIn(0f, 1f)
                    onProgress(percent, "Đang xử lý ${state.timeInMilliseconds} ms")
                }

                is MediaEngine.ExecutionState.Success -> {
                    outputLog = state.outputLog
                    onProgress(endPercent, "Hoàn tất $phase")
                }
                is MediaEngine.ExecutionState.Error -> error(
                    state.failStackTrace ?: state.logs ?: "FFmpeg thất bại ở $phase",
                )
            }
        }
        return outputLog
    }

    private suspend fun analyzeLoudness(
        command: String,
        phase: String,
        taskId: String,
    ): LoudnessMetrics? = try {
        val session = withContext(Dispatchers.IO) { FFmpegKit.execute(command) }
        if (!ReturnCode.isSuccess(session.returnCode)) {
            error("FFmpeg loudness thất bại ở $phase: ${session.returnCode}")
        }
        parseLoudnorm(session.allLogsAsString.orEmpty())
            ?: error("Không đọc được loudnorm JSON ở $phase")
    } catch (error: Exception) {
        DiagnosticLogger.warn(
            component = TAG,
            event = "spatial_loudness_analysis_failed",
            sessionId = taskId,
            message = error.message,
            fields = mapOf("phase" to phase, "failure_type" to error.javaClass.name),
            error = error,
        )
        null
    }

    private fun parseLoudnorm(logs: String): LoudnessMetrics? {
        val block = LOUDNORM_JSON.findAll(logs).lastOrNull()?.value ?: return null
        val json = JSONObject(block)
        fun number(name: String): Double? = json.optString(name).toDoubleOrNull()?.takeIf(Double::isFinite)
        return LoudnessMetrics(
            integratedLufs = number("input_i"),
            truePeakDbtp = number("input_tp"),
            loudnessRangeLu = number("input_lra"),
            thresholdLufs = number("input_thresh"),
        )
    }

    private fun probeAudio(path: String, taskId: String, role: String): AudioProbeInfo = try {
        val session = FFprobeKit.executeWithArguments(
            arrayOf(
                "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name,channels,channel_layout,sample_rate,duration:format=duration",
                "-of", "json",
                path,
            ),
        )
        val root = JSONObject(session.output ?: "{}")
        val stream = root.optJSONArray("streams")?.optJSONObject(0)
        val format = root.optJSONObject("format")
        val durationSeconds = stream?.optString("duration")?.toDoubleOrNull()
            ?: format?.optString("duration")?.toDoubleOrNull()
        AudioProbeInfo(
            codec = stream?.optString("codec_name")?.takeIf(String::isNotBlank),
            channels = stream?.optInt("channels")?.takeIf { it > 0 },
            channelLayout = stream?.optString("channel_layout")?.takeIf(String::isNotBlank),
            sampleRate = stream?.optString("sample_rate")?.toIntOrNull(),
            durationMs = durationSeconds?.times(1_000.0)?.toLong(),
        )
    } catch (error: Exception) {
        DiagnosticLogger.warn(
            component = TAG,
            event = "spatial_audio_probe_failed",
            sessionId = taskId,
            message = error.message,
            fields = mapOf("role" to role, "failure_type" to error.javaClass.name),
            error = error,
        )
        AudioProbeInfo()
    }

    private fun runtimeSnapshot(prefix: String): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val rawTemperature = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return mapOf(
            "${prefix}_pss_kb" to Debug.getPss(),
            "${prefix}_native_heap_bytes" to Debug.getNativeHeapAllocatedSize(),
            "${prefix}_java_heap_used_bytes" to runtime.totalMemory() - runtime.freeMemory(),
            "${prefix}_battery_temperature_c" to rawTemperature
                ?.takeIf { it != Int.MIN_VALUE }
                ?.div(10.0),
        )
    }

    private fun qualityDeltaFields(
        input: LoudnessMetrics?,
        output: LoudnessMetrics?,
    ): Map<String, Any?> = mapOf(
        "integrated_loudness_delta_lu" to if (input?.integratedLufs != null && output?.integratedLufs != null) {
            output.integratedLufs - input.integratedLufs
        } else null,
        "true_peak_delta_db" to if (input?.truePeakDbtp != null && output?.truePeakDbtp != null) {
            output.truePeakDbtp - input.truePeakDbtp
        } else null,
    )

    private fun buildEncodeCommand(
        inputPath: String,
        rendered: File,
        output: File,
        isVideoMode: Boolean,
        modeIndex: Int,
        extension: String,
        preview: Boolean,
    ): String = buildString {
        append("-y ")
        if (isVideoMode && modeIndex == 0) {
            append("-i \"").append(inputPath).append("\" ")
            append("-f f32le -ar 48000 -ac 2 -i \"").append(rendered.absolutePath).append("\" ")
            if (preview) append("-t 10 ")
            append("-map 0:v:0 -map 1:a:0 -c:v copy ")
            append("-c:a aac -b:a ").append(SettingsManager.getAudioBitrateInt(context) / 1000).append("k ")
            append("-shortest -movflags +faststart ")
        } else {
            append("-f f32le -ar 48000 -ac 2 -i \"").append(rendered.absolutePath).append("\" ")
            append("-vn ")
            append(audioEncodingArgs(extension))
        }
        append("\"").append(output.absolutePath).append("\"")
    }

    private fun audioEncodingArgs(extension: String): String = when (extension.lowercase()) {
        "m4a" -> "-c:a aac -b:a ${SettingsManager.getAudioBitrateInt(context) / 1000}k "
        "mp3" -> "-c:a libmp3lame -b:a ${SettingsManager.getAudioBitrateInt(context) / 1000}k "
        "wav" -> "-c:a pcm_s24le "
        "flac" -> "-c:a flac "
        else -> SettingsManager.getAudioEncodingArgs(context) + " "
    }

    private fun formatAngle(value: Float): String {
        val rounded = value.toInt()
        return if (value == rounded.toFloat()) rounded.toString() else "%.1f".format(value)
    }

    private data class AudioProbeInfo(
        val codec: String? = null,
        val channels: Int? = null,
        val channelLayout: String? = null,
        val sampleRate: Int? = null,
        val durationMs: Long? = null,
    ) {
        fun diagnosticFields(prefix: String): Map<String, Any?> = mapOf(
            "${prefix}_codec" to codec,
            "${prefix}_channels" to channels,
            "${prefix}_channel_layout" to channelLayout,
            "${prefix}_sample_rate" to sampleRate,
            "${prefix}_duration_ms" to durationMs,
        )
    }

    private data class LoudnessMetrics(
        val integratedLufs: Double?,
        val truePeakDbtp: Double?,
        val loudnessRangeLu: Double?,
        val thresholdLufs: Double?,
    ) {
        fun diagnosticFields(prefix: String): Map<String, Any?> = mapOf(
            "${prefix}_integrated_lufs" to integratedLufs,
            "${prefix}_true_peak_dbtp" to truePeakDbtp,
            "${prefix}_loudness_range_lu" to loudnessRangeLu,
            "${prefix}_loudness_threshold_lufs" to thresholdLufs,
        )
    }

    private fun LoudnessMetrics?.diagnosticFields(prefix: String): Map<String, Any?> =
        this?.diagnosticFields(prefix) ?: mapOf(
            "${prefix}_integrated_lufs" to null,
            "${prefix}_true_peak_dbtp" to null,
            "${prefix}_loudness_range_lu" to null,
            "${prefix}_loudness_threshold_lufs" to null,
        )

    companion object {
        private const val TAG = "SpatialAudioEngine"
        private const val DECODE_STARTUP_TIMEOUT_MS = 20_000L
        private const val PCM_BYTES_PER_SECOND = 48_000L * 2L * 4L
        private const val BYPASS_EPSILON = 1e-6f
        private const val SAMPLE_PEAK_LIMIT = 0.89125094f
        private const val DUAL_OBJECT_BRANCH_GAIN = 0.70710678f
        private val LOUDNORM_JSON = Regex("""\{\s*\"input_i\"[\s\S]*?\}""")
    }
}
