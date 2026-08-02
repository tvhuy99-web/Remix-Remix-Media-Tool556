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
            when {
                cancelled.get() -> {
                    activeFfmpegSessionId.compareAndSet(session.sessionId, -1L)
                    FFmpegKit.cancel(session.sessionId)
                }
                terminal.get() -> activeFfmpegSessionId.compareAndSet(session.sessionId, -1L)
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
