package com.aistudio.mediatool.core.spatial

import android.content.Context
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.media.MediaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
        val decoded = File(workDir, "decoded_mono_48k.f32")
        val rendered = File(workDir, "rendered_binaural_48k.f32")
        workDir.mkdirs()
        output.delete()
        val expectedDurationMs = if (preview) sourceDurationMs.coerceAtMost(10_000L) else sourceDurationMs
        val safeFilters = preFilters.filterNot { it.startsWith("alimiter=") }
        DiagnosticLogger.info(
            component = TAG,
            event = "spatial_render_start",
            sessionId = taskId,
            fields = value.diagnosticFields() + mapOf(
                "source_id" to DiagnosticRedactor.stableId(inputSaf),
                "source_duration_ms" to sourceDurationMs,
                "expected_duration_ms" to expectedDurationMs,
                "preview" to preview,
                "video_mode" to isVideoMode,
                "mode_index" to modeIndex,
                "pre_filter_count" to safeFilters.size,
            ),
        )
        try {
            emit(State.Progress(5f, "Đang giải mã nguồn về PCM 48 kHz"))
            val decodeCommand = buildString {
                append("-y -i \"").append(inputSaf).append("\" ")
                if (preview) append("-t 10 ")
                append("-map 0:a:0? -vn ")
                if (safeFilters.isNotEmpty()) {
                    append("-af \"").append(safeFilters.joinToString(",")).append("\" ")
                }
                append("-ac 1 -ar 48000 -c:a pcm_f32le -f f32le \"")
                    .append(decoded.absolutePath)
                    .append("\"")
            }
            runFfmpeg(
                command = decodeCommand,
                phase = "spatial_decode",
                startPercent = 5f,
                endPercent = 35f,
                expectedDurationMs = expectedDurationMs,
            ) { progress, message -> emit(State.Progress(progress, message)) }
            require(decoded.isFile && decoded.length() >= 4L) { "Không giải mã được PCM cho spatial audio" }

            emit(State.Progress(40f, "Đang dựng trường âm thanh HRTF 3D"))
            val metrics = withContext(Dispatchers.Default) {
                SteamAudioBridge.render(decoded, rendered, value)
            }
            require(rendered.isFile && rendered.length() >= 8L) { "Renderer không tạo PCM stereo" }
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_native_complete",
                sessionId = taskId,
                fields = metrics.diagnosticFields() + mapOf(
                    "decoded_bytes" to decoded.length(),
                    "rendered_bytes" to rendered.length(),
                    "render_realtime_factor" to if (expectedDurationMs > 0L) {
                        metrics.renderMs.toDouble() / expectedDurationMs.toDouble()
                    } else null,
                ),
            )

            emit(State.Progress(82f, "Đang mã hóa kết quả binaural"))
            val encodeCommand = buildEncodeCommand(
                inputSaf = inputSaf,
                rendered = rendered,
                output = output,
                isVideoMode = isVideoMode,
                modeIndex = modeIndex,
                extension = extension,
                preview = preview,
            )
            runFfmpeg(
                command = encodeCommand,
                phase = "spatial_encode",
                startPercent = 82f,
                endPercent = 98f,
                expectedDurationMs = expectedDurationMs,
            ) { progress, message -> emit(State.Progress(progress, message)) }
            require(output.isFile && output.length() > 0L) { "Không tạo được tệp spatial audio đầu ra" }
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_render_success",
                sessionId = taskId,
                fields = metrics.diagnosticFields() + mapOf(
                    "output_bytes" to output.length(),
                    "output_extension" to extension,
                ),
            )
            emit(State.Progress(100f, "Spatial audio hoàn tất"))
            emit(State.Success(metrics))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            output.delete()
            DiagnosticLogger.info(
                component = TAG,
                event = "spatial_render_cancelled",
                sessionId = taskId,
                fields = mapOf("source_id" to DiagnosticRedactor.stableId(inputSaf)),
            )
            throw cancelled
        } catch (error: Throwable) {
            output.delete()
            DiagnosticLogger.error(
                component = TAG,
                event = "spatial_render_failed",
                sessionId = taskId,
                message = error.message,
                fields = value.diagnosticFields() + mapOf(
                    "source_id" to DiagnosticRedactor.stableId(inputSaf),
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
        onProgress: suspend (Float, String) -> Unit,
    ) {
        mediaEngine.executeFFmpegCommand(command, diagnosticPhase = phase).collect { state ->
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

                is MediaEngine.ExecutionState.Success -> onProgress(endPercent, "Hoàn tất $phase")
                is MediaEngine.ExecutionState.Error -> error(
                    state.failStackTrace ?: state.logs ?: "FFmpeg thất bại ở $phase",
                )
            }
        }
    }

    private fun buildEncodeCommand(
        inputSaf: String,
        rendered: File,
        output: File,
        isVideoMode: Boolean,
        modeIndex: Int,
        extension: String,
        preview: Boolean,
    ): String = buildString {
        append("-y ")
        if (isVideoMode && modeIndex == 0) {
            append("-i \"").append(inputSaf).append("\" ")
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

    companion object {
        private const val TAG = "SpatialAudioEngine"
    }
}
