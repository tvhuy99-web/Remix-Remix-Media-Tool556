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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
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
    private val config: VoiceCleanupConfig,
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
        var totalSamples = 0L
        val phaseTimings = linkedMapOf<String, Long>()

        logInfo("cleanup_config", config.diagnosticFields())
        try {
            checkpoint("decode", 0.02f)
            emit(VoiceCleanupState.Progress(0.02f, "Đang giải mã âm thanh"))
            val inputPath = FFmpegKitConfig.getSafParameterForRead(context, inputUri)
            val decodeStartedAt = SystemClock.elapsedRealtime()
            val decode = executeFfmpeg(
                "-y -i \"$inputPath\" -vn -f f32le -ac 1 -ar ${MossFormer2Dsp.SAMPLE_RATE} " +
                    "\"${rawInput.absolutePath}\"",
                "voice_decode",
            )
            phaseTimings["decode_ms"] = SystemClock.elapsedRealtime() - decodeStartedAt
            require(ReturnCode.isSuccess(decode.returnCode)) { "Không thể giải mã tệp đầu vào" }
            require(rawInput.length() >= Float.SIZE_BYTES && rawInput.length() % Float.SIZE_BYTES == 0L) {
                "Tệp đầu vào không chứa PCM hợp lệ"
            }
            totalSamples = rawInput.length() / Float.SIZE_BYTES
            logInfo(
                "decode_complete",
                mapOf(
                    "source_id" to sourceId,
                    "samples" to totalSamples,
                    "pcm_bytes" to rawInput.length(),
                    "decode_ms" to phaseTimings.getValue("decode_ms"),
                ),
            )

            checkpoint("source_metrics", 0.05f)
            emit(VoiceCleanupState.Progress(0.05f, "Đang đo âm lượng bản gốc"))
            val sourceMetricsStartedAt = SystemClock.elapsedRealtime()
            val sourceMetrics = analyzeRawPcm(rawInput, "source")
            phaseTimings["source_metrics_ms"] = SystemClock.elapsedRealtime() - sourceMetricsStartedAt
            logAudioMetrics("source", sourceMetrics)

            checkpoint("model_opening", 0.08f)
            emit(VoiceCleanupState.Progress(0.08f, "Đang mở MossFormer2"))
            val inference = enhancePcm(rawInput, rawEnhanced, totalSamples) { value, phase ->
                emit(VoiceCleanupState.Progress(value, phase))
            }
            require(rawEnhanced.length() == totalSamples * Float.SIZE_BYTES) {
                "Đầu ra MossFormer2 sai thời lượng"
            }
            logInfo(
                "ai_complete",
                mapOf(
                    "segments" to inference.segmentCount,
                    "peak_pss_kb" to inference.peakPssKb,
                ) + config.diagnosticFields() + inference.plan.diagnosticFields() +
                    inference.timing.diagnosticFields() + inference.maskMetrics.diagnosticFields() +
                    inference.seamMetrics.diagnosticFields() +
                    (inference.frontendComparison?.diagnosticFields() ?: emptyMap()),
            )

            checkpoint("ai_metrics", 0.89f)
            emit(VoiceCleanupState.Progress(0.89f, "Đang đo đầu ra AI"))
            val aiMetricsStartedAt = SystemClock.elapsedRealtime()
            val afterAiMetrics = analyzeRawPcm(rawEnhanced, "after_ai")
            phaseTimings["ai_metrics_ms"] = SystemClock.elapsedRealtime() - aiMetricsStartedAt
            logAudioMetrics("after_ai", afterAiMetrics)

            val appliedGainDb = resolveGainDb(sourceMetrics, afterAiMetrics)
            val filter = buildOutputFilter(appliedGainDb)
            logInfo(
                "output_filter_resolved",
                config.diagnosticFields() + mapOf(
                    "applied_gain_db" to appliedGainDb,
                    "filter_count" to filter.split(',').size,
                ),
            )

            checkpoint("encode", 0.93f)
            emit(VoiceCleanupState.Progress(0.93f, "Đang áp dụng âm lượng và mã hóa"))
            val extension = SettingsManager.getAudioFormatExt(context)
            val target = FileExportManager.resultFile(context, "giong_noi_da_lam_sach", extension)
            outputFile = target
            val encodeStartedAt = SystemClock.elapsedRealtime()
            val encode = executeFfmpeg(
                "-y -f f32le -ar ${MossFormer2Dsp.SAMPLE_RATE} -ac 1 " +
                    "-i \"${rawEnhanced.absolutePath}\" -af \"$filter\" " +
                    "${SettingsManager.getAudioEncodingArgs(context)} \"${target.absolutePath}\"",
                "voice_encode",
            )
            phaseTimings["encode_ms"] = SystemClock.elapsedRealtime() - encodeStartedAt
            require(ReturnCode.isSuccess(encode.returnCode)) { "Không thể mã hóa kết quả" }
            require(target.isFile && target.length() > 0L) { "Tệp kết quả bị rỗng" }

            checkpoint("final_metrics", 0.97f)
            emit(VoiceCleanupState.Progress(0.97f, "Đang đo kết quả cuối"))
            val finalMetricsStartedAt = SystemClock.elapsedRealtime()
            val finalMetrics = analyzeEncodedAudio(target, "final_output")
            phaseTimings["final_metrics_ms"] = SystemClock.elapsedRealtime() - finalMetricsStartedAt
            logAudioMetrics("final_output", finalMetrics)

            val pipelineMs = SystemClock.elapsedRealtime() - startedAt
            val audioSeconds = totalSamples.toDouble() / MossFormer2Dsp.SAMPLE_RATE
            val completeTiming = inference.timing.copy(
                pipelineMs = pipelineMs,
                pipelineRealTimeFactor = realTimeFactor(pipelineMs, audioSeconds),
            )
            val report = VoiceCleanupReport(
                source = sourceMetrics,
                afterAi = afterAiMetrics,
                finalOutput = finalMetrics,
                mask = inference.maskMetrics,
                appliedGainDb = appliedGainDb,
                segmentCount = inference.segmentCount,
                inferenceRealTimeFactor = completeTiming.onnxRealTimeFactor,
                timing = completeTiming,
                seams = inference.seamMetrics,
                frontendComparison = inference.frontendComparison,
            )
            outputCommitted = true
            checkpoint("complete", 1f)
            logInfo(
                "pipeline_timing",
                phaseTimings + completeTiming.diagnosticFields(),
            )
            logInfo(
                "pipeline_success",
                mapOf(
                    "source_id" to sourceId,
                    "output_bytes" to target.length(),
                    "format" to extension,
                    "elapsed_ms" to pipelineMs,
                    "applied_gain_db" to appliedGainDb,
                ) + config.diagnosticFields() + inference.plan.diagnosticFields() +
                    completeTiming.diagnosticFields() + finalMetrics.diagnosticFields("final"),
            )
            emit(VoiceCleanupState.Progress(1f, "Hoàn tất"))
            emit(VoiceCleanupState.Success(target, report))
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
                ) + config.diagnosticFields(),
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
        val enhanceStartedAt = SystemClock.elapsedRealtime()
        val plan = VoiceCleanupWindowPlan.resolve(totalSamples, config.windowMode)
        val dsp = MossFormer2Dsp(plan)
        val segmentCount = dsp.segmentCount(totalSamples)
        val inputBytes = ByteArray(dsp.segmentSamples * Float.SIZE_BYTES)
        val segment = FloatArray(dsp.segmentSamples)
        val writeBuffer = ByteBuffer
            .allocate((dsp.segmentSamples - dsp.edgeDiscardSamples) * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val aggregateMask = VoiceCleanupMaskAccumulator()
        val seamAccumulator = VoiceCleanupSeamAccumulator()
        var frontendComparison: VoiceCleanupFrontendComparisonMetrics? = null
        var modelOpenMs = 0L
        var frontendMs = 0L
        var onnxMs = 0L
        var maskApplyMs = 0L
        var pcmWriteMs = 0L
        var writtenSamples = 0L
        var peakPssKb = Debug.getPss()

        logInfo("window_plan_resolved", config.diagnosticFields() + plan.diagnosticFields())
        val modelOpenStartedAt = SystemClock.elapsedRealtime()
        val openedEngine = MossFormer2OnnxEngine.open(
            modelFile = modelFile,
            cpuThreads = SettingsManager.getNumThreads(context),
            frames = dsp.frames,
        )
        modelOpenMs = SystemClock.elapsedRealtime() - modelOpenStartedAt
        engine = openedEngine
        try {
            RandomAccessFile(inputFile, "r").use { input ->
                BufferedOutputStream(FileOutputStream(outputFile), 512 * 1024).use { output ->
                    for (segmentIndex in 0 until segmentCount) {
                        coroutineContext.ensureActive()
                        if (cancelRequested.get()) throw CancellationException("Đã hủy xử lý")
                        Arrays.fill(segment, 0f)
                        val sourceStart = segmentIndex.toLong() * dsp.strideSamples
                        val availableSamples = min(
                            dsp.segmentSamples.toLong(),
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

                        val progressStart = 0.10f + 0.76f * segmentIndex / segmentCount.toFloat()
                        checkpoint("segment_${segmentIndex}_frontend", progressStart)
                        onProgress(progressStart, "Đang phân tích đoạn ${segmentIndex + 1}/$segmentCount")
                        val frontendStartedAt = SystemClock.elapsedRealtime()
                        val ditherSeed = DITHER_SEED_BASE xor (segmentIndex.toLong() * DITHER_SEED_STEP)
                        val features = if (
                            segmentIndex == 0 && config.ditherMode != VoiceCleanupDitherMode.OFF
                        ) {
                            val noDither = dsp.buildFeatures(
                                segment,
                                ditherMode = VoiceCleanupDitherMode.OFF,
                            ).copyOf()
                            val withDither = dsp.buildFeatures(
                                segment,
                                ditherMode = config.ditherMode,
                                ditherSeed = ditherSeed,
                            )
                            frontendComparison = VoiceCleanupFrontendComparisonMetrics.compare(noDither, withDither)
                            logInfo(
                                "frontend_ab_comparison",
                                mapOf("segment_index" to segmentIndex, "dither_seed" to ditherSeed) +
                                    checkNotNull(frontendComparison).diagnosticFields(),
                            )
                            withDither
                        } else {
                            dsp.buildFeatures(
                                segment,
                                ditherMode = config.ditherMode,
                                ditherSeed = ditherSeed,
                            )
                        }
                        frontendMs += SystemClock.elapsedRealtime() - frontendStartedAt

                        val inferenceStartedAt = SystemClock.elapsedRealtime()
                        checkpoint("segment_${segmentIndex}_inference", progressStart)
                        val mask = openedEngine.process(features)
                        onnxMs += SystemClock.elapsedRealtime() - inferenceStartedAt

                        val validFrameRange = dsp.validMaskFrameRange(segmentIndex, availableSamples)
                        val selectedFrames = if (validFrameRange.isEmpty()) 0..0 else validFrameRange
                        if (validFrameRange.isEmpty()) {
                            DiagnosticLogger.warn(
                                TAG,
                                "mask_metrics_padding_fallback",
                                taskId,
                                fields = mapOf(
                                    "segment_index" to segmentIndex,
                                    "available_samples" to availableSamples,
                                ) + plan.diagnosticFields(),
                            )
                        }
                        val segmentMaskAccumulator = VoiceCleanupMaskAccumulator().apply {
                            addFrames(mask, selectedFrames, MossFormer2Dsp.BINS)
                        }
                        aggregateMask.addFrames(mask, selectedFrames, MossFormer2Dsp.BINS)
                        val segmentMask = segmentMaskAccumulator.snapshot()
                        logInfo(
                            "segment_mask_metrics",
                            mapOf(
                                "segment_index" to segmentIndex,
                                "available_samples" to availableSamples,
                                "valid_sample_ratio" to availableSamples.toDouble() / dsp.segmentSamples,
                                "valid_frame_first" to selectedFrames.first,
                                "valid_frame_last" to selectedFrames.last,
                                "padding_frames_excluded" to (dsp.frames - selectedFrames.count()),
                            ) + segmentMask.diagnosticFields("mask"),
                        )

                        val maskStartedAt = SystemClock.elapsedRealtime()
                        val enhanced = dsp.applyMask(segment, mask)
                        maskApplyMs += SystemClock.elapsedRealtime() - maskStartedAt
                        val retained = dsp.retainedRange(segmentIndex)
                        val remaining = (totalSamples - writtenSamples).coerceAtLeast(0L)
                        val count = min(retained.count().toLong(), remaining).toInt()
                        seamAccumulator.addSegment(enhanced, retained.first, count, PCM_SCALE)

                        val writeStartedAt = SystemClock.elapsedRealtime()
                        writeBuffer.clear()
                        for (offset in 0 until count) {
                            val sample = enhanced[retained.first + offset] / PCM_SCALE
                            writeBuffer.putFloat(VoiceCleanupPcmOutput.validatedSample(sample))
                        }
                        output.write(writeBuffer.array(), 0, count * Float.SIZE_BYTES)
                        pcmWriteMs += SystemClock.elapsedRealtime() - writeStartedAt
                        writtenSamples += count
                        peakPssKb = maxOf(peakPssKb, Debug.getPss())

                        val progressEnd = 0.10f + 0.76f * (segmentIndex + 1) / segmentCount.toFloat()
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
                                "window_seconds" to config.windowMode.seconds,
                                "feature_frames" to dsp.frames,
                            ) + plan.diagnosticFields(),
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
        val enhanceMs = SystemClock.elapsedRealtime() - enhanceStartedAt
        val timing = VoiceCleanupTimingMetrics(
            modelOpenMs = modelOpenMs,
            frontendMs = frontendMs,
            onnxMs = onnxMs,
            maskApplyMs = maskApplyMs,
            pcmWriteMs = pcmWriteMs,
            enhanceMs = enhanceMs,
            onnxRealTimeFactor = realTimeFactor(onnxMs, audioSeconds),
            enhanceRealTimeFactor = realTimeFactor(enhanceMs, audioSeconds),
        )
        val seams = seamAccumulator.snapshot()
        if (
            seams.maximumAbsoluteRmsDeltaDb > SEAM_RMS_WARN_DB ||
            (seams.maximumRelativeJumpDb ?: Double.NEGATIVE_INFINITY) > SEAM_JUMP_WARN_DB
        ) {
            DiagnosticLogger.warn(
                TAG,
                "seam_discontinuity_detected",
                taskId,
                fields = seams.diagnosticFields() + plan.diagnosticFields(),
            )
        }
        return InferenceMetrics(
            segmentCount = segmentCount,
            peakPssKb = peakPssKb,
            maskMetrics = aggregateMask.snapshot(),
            seamMetrics = seams,
            frontendComparison = frontendComparison,
            timing = timing,
            plan = plan,
        )
    }

    private suspend fun analyzeRawPcm(file: File, stage: String): VoiceCleanupAudioMetrics {
        val waveform = scanRawPcm(file)
        val loudness = measureAudio(
            command = "-hide_banner -nostats -f f32le -ar ${MossFormer2Dsp.SAMPLE_RATE} -ac 1 " +
                "-i \"${file.absolutePath}\" -af \"$METRICS_FILTER\" -f null -",
            phase = "metrics_$stage",
        )
        return loudness.copy(
            rmsDbfs = waveform.rmsDbfs ?: loudness.rmsDbfs,
            samplePeakDbfs = waveform.samplePeakDbfs ?: loudness.samplePeakDbfs,
        )
    }

    private suspend fun analyzeEncodedAudio(file: File, stage: String): VoiceCleanupAudioMetrics =
        measureAudio(
            command = "-hide_banner -nostats -i \"${file.absolutePath}\" -vn " +
                "-af \"volumedetect,$METRICS_FILTER\" -f null -",
            phase = "metrics_$stage",
        )

    private suspend fun measureAudio(command: String, phase: String): VoiceCleanupAudioMetrics {
        return try {
            val session = executeFfmpeg(command, phase)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                DiagnosticLogger.warn(
                    TAG,
                    "audio_metrics_failed",
                    taskId,
                    fields = mapOf("phase" to phase, "return_code" to session.returnCode.toString()),
                )
                EMPTY_AUDIO_METRICS
            } else {
                VoiceCleanupMetricsParser.parse(session.allLogsAsString)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Error) {
            throw error
        } catch (error: Exception) {
            DiagnosticLogger.warn(
                TAG,
                "audio_metrics_failed",
                taskId,
                message = error.message,
                fields = mapOf("phase" to phase),
            )
            EMPTY_AUDIO_METRICS
        }
    }

    private suspend fun scanRawPcm(file: File): VoiceCleanupAudioMetrics {
        var sampleCount = 0L
        var sumSquares = 0.0
        var peak = 0.0
        val bytes = ByteArray(256 * 1024)
        BufferedInputStream(FileInputStream(file), bytes.size).use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(bytes)
                if (read < 0) break
                require(read % Float.SIZE_BYTES == 0) { "PCM float32 bị lệch byte" }
                val floats = ByteBuffer.wrap(bytes, 0, read)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                while (floats.hasRemaining()) {
                    val sample = floats.get().toDouble()
                    require(sample.isFinite()) { "PCM chứa giá trị không hữu hạn" }
                    sampleCount++
                    sumSquares += sample * sample
                    peak = maxOf(peak, abs(sample))
                }
            }
        }
        val rms = if (sampleCount > 0L) sqrt(sumSquares / sampleCount.toDouble()) else 0.0
        return VoiceCleanupAudioMetrics(
            integratedLufs = null,
            rmsDbfs = amplitudeToDb(rms),
            samplePeakDbfs = amplitudeToDb(peak),
            truePeakDbfs = null,
        )
    }

    private fun resolveGainDb(
        source: VoiceCleanupAudioMetrics,
        afterAi: VoiceCleanupAudioMetrics,
    ): Float {
        val automatic = when (config.loudnessMode) {
            VoiceCleanupLoudnessMode.RAW -> 0.0
            VoiceCleanupLoudnessMode.MATCH_SOURCE -> {
                when {
                    source.integratedLufs != null && afterAi.integratedLufs != null ->
                        source.integratedLufs - afterAi.integratedLufs
                    source.rmsDbfs != null && afterAi.rmsDbfs != null ->
                        source.rmsDbfs - afterAi.rmsDbfs
                    else -> 0.0
                }
            }
            VoiceCleanupLoudnessMode.TARGET_LUFS -> {
                afterAi.integratedLufs?.let { config.targetLufs - it } ?: 0.0
            }
        }
        val requested = automatic + config.outputGainDb
        val applied = requested.coerceIn(MIN_APPLIED_GAIN_DB, MAX_APPLIED_GAIN_DB)
        if (abs(requested - applied) > 0.01) {
            DiagnosticLogger.warn(
                TAG,
                "output_gain_clamped",
                taskId,
                fields = mapOf("requested_gain_db" to requested, "applied_gain_db" to applied),
            )
        }
        if (config.loudnessMode != VoiceCleanupLoudnessMode.RAW && automatic == 0.0) {
            DiagnosticLogger.warn(
                TAG,
                "automatic_loudness_unavailable",
                taskId,
                fields = mapOf("loudness_mode" to config.loudnessMode.name),
            )
        }
        return applied.toFloat()
    }

    private fun buildOutputFilter(appliedGainDb: Float): String {
        val filters = mutableListOf<String>()
        if (abs(appliedGainDb) >= 0.01f) {
            filters += "volume=${formatDecimal(appliedGainDb.toDouble())}dB"
        }
        if (config.limiterEnabled) {
            val linearCeiling = 10.0.pow(config.limiterCeilingDb.toDouble() / 20.0)
            filters += "alimiter=limit=${formatDecimal(linearCeiling)}:level=0:latency=1"
        }
        filters += "aresample=${MossFormer2Dsp.SAMPLE_RATE}"
        return filters.joinToString(",")
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

    private fun logAudioMetrics(stage: String, metrics: VoiceCleanupAudioMetrics) {
        logInfo("audio_metrics", mapOf("stage" to stage) + metrics.diagnosticFields(stage))
    }

    private fun logInfo(event: String, fields: Map<String, Any?> = emptyMap()) {
        DiagnosticLogger.info(TAG, event, taskId, fields = fields)
    }

    private fun amplitudeToDb(amplitude: Double): Double? =
        amplitude.takeIf { it > 0.0 && it.isFinite() }?.let { 20.0 * log10(it) }

    private fun realTimeFactor(elapsedMs: Long, audioSeconds: Double): Double =
        if (audioSeconds > 0.0) elapsedMs / 1000.0 / audioSeconds else 0.0

    private fun formatDecimal(value: Double): String = String.format(Locale.US, "%.6f", value)

    private data class InferenceMetrics(
        val segmentCount: Int,
        val peakPssKb: Long,
        val maskMetrics: VoiceCleanupMaskMetrics,
        val seamMetrics: VoiceCleanupSeamMetrics,
        val frontendComparison: VoiceCleanupFrontendComparisonMetrics?,
        val timing: VoiceCleanupTimingMetrics,
        val plan: VoiceCleanupWindowPlan,
    )

    private companion object {
        const val TAG = "VoiceCleanupProcessor"
        const val PCM_SCALE = 32_768f
        const val MIN_APPLIED_GAIN_DB = -24.0
        const val MAX_APPLIED_GAIN_DB = 24.0
        const val METRICS_FILTER = "loudnorm=I=-16:LRA=11:TP=-1:print_format=json"
        const val DITHER_SEED_BASE = MossFormer2Dsp.DEFAULT_DITHER_SEED
        const val DITHER_SEED_STEP = -7_046_029_254_386_353_131L
        const val SEAM_RMS_WARN_DB = 6.0
        const val SEAM_JUMP_WARN_DB = 12.0
        val EMPTY_AUDIO_METRICS = VoiceCleanupAudioMetrics(null, null, null, null)
    }
}
