package com.aistudio.mediatool.core.ml

import android.content.Context
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
    private val config: VoiceCleanupConfig,
    private val taskId: String,
) {
    private val activeFfmpegSessionId = AtomicLong(-1L)
    private val cancelRequested = AtomicBoolean(false)

    fun cancel() {
        cancelRequested.set(true)
        val sessionId = activeFfmpegSessionId.getAndSet(-1L)
        if (sessionId >= 0L) FFmpegKit.cancel(sessionId)
    }

    fun cleanup(inputUri: android.net.Uri): Flow<VoiceCleanupState> = flow {
        cancelRequested.set(false)
        val startedAt = SystemClock.elapsedRealtime()
        val sourceId = DiagnosticRedactor.stableId(inputUri.toString())
        val prefix = "voice_${taskId.replace('-', '_')}"
        val rawInput = File(context.cacheDir, "${prefix}_input.f32")
        val rawEnhanced = File(context.cacheDir, "${prefix}_enhanced.f32")
        val temporaryFiles = listOf(rawInput, rawEnhanced)
        var outputFile: File? = null
        var outputCommitted = false

        try {
            logInfo(
                "pipeline_start",
                mapOf(
                    "model_id" to VoiceCleanupModelRegistry.DPDFNET8_48KHZ_HR_ID,
                    "source_id" to sourceId,
                    "strength" to config.strength,
                    "attenuation_limit_db" to config.strength.attenuationLimitDb,
                    "target_lufs" to config.targetLufs,
                ),
            )
            checkpoint("decode", 0.02f)
            emit(VoiceCleanupState.Progress(0.02f, "Đang giải mã âm thanh"))
            val inputPath = FFmpegKitConfig.getSafParameterForRead(context, inputUri)
            val decode = executeFfmpeg(
                "-y -i \"$inputPath\" -vn -f f32le -ac 1 -ar ${DpdfNetDsp.SAMPLE_RATE} \"${rawInput.absolutePath}\"",
                "voice_decode",
            )
            require(ReturnCode.isSuccess(decode.returnCode)) { "Không thể giải mã tệp đầu vào" }
            require(rawInput.length() >= Float.SIZE_BYTES && rawInput.length() % Float.SIZE_BYTES == 0L) {
                "Tệp đầu vào không chứa PCM hợp lệ"
            }
            val totalSamples = rawInput.length() / Float.SIZE_BYTES
            emit(VoiceCleanupState.Progress(0.10f, "Đang mở DPDFNet-8"))
            checkpoint("model_opening", 0.10f)

            val aiStartedAt = SystemClock.elapsedRealtime()
            val metrics = enhancePcm(rawInput, rawEnhanced, totalSamples) { progress ->
                emit(
                    VoiceCleanupState.Progress(
                        0.10f + 0.68f * progress.coerceIn(0f, 1f),
                        "Đang làm sạch giọng nói bằng AI",
                    ),
                )
            }
            require(rawEnhanced.length() == totalSamples * Float.SIZE_BYTES) {
                "Đầu ra AI sai thời lượng"
            }
            logInfo(
                "ai_complete",
                mapOf(
                    "frames" to metrics.frameCount,
                    "inference_ms" to metrics.inferenceMs,
                    "average_frame_ms" to metrics.averageFrameMs,
                    "rtf" to metrics.realTimeFactor,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - aiStartedAt,
                ),
            )

            checkpoint("loudness_analysis", 0.80f)
            emit(VoiceCleanupState.Progress(0.80f, "Đang đo âm lượng giọng nói"))
            val measurement = analyzeLoudness(rawEnhanced)
            logInfo(
                "loudness_measured",
                mapOf(
                    "two_pass_available" to (measurement != null),
                    "input_lufs" to measurement?.inputI,
                    "input_true_peak_db" to measurement?.inputTp,
                    "input_lra" to measurement?.inputLra,
                ),
            )

            checkpoint("normalize_encode", 0.90f)
            emit(VoiceCleanupState.Progress(0.90f, "Đang cân bằng và mã hóa kết quả"))
            val extension = SettingsManager.getAudioFormatExt(context)
            val target = FileExportManager.resultFile(context, "giong_noi_da_lam_sach", extension)
            outputFile = target
            val filter = buildFinalFilter(measurement)
            val encode = executeFfmpeg(
                "-y -f f32le -ar ${DpdfNetDsp.SAMPLE_RATE} -ac 1 -i \"${rawEnhanced.absolutePath}\" " +
                    "-af \"$filter\" ${SettingsManager.getAudioEncodingArgs(context)} \"${target.absolutePath}\"",
                "voice_normalize_encode",
            )
            require(ReturnCode.isSuccess(encode.returnCode)) { "Không thể chuẩn hóa hoặc mã hóa kết quả" }
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
                    "model_id" to VoiceCleanupModelRegistry.DPDFNET8_48KHZ_HR_ID,
                    "source_id" to sourceId,
                    "out_of_memory" to (error is OutOfMemoryError),
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
                error = error,
            )
            throw error
        } finally {
            temporaryFiles.forEach { runCatching { it.delete() } }
            if (!outputCommitted) outputFile?.let { runCatching { it.delete() } }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun enhancePcm(
        inputFile: File,
        outputFile: File,
        totalSamples: Long,
        onProgress: suspend (Float) -> Unit,
    ): InferenceMetrics {
        val virtualSamples = totalSamples + DpdfNetDsp.INPUT_TAIL_PADDING_SAMPLES
        val frameCountLong = 1L + virtualSamples / DpdfNetDsp.HOP_LENGTH
        require(frameCountLong in 1..Int.MAX_VALUE.toLong()) { "Tệp quá dài cho pipeline DPDFNet" }
        val frameCount = frameCountLong.toInt()
        val fullSynthesisSamples = (frameCountLong + 1L) * DpdfNetDsp.HOP_LENGTH
        val sourceStart = (
            DpdfNetDsp.CENTER_PADDING_SAMPLES + DpdfNetDsp.MODEL_ADVANCE_SAMPLES
        ).toLong()
        val sourceEndExclusive = fullSynthesisSamples - DpdfNetDsp.CENTER_PADDING_SAMPLES
        val frame = FloatArray(DpdfNetDsp.WINDOW_LENGTH)
        val inputTensor = FloatArray(DpdfNetDsp.TENSOR_ELEMENTS)
        val noisyHistory = Array(ATTENUATION_FRAME_OFFSET + 1) {
            FloatArray(DpdfNetDsp.TENSOR_ELEMENTS)
        }
        val outputHop = FloatArray(DpdfNetDsp.HOP_LENGTH)
        val inputScratch = ByteArray(DpdfNetDsp.WINDOW_LENGTH * Float.SIZE_BYTES)
        val outputScratch = ByteBuffer.allocate(DpdfNetDsp.HOP_LENGTH * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val dsp = DpdfNetDsp()
        val writer = AlignedFloatWriter(
            output = BufferedOutputStream(FileOutputStream(outputFile), 512 * 1024),
            targetSamples = totalSamples,
            sourceStart = sourceStart,
            sourceEndExclusive = sourceEndExclusive,
            scratch = outputScratch,
        )
        var inferenceMs = 0L

        try {
            RandomAccessFile(inputFile, "r").use { input ->
                DpdfNetLiteRtEngine.open(
                    modelFile = modelFile,
                    cpuThreads = SettingsManager.getNumThreads(context),
                    cacheDirectory = File(context.codeCacheDir, "dpdfnet_cache"),
                ).use { engine ->
                    var lastBucket = -1
                    for (frameIndex in 0 until frameCount) {
                        coroutineContext.ensureActive()
                        if (cancelRequested.get()) throw CancellationException("Đã hủy xử lý")
                        readCenteredFrame(
                            input = input,
                            originalSamples = totalSamples,
                            frameIndex = frameIndex,
                            destination = frame,
                            scratch = inputScratch,
                        )
                        dsp.forward(frame, inputTensor)
                        inputTensor.copyInto(noisyHistory[frameIndex % noisyHistory.size])
                        val inferenceStartedAt = SystemClock.elapsedRealtime()
                        val enhanced = engine.process(inputTensor)
                        inferenceMs += SystemClock.elapsedRealtime() - inferenceStartedAt
                        val alignedNoisy = if (frameIndex >= ATTENUATION_FRAME_OFFSET) {
                            noisyHistory[(frameIndex - ATTENUATION_FRAME_OFFSET) % noisyHistory.size]
                        } else {
                            null
                        }
                        dsp.applyAttenuationLimit(
                            enhanced = enhanced,
                            alignedNoisy = alignedNoisy,
                            noisyBlend = config.strength.noisyBlend,
                        )
                        dsp.inverseHop(enhanced, outputHop)
                        writer.append(outputHop)

                        val progress = (frameIndex + 1f) / frameCount.toFloat()
                        val bucket = (progress * 100f).toInt()
                        if (bucket > lastBucket) {
                            lastBucket = bucket
                            onProgress(progress)
                        }
                    }
                    dsp.flushHop(outputHop)
                    writer.append(outputHop)
                }
            }
            writer.finishWithZeros()
        } finally {
            writer.close()
        }

        val audioSeconds = totalSamples.toDouble() / DpdfNetDsp.SAMPLE_RATE.toDouble()
        val inferenceSeconds = inferenceMs.toDouble() / 1000.0
        return InferenceMetrics(
            frameCount = frameCount,
            inferenceMs = inferenceMs,
            averageFrameMs = if (frameCount > 0) inferenceMs.toDouble() / frameCount else 0.0,
            realTimeFactor = if (audioSeconds > 0.0) inferenceSeconds / audioSeconds else 0.0,
        )
    }

    private fun readCenteredFrame(
        input: RandomAccessFile,
        originalSamples: Long,
        frameIndex: Int,
        destination: FloatArray,
        scratch: ByteArray,
    ) {
        Arrays.fill(destination, 0f)
        val start = frameIndex.toLong() * DpdfNetDsp.HOP_LENGTH - DpdfNetDsp.CENTER_PADDING_SAMPLES
        val endExclusive = start + DpdfNetDsp.WINDOW_LENGTH
        if (start >= 0L && endExclusive <= originalSamples) {
            input.seek(start * Float.SIZE_BYTES)
            input.readFully(scratch, 0, scratch.size)
            val floats = ByteBuffer.wrap(scratch).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            floats.get(destination)
            return
        }

        val virtualLength = originalSamples + DpdfNetDsp.INPUT_TAIL_PADDING_SAMPLES
        for (offset in destination.indices) {
            val virtualIndex = reflectIndex(start + offset, virtualLength)
            destination[offset] = if (virtualIndex < originalSamples) {
                readFloatAt(input, virtualIndex)
            } else {
                0f
            }
        }
    }

    private fun reflectIndex(index: Long, length: Long): Long {
        if (length <= 1L) return 0L
        val period = 2L * (length - 1L)
        val wrapped = ((index % period) + period) % period
        return if (wrapped < length) wrapped else period - wrapped
    }

    private fun readFloatAt(input: RandomAccessFile, sampleIndex: Long): Float {
        input.seek(sampleIndex * Float.SIZE_BYTES)
        return Float.fromBits(Integer.reverseBytes(input.readInt()))
    }

    private suspend fun analyzeLoudness(rawEnhanced: File): LoudnessMeasurement? {
        val loudnorm = "loudnorm=I=${config.targetLufs}:LRA=11:TP=-1.0:print_format=json"
        val session = executeFfmpeg(
            "-hide_banner -nostats -f f32le -ar ${DpdfNetDsp.SAMPLE_RATE} -ac 1 " +
                "-i \"${rawEnhanced.absolutePath}\" -af \"$VOICE_SHAPING_FILTER,$loudnorm\" -f null -",
            "voice_loudness_analysis",
        )
        if (!ReturnCode.isSuccess(session.returnCode)) return null
        return parseLoudnessMeasurement(session.allLogsAsString)
    }

    private fun buildFinalFilter(measurement: LoudnessMeasurement?): String {
        val loudnorm = if (measurement != null) {
            "loudnorm=I=${config.targetLufs}:LRA=11:TP=-1.0:" +
                "measured_I=${measurement.inputI}:measured_LRA=${measurement.inputLra}:" +
                "measured_TP=${measurement.inputTp}:measured_thresh=${measurement.inputThresh}:" +
                "offset=${measurement.targetOffset}:linear=true:print_format=summary"
        } else {
            "loudnorm=I=${config.targetLufs}:LRA=11:TP=-1.0"
        }
        return "$VOICE_SHAPING_FILTER,$loudnorm,aresample=${DpdfNetDsp.SAMPLE_RATE}," +
            "alimiter=limit=0.891251:level=0:latency=1"
    }

    internal fun parseLoudnessMeasurement(logs: String?): LoudnessMeasurement? {
        if (logs.isNullOrBlank()) return null
        val json = extractLoudnessJson(logs) ?: return null
        fun value(key: String): Double? = Regex("\\\"$key\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
            ?.takeIf(Double::isFinite)
        return LoudnessMeasurement(
            inputI = value("input_i") ?: return null,
            inputTp = value("input_tp") ?: return null,
            inputLra = value("input_lra") ?: return null,
            inputThresh = value("input_thresh") ?: return null,
            targetOffset = value("target_offset") ?: return null,
        )
    }

    private suspend fun executeFfmpeg(command: String, phase: String): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            val terminal = AtomicBoolean(false)
            val sessionId = AtomicLong(-1L)
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
                FFmpegKit.executeAsync(
                    command,
                    { completed ->
                        activeFfmpegSessionId.compareAndSet(completed.sessionId, -1L)
                        if (terminal.compareAndSet(false, true)) {
                            if (!ReturnCode.isSuccess(completed.returnCode)) {
                                DiagnosticLogger.error(
                                    component = TAG,
                                    event = "ffmpeg_failed",
                                    sessionId = taskId,
                                    fields = mapOf(
                                        "phase" to phase,
                                        "command_id" to commandId,
                                        "return_code" to completed.returnCode.toString(),
                                        "ffmpeg_tail" to DiagnosticRedactor.sanitizeFfmpegLogs(
                                            completed.allLogsAsString,
                                            maxChars = 8_000,
                                        ),
                                    ),
                                )
                            }
                            continuation.resumeWith(Result.success(completed))
                        }
                    },
                    null,
                    null,
                )
            } catch (error: Exception) {
                terminal.set(true)
                continuation.resumeWith(Result.failure(error))
                return@suspendCancellableCoroutine
            }
            sessionId.set(session.sessionId)
            activeFfmpegSessionId.set(session.sessionId)
            if (terminal.get()) activeFfmpegSessionId.compareAndSet(session.sessionId, -1L)
        }

    private fun checkpoint(phase: String, progress: Float) {
        ProcessExitDiagnostics.checkpoint(
            context = context,
            taskType = VoiceCleanupTask.TYPE,
            taskId = taskId,
            phase = phase,
            progress = progress,
            modelId = VoiceCleanupModelRegistry.DPDFNET8_48KHZ_HR_ID,
        )
    }

    private fun logInfo(event: String, fields: Map<String, Any?> = emptyMap()) {
        DiagnosticLogger.info(TAG, event, taskId, fields = fields)
    }

    internal data class LoudnessMeasurement(
        val inputI: Double,
        val inputTp: Double,
        val inputLra: Double,
        val inputThresh: Double,
        val targetOffset: Double,
    )

    private data class InferenceMetrics(
        val frameCount: Int,
        val inferenceMs: Long,
        val averageFrameMs: Double,
        val realTimeFactor: Double,
    )

    private class AlignedFloatWriter(
        private val output: BufferedOutputStream,
        private val targetSamples: Long,
        private val sourceStart: Long,
        private val sourceEndExclusive: Long,
        private val scratch: ByteBuffer,
    ) : AutoCloseable {
        private var emittedSamples = 0L
        private var writtenSamples = 0L

        fun append(samples: FloatArray) {
            scratch.clear()
            for (sample in samples) {
                val position = emittedSamples++
                if (position < sourceStart || position >= sourceEndExclusive || writtenSamples >= targetSamples) {
                    continue
                }
                scratch.putFloat(if (sample.isFinite()) sample else 0f)
                writtenSamples++
            }
            if (scratch.position() > 0) output.write(scratch.array(), 0, scratch.position())
        }

        fun finishWithZeros() {
            val zeroBytes = ByteArray(DpdfNetDsp.HOP_LENGTH * Float.SIZE_BYTES)
            while (writtenSamples < targetSamples) {
                val samples = minOf(DpdfNetDsp.HOP_LENGTH.toLong(), targetSamples - writtenSamples).toInt()
                output.write(zeroBytes, 0, samples * Float.SIZE_BYTES)
                writtenSamples += samples
            }
            output.flush()
        }

        override fun close() {
            output.close()
        }
    }

    companion object {
        private const val TAG = "VoiceCleanupProcessor"
        private const val ATTENUATION_FRAME_OFFSET = 4
        private const val VOICE_SHAPING_FILTER =
            "highpass=f=70,acompressor=threshold=0.125:ratio=2.5:attack=10:release=180:makeup=1.35"

        internal fun extractLoudnessJson(logs: String): String? {
            var cursor = 0
            var latest: String? = null
            while (true) {
                val inputMarker = logs.indexOf("\"input_i\"", cursor)
                if (inputMarker < 0) return latest
                val objectStart = logs.lastIndexOf('{', inputMarker)
                val offsetMarker = logs.indexOf("\"target_offset\"", inputMarker)
                if (objectStart < 0 || offsetMarker < 0) return latest
                val objectEnd = logs.indexOf('}', offsetMarker)
                if (objectEnd < 0) return latest
                latest = logs.substring(objectStart, objectEnd + 1)
                cursor = objectEnd + 1
            }
        }
    }
}
