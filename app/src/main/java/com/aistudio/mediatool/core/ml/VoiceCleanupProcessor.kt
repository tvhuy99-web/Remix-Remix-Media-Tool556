package com.aistudio.mediatool.core.ml

import android.content.Context
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import com.aistudio.mediatool.core.FileExportManager
import com.aistudio.mediatool.core.SettingsManager
import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
import com.aistudio.mediatool.core.diagnostics.DiagnosticRedactor
import com.aistudio.mediatool.core.diagnostics.ProcessExitDiagnostics
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine

class VoiceCleanupProcessor(
    private val context: Context,
    private val modelFile: File,
    private val taskId: String,
) {
    private val activeFfmpegSessionId = AtomicLong(-1L)
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var engine: MossFormer2OnnxEngine? = null

    fun cancel() {
        cancelRequested.set(true)
        engine?.cancel()
        val sessionId = activeFfmpegSessionId.getAndSet(-1L)
        if (sessionId >= 0L) FFmpegKit.cancel(sessionId)
    }

    fun cleanup(inputUri: Uri): Flow<VoiceCleanupState> = flow {
        cancelRequested.set(false)
        val startedAt = SystemClock.elapsedRealtime()
        val sourceId = DiagnosticRedactor.stableId(inputUri.toString())
        val prefix = "mossformer_${taskId.replace('-', '_')}"
        val rawInput = File(context.cacheDir, "${prefix}_input.f32")
        val rawEnhanced = File(context.cacheDir, "${prefix}_enhanced.f32")
        var outputFile: File? = null
        var outputCommitted = false

        try {
            checkpoint("decode", 0.02f)
            emit(VoiceCleanupState.Progress(0.02f, "Đang giải mã âm thanh"))
            val inputPath = FFmpegKitConfig.getSafParameterForRead(context, inputUri)
            val decode = executeFfmpeg(
                "-y -i \"$inputPath\" -vn -f f32le -ac 1 -ar ${MossFormer2Dsp.SAMPLE_RATE} \"${rawInput.absolutePath}\"",
                "voice_decode",
            )
            require(ReturnCode.isSuccess(decode.returnCode)) { "Không thể giải mã tệp đầu vào" }
            require(rawInput.length() >= Float.SIZE_BYTES && rawInput.length() % Float.SIZE_BYTES == 0L) {
                "Tệp đầu vào không chứa PCM hợp lệ"
            }
            val totalSamples = rawInput.length() / Float.SIZE_BYTES
            logInfo(
                "decode_complete",
                mapOf("source_id" to sourceId, "samples" to totalSamples, "pcm_bytes" to rawInput.length()),
            )

            checkpoint("model_opening", 0.08f)
            emit(VoiceCleanupState.Progress(0.08f, "Đang mở MossFormer2"))
            val metrics = enhancePcm(rawInput, rawEnhanced, totalSamples) { value, phase ->
                emit(VoiceCleanupState.Progress(value, phase))
            }
            require(rawEnhanced.length() == totalSamples * Float.SIZE_BYTES) {
                "Đầu ra MossFormer2 sai thời lượng"
            }
            logInfo(
                "ai_complete",
                mapOf(
                    "segments" to metrics.segmentCount,
                    "inference_ms" to metrics.inferenceMs,
                    "rtf" to metrics.realTimeFactor,
                    "peak_pss_kb" to metrics.peakPssKb,
                ),
            )

            checkpoint("encode", 0.92f)
            emit(VoiceCleanupState.Progress(0.92f, "Đang chuẩn hóa và mã hóa kết quả"))
            val extension = SettingsManager.getAudioFormatExt(context)
            val target = FileExportManager.resultFile(context, "giong_noi_da_lam_sach", extension)
            outputFile = target
            val filter = "loudnorm=I=-16:LRA=11:TP=-1.0,aresample=${MossFormer2Dsp.SAMPLE_RATE}," +
                "alimiter=limit=0.891251:level=0:latency=1"
            val encode = executeFfmpeg(
                "-y -f f32le -ar ${MossFormer2Dsp.SAMPLE_RATE} -ac 1 " +
                    "-i \"${rawEnhanced.absolutePath}\" -af \"$filter\" " +
                    "${SettingsManager.getAudioEncodingArgs(context)} \"${target.absolutePath}\"",
                "voice_encode",
            )
            require(ReturnCode.isSuccess(encode.returnCode)) { "Không thể mã hóa kết quả" }
            require(target.isFile && target.length() > 0L) { "Tệp kết quả bị rỗng" }
            outputCommitted = true
            checkpoint("complete", 1f)
            logInfo(
                "pipeline_success",
                mapOf(
                    "source_id" to sourceId,
                    "output_bytes" to target.length(),
                    "format" to extension,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            emit(VoiceCleanupState.Progress(1f, "Hoàn tất"))
            emit(VoiceCleanupState.Success(target))
        } catch (cancelled: CancellationException) {
            logInfo("pipeline_cancelled", mapOf("elapsed_ms" to SystemClock.elapsedRealtime() - startedAt))
            throw cancelled
        } catch (error: Throwable) {
            DiagnosticLogger.error(
                component = TAG,
                event = "pipeline_failed",
                sessionId = taskId,
                message = error.message,
                fields = mapOf(
                    "model_id" to VoiceCleanupModelRegistry.MOSSFORMER2_ID,
                    "source_id" to sourceId,
                    "out_of_memory" to (error is OutOfMemoryError),
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
                error = error,
            )
            throw error
        } finally {
            engine = null
            runCatching { rawInput.delete() }
            runCatching { rawEnhanced.delete() }
            if (!outputCommitted) outputFile?.let { runCatching { it.delete() } }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun enhancePcm(
        inputFile: File,
        outputFile: File,
        totalSamples: Long,
        onProgress: suspend (Float, String) -> Unit,
    ): InferenceMetrics {
        val segmentCount = MossFormer2Dsp.segmentCount(totalSamples)
        val inputBytes = ByteArray(MossFormer2Dsp.SEGMENT_SAMPLES * Float.SIZE_BYTES)
        val segment = FloatArray(MossFormer2Dsp.SEGMENT_SAMPLES)
        val writeBuffer = ByteBuffer
            .allocate((MossFormer2Dsp.SEGMENT_SAMPLES - MossFormer2Dsp.EDGE_DISCARD_SAMPLES) * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val dsp = MossFormer2Dsp()
        var inferenceMs = 0L
        var writtenSamples = 0L
        var peakPssKb = Debug.getPss()

        val openedEngine = MossFormer2OnnxEngine.open(
            modelFile = modelFile,
            cpuThreads = SettingsManager.getNumThreads(context),
        )
        engine = openedEngine
        try {
            RandomAccessFile(inputFile, "r").use { input ->
                BufferedOutputStream(FileOutputStream(outputFile), 512 * 1024).use { output ->
                    for (segmentIndex in 0 until segmentCount) {
                        coroutineContext.ensureActive()
                        if (cancelRequested.get()) throw CancellationException("Đã hủy xử lý")
                        Arrays.fill(segment, 0f)
                        val sourceStart = segmentIndex.toLong() * MossFormer2Dsp.STRIDE_SAMPLES
                        val availableSamples = min(
                            MossFormer2Dsp.SEGMENT_SAMPLES.toLong(),
                            (totalSamples - sourceStart).coerceAtLeast(0L),
                        ).toInt()
                        if (availableSamples > 0) {
                            val byteCount = availableSamples * Float.SIZE_BYTES
                            input.seek(sourceStart * Float.SIZE_BYTES)
                            input.readFully(inputBytes, 0, byteCount)
                            ByteBuffer.wrap(inputBytes, 0, byteCount)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer()
                                .get(segment, 0, availableSamples)
                        }
                        for (index in segment.indices) segment[index] *= PCM_SCALE

                        val progressStart = 0.10f + 0.78f * segmentIndex / segmentCount.toFloat()
                        checkpoint("segment_${segmentIndex}_frontend", progressStart)
                        onProgress(progressStart, "Đang phân tích đoạn ${segmentIndex + 1}/$segmentCount")
                        val features = dsp.buildFeatures(segment)

                        val inferenceStartedAt = SystemClock.elapsedRealtime()
                        checkpoint("segment_${segmentIndex}_inference", progressStart)
                        val mask = openedEngine.process(features)
                        inferenceMs += SystemClock.elapsedRealtime() - inferenceStartedAt

                        val enhanced = dsp.applyMask(segment, mask)
                        val retained = MossFormer2Dsp.retainedRange(segmentIndex)
                        val remaining = (totalSamples - writtenSamples).coerceAtLeast(0L)
                        val count = min(retained.count().toLong(), remaining).toInt()
                        writeBuffer.clear()
                        for (offset in 0 until count) {
                            val sample = enhanced[retained.first + offset] / PCM_SCALE
                            writeBuffer.putFloat(sample.coerceIn(-1.5f, 1.5f))
                        }
                        output.write(writeBuffer.array(), 0, count * Float.SIZE_BYTES)
                        writtenSamples += count
                        peakPssKb = maxOf(peakPssKb, Debug.getPss())

                        val progressEnd = 0.10f + 0.78f * (segmentIndex + 1) / segmentCount.toFloat()
                        checkpoint("segment_${segmentIndex}_complete", progressEnd)
                        onProgress(progressEnd, "Đã xử lý đoạn ${segmentIndex + 1}/$segmentCount")
                        logInfo(
                            "segment_complete",
                            mapOf(
                                "segment_index" to segmentIndex,
                                "source_start" to sourceStart,
                                "available_samples" to availableSamples,
                                "written_samples" to count,
                                "process_pss_kb" to Debug.getPss(),
                            ),
                        )
                    }
                    output.flush()
                }
            }
        } finally {
            engine = null
            openedEngine.close()
        }
        require(writtenSamples == totalSamples) {
            "Pipeline MossFormer2 ghi $writtenSamples/$totalSamples mẫu"
        }
        val audioSeconds = totalSamples.toDouble() / MossFormer2Dsp.SAMPLE_RATE
        return InferenceMetrics(
            segmentCount = segmentCount,
            inferenceMs = inferenceMs,
            realTimeFactor = if (audioSeconds > 0.0) inferenceMs / 1000.0 / audioSeconds else 0.0,
            peakPssKb = peakPssKb,
        )
    }

    private suspend fun executeFfmpeg(command: String, phase: String): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            val terminal = AtomicBoolean(false)
            val sessionId = AtomicLong(-1L)
            val startedAt = SystemClock.elapsedRealtime()
            val commandId = DiagnosticRedactor.stableId(command)
            continuation.invokeOnCancellation {
                if (terminal.compareAndSet(false, true)) {
                    val id = sessionId.get()
                    if (id >= 0L) {
                        activeFfmpegSessionId.compareAndSet(id, -1L)
                        FFmpegKit.cancel(id)
                    }
                }
            }
            val session = try {
                FFmpegKit.executeAsync(command, { completed ->
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
                            DiagnosticLogger.error(TAG, "ffmpeg_failed", taskId, fields = fields)
                        } else {
                            DiagnosticLogger.info(TAG, "ffmpeg_success", taskId, fields = fields)
                        }
                        continuation.resumeWith(Result.success(completed))
                    }
                }, null, null)
            } catch (error: Exception) {
                terminal.set(true)
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }
            sessionId.set(session.sessionId)
            activeFfmpegSessionId.set(session.sessionId)
            if (terminal.get()) {
                activeFfmpegSessionId.compareAndSet(session.sessionId, -1L)
                FFmpegKit.cancel(session.sessionId)
            }
        }

    private fun checkpoint(phase: String, progress: Float) {
        ProcessExitDiagnostics.checkpoint(
            context = context,
            taskType = VoiceCleanupTask.TYPE,
            taskId = taskId,
            phase = phase,
            progress = progress,
            modelId = VoiceCleanupModelRegistry.MOSSFORMER2_ID,
        )
    }

    private fun logInfo(event: String, fields: Map<String, Any?> = emptyMap()) {
        DiagnosticLogger.info(TAG, event, taskId, fields = fields)
    }

    private data class InferenceMetrics(
        val segmentCount: Int,
        val inferenceMs: Long,
        val realTimeFactor: Double,
        val peakPssKb: Int,
    )

    private companion object {
        const val TAG = "VoiceCleanupProcessor"
        const val PCM_SCALE = 32_768f
    }
}
