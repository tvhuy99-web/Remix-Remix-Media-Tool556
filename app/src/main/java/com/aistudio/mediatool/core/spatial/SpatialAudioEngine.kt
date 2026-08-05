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
import com.arthenica.ffmpegkit.FFprobeKit
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
        val normalizedConfig = config.normalized()
        val roomFit = SpatialRoomTrajectoryPolicy.fit(normalizedConfig)
        val value = roomFit.config
        val workDir = File(context.cacheDir, "spatial_${System.currentTimeMillis()}_${taskId.take(8)}")
        val localSource = File(workDir, "source_local.bin")
        val decoded = File(workDir, "decoded_stereo_48k.f32")
        val rendered = File(workDir, "rendered_binaural_stereo_48k.f32")
        workDir.mkdirs()
        output.delete()
        val sourceId = DiagnosticRedactor.stableId(inputSaf)
        val expectedDurationMs = if (preview) sourceDurationMs.coerceAtMost(10_000L) else sourceDurationMs
        val tailPolicy = SpatialTailPolicy.resolve(isVideoMode = isVideoMode, preview = preview)
        val prefilterDecision = SpatialPrefilterPolicy.apply(preFilters)
        val safeFilters = prefilterDecision.allowed
        if (prefilterDecision.suppressed.isNotEmpty()) {
            DiagnosticLogger.warn(
                component = TAG,
                event = "spatial_prefilters_suppressed",
                sessionId = taskId,
                fields = prefilterDecision.diagnosticFields(),
            )
        }
        if (roomFit.adjusted) {
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_room_trajectory_fitted",
                sessionId = taskId,
                fields = roomFit.diagnosticFields(),
            )
        }
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
            fields = value.diagnosticFields() + roomFit.diagnosticFields() +
                prefilterDecision.diagnosticFields() + tailPolicy.diagnosticFields() +
                sourceInfo.diagnosticFields("source") + runtimeBefore + mapOf(
                "source_id" to sourceId,
                "source_duration_ms" to sourceDurationMs,
                "expected_duration_ms" to expectedDurationMs,
                "preview" to preview,
                "video_mode" to isVideoMode,
                "mode_index" to modeIndex,
                "allowed_pre_filter_count" to safeFilters.size,
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
                // Giữ L/R nếu nguồn đã stereo; FFmpeg tự nhân đôi nguồn mono thành stereo.
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

            val decodedDurationMs = decoded.length() * 1_000L / (48_000L * 2L * 4L)
            val inputLoudness = analyzeLoudness(
                command = "-hide_banner -nostats -f f32le -ar 48000 -ac 2 -i \"${decoded.absolutePath}\" " +
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

            emit(State.Progress(38f, "Đang dựng trường âm thanh stereo HRTF"))
            val nativeBefore = runtimeSnapshot("native_before")
            val metrics = withContext(Dispatchers.Default) {
                SteamAudioBridge.render(decoded, rendered, value)
            }
            val nativeAfter = runtimeSnapshot("native_after")
            require(rendered.isFile && rendered.length() >= 8L) { "Renderer không tạo PCM stereo" }
            val renderedDurationMs = rendered.length() * 1_000L / (48_000L * 2L * 4L)
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_native_complete",
                sessionId = taskId,
                fields = metrics.diagnosticFields() + roomFit.diagnosticFields() +
                    nativeBefore + nativeAfter + mapOf(
                    "decoded_bytes" to decoded.length(),
                    "rendered_bytes" to rendered.length(),
                    "decoded_duration_ms" to decodedDurationMs,
                    "rendered_duration_ms" to renderedDurationMs,
                    "render_realtime_factor" to if (expectedDurationMs > 0L) {
                        metrics.renderMs.toDouble() / expectedDurationMs.toDouble()
                    } else null,
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
                command = "-hide_banner -nostats -i \"${output.absolutePath}\" -map 0:a:0? " +
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
                    ),
            )
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_render_success",
                sessionId = taskId,
                fields = metrics.diagnosticFields() + roomFit.diagnosticFields() +
                    tailPolicy.diagnosticFields() + inputLoudness.diagnosticFields("input") +
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
    ): SpatialLoudnessReading? = try {
        val logs = SpatialLoudnessLogCapture.execute(command)
        SpatialLoudnessParser.parse(logs).also { reading ->
            if (reading == null) {
                DiagnosticLogger.warn(
                    component = TAG,
                    event = "spatial_loudness_parse_failed",
                    sessionId = taskId,
                    fields = mapOf(
                        "phase" to phase,
                        "log_chars" to logs.length,
                        "contains_input_i" to logs.contains("\"input_i\""),
                    ),
                )
            }
        }
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
        input: SpatialLoudnessReading?,
        output: SpatialLoudnessReading?,
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
            if (preview) append("-t 10 ")
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

    companion object {
        private const val TAG = "SpatialAudioEngine"
        private const val DECODE_STARTUP_TIMEOUT_MS = 20_000L
        private val LOUDNORM_JSON = Regex("""\{\s*\"input_i\"[\s\S]*?\}""")
    }
}
