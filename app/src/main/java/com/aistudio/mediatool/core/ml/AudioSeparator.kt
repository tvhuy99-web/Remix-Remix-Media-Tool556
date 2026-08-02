package com.aistudio.mediatool.core.ml

import android.content.Context
import android.net.Uri
import android.app.ActivityManager
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
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed class SeparationState {
    data class Progress(val value: Float) : SeparationState()
    data class Success(
        val vocalsFile: File, 
        val musicFile: File,
        val drumsFile: File? = null,
        val bassFile: File? = null,
        val otherFile: File? = null
    ) : SeparationState()
}

class AudioSeparator(
    private val context: Context,
    private val modelFile: File,
    private val model: StemModelDescriptor,
    private val taskId: String,
) {
    private val activeFfmpegSessionId = AtomicLong(-1L)
    @Volatile private var activeRunOptions: OrtSession.RunOptions? = null
    private val sampleRate = model.sampleRate
    private val channels = model.channels
    private val chunkFrames = model.chunking.frames
    private val bytesPerFrame = channels * Float.SIZE_BYTES

    fun cancel() {
        DiagnosticLogger.warn(
            component = TAG,
            event = "cancel_requested",
            sessionId = taskId,
            fields = mapOf("model_id" to model.id),
        )
        val sessionId = activeFfmpegSessionId.getAndSet(-1L)
        if (sessionId >= 0L) FFmpegKit.cancel(sessionId)
        runCatching { activeRunOptions?.setTerminate(true) }
    }

    private suspend fun executeFfmpeg(command: String, phase: String): FFmpegSession = suspendCancellableCoroutine { continuation ->
        val terminal = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val sessionId = AtomicLong(-1L)
        val startedAt = SystemClock.elapsedRealtime()
        val commandId = DiagnosticRedactor.stableId(command)
        DiagnosticLogger.info(
            component = TAG,
            event = "ffmpeg_start",
            sessionId = taskId,
            fields = mapOf(
                "phase" to phase,
                "command_id" to commandId,
                "has_audio_filter" to command.contains("-af "),
                "has_video_filter" to command.contains("-vf "),
                "has_filter_complex" to command.contains("-filter_complex"),
            ),
        )

        continuation.invokeOnCancellation {
            cancelled.set(true)
            if (terminal.compareAndSet(false, true)) {
                DiagnosticLogger.info(
                    component = TAG,
                    event = "ffmpeg_cancelled",
                    sessionId = taskId,
                    fields = mapOf(
                        "phase" to phase,
                        "command_id" to commandId,
                        "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                    ),
                )
            }
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
                            DiagnosticLogger.info(
                                component = TAG,
                                event = "ffmpeg_success",
                                sessionId = taskId,
                                fields = fields,
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
            DiagnosticLogger.error(
                component = TAG,
                event = "ffmpeg_start_failed",
                sessionId = taskId,
                message = error.message,
                fields = mapOf(
                    "phase" to phase,
                    "command_id" to commandId,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                ),
                error = error,
            )
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


    companion object {
        private const val TAG = "AudioSeparator"
    }

    private fun logError(message: String, error: Throwable? = null) {
        DiagnosticLogger.error(
            component = TAG,
            event = "pipeline_error",
            sessionId = taskId,
            message = message,
            error = error,
        )
    }

    private fun logInfo(event: String, fields: Map<String, Any?> = emptyMap(), message: String? = null) {
        DiagnosticLogger.info(TAG, event, taskId, message, fields)
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

    private suspend fun createReflectPaddedPcm(
        source: File,
        destination: File,
        sourceFrames: Long,
        paddingFrames: Int,
    ) {
        require(sourceFrames > paddingFrames.toLong() * 2L)
        val edgeByteCount = Math.multiplyExact(paddingFrames, bytesPerFrame)

        fun reverseFrames(bytes: ByteArray): ByteArray {
            val reversed = ByteArray(bytes.size)
            for (frame in 0 until paddingFrames) {
                val sourceOffset = (paddingFrames - 1 - frame) * bytesPerFrame
                System.arraycopy(bytes, sourceOffset, reversed, frame * bytesPerFrame, bytesPerFrame)
            }
            return reversed
        }

        val prefix = ByteArray(edgeByteCount)
        val suffix = ByteArray(edgeByteCount)
        RandomAccessFile(source, "r").use { input ->
            // PyTorch reflect padding không lặp lại chính sample ở biên:
            // trái = x[p]..x[1], phải = x[n-2]..x[n-p-1].
            input.seek(bytesPerFrame.toLong())
            input.readFully(prefix)
            input.seek(
                (sourceFrames - paddingFrames.toLong() - 1L) * bytesPerFrame.toLong(),
            )
            input.readFully(suffix)
        }

        try {
            FileOutputStream(destination).use { fileOutput ->
                java.io.BufferedOutputStream(fileOutput, 524288).use { output ->
                    output.write(reverseFrames(prefix))
                    FileInputStream(source).buffered(524288).use { input ->
                        val copyBuffer = ByteArray(524288)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(copyBuffer)
                            if (read < 0) break
                            output.write(copyBuffer, 0, read)
                        }
                    }
                    output.write(reverseFrames(suffix))
                    output.flush()
                    fileOutput.fd.sync()
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun createSessionOptions(hardwareAccelerationIndex: Int): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            val conservativeMemoryMode = model.id == StemModelRegistry.MEL_BAND_ROFORMER_ID
            setOptimizationLevel(
                if (conservativeMemoryMode) OrtSession.SessionOptions.OptLevel.BASIC_OPT
                else OrtSession.SessionOptions.OptLevel.ALL_OPT,
            )
            if (conservativeMemoryMode) {
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setMemoryPatternOptimization(false)
                setCPUArenaAllocator(false)
                setInterOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            val requestedThreads = if (conservativeMemoryMode) 1 else SettingsManager.getNumThreads(context)
            val threading = OnnxThreadingPolicy.resolve(hardwareAccelerationIndex, requestedThreads)
            setIntraOpNumThreads(threading.ortIntraOpThreads)
            logInfo(
                event = "onnx_threading",
                fields = mapOf(
                    "requested_threads" to requestedThreads,
                    "ort_intra_op_threads" to threading.ortIntraOpThreads,
                    "xnnpack_threads" to threading.xnnpackThreads,
                ),
            )

            when (hardwareAccelerationIndex) {
                1 -> {
                    logInfo("onnx_provider_config", mapOf("provider" to OnnxAcceleration.NNAPI))
                    try {
                        val method = this::class.java.getMethod("addNnapi", Int::class.javaPrimitiveType)
                        method.invoke(this, 1)
                    } catch (_: Exception) {
                        addNnapi()
                    }
                }
                2 -> {
                    val xnnpackThreads = requireNotNull(threading.xnnpackThreads)
                    // XNNPACK có pool riêng. Tắt spinning của ORT và giữ ORT intra-op = 1
                    // theo khuyến nghị chính thức để tránh hai pool tranh CPU.
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    addXnnpack(hashMapOf("intra_op_num_threads" to xnnpackThreads.toString()))
                    logInfo(
                        "onnx_provider_config",
                        mapOf("provider" to OnnxAcceleration.XNNPACK, "threads" to xnnpackThreads),
                    )
                }
                3 -> {
                    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                    setInterOpNumThreads(1)
                    addConfigEntry("session.intra_op.allow_spinning", "0")
                    // QNN GPU không hỗ trợ toàn bộ graph của model này trên mọi thiết bị.
                    // Giữ CPU EP làm fallback để các node còn lại vẫn chạy thay vì lỗi khi mở session.
                    addQnn(hashMapOf("backend_type" to "gpu"))
                    logInfo(
                        "onnx_provider_config",
                        mapOf(
                            "provider" to OnnxAcceleration.QNN_GPU,
                            "backend_type" to "gpu",
                            "cpu_fallback_disabled" to false,
                        ),
                    )
                }
                else -> logInfo("onnx_provider_config", mapOf("provider" to OnnxAcceleration.CPU))
            }
        }

    private data class OpenedSession(
        val options: OrtSession.SessionOptions,
        val session: OrtSession,
        val provider: OnnxAcceleration,
    )

    private fun openSession(env: OrtEnvironment): OpenedSession {
        val configuredAcceleration = OnnxAcceleration.fromSettingsIndex(
            SettingsManager.getHardwareAccelIndex(context),
        )
        val conservativeMemoryMode = model.id == StemModelRegistry.MEL_BAND_ROFORMER_ID
        val modelForcedAcceleration = when (model.id) {
            StemModelRegistry.HTDEMUCS_FT_VOCALS_QNN_ID -> OnnxAcceleration.QNN_GPU
            else -> null
        }
        val effectiveAcceleration = modelForcedAcceleration ?: configuredAcceleration
        val requestedAcceleration = when {
            conservativeMemoryMode -> OnnxAcceleration.CPU
            effectiveAcceleration in model.allowedAccelerators -> effectiveAcceleration
            else -> OnnxAcceleration.CPU
        }.also { selected ->
            if (selected != configuredAcceleration) {
                val event = when {
                    conservativeMemoryMode -> "provider_forced_for_memory"
                    modelForcedAcceleration != null -> "provider_forced_by_model"
                    else -> "provider_not_allowed"
                }
                DiagnosticLogger.warn(
                    component = TAG,
                    event = event,
                    sessionId = taskId,
                    fields = mapOf(
                        "model_id" to model.id,
                        "configured_provider" to configuredAcceleration,
                        "model_forced_provider" to modelForcedAcceleration,
                        "effective_provider" to selected,
                    ),
                )
            }
        }

        // QNN GPU vẫn được ưu tiên cho model FP16. CPU EP xử lý các node mà QNN
        // không nhận trong cùng session. Nếu bản thân session QNN không mở được
        // trên thiết bị, thử XNNPACK trước khi rơi về CPU thuần.
        val providerChain = mutableListOf(requestedAcceleration).apply {
            if (
                requestedAcceleration == OnnxAcceleration.QNN_GPU &&
                OnnxAcceleration.XNNPACK in model.allowedAccelerators
            ) {
                add(OnnxAcceleration.XNNPACK)
            }
            if (OnnxAcceleration.CPU !in this) add(OnnxAcceleration.CPU)
        }.distinct()
        val failures = mutableListOf<String>()

        for ((attemptIndex, candidate) in providerChain.withIndex()) {
            val options = try {
                createSessionOptions(candidate.settingsIndex)
            } catch (error: Exception) {
                val detail = "${candidate.name}/config: ${error.message ?: error::class.java.simpleName}"
                failures += detail
                DiagnosticLogger.warn(
                    component = TAG,
                    event = "onnx_provider_attempt_failed",
                    sessionId = taskId,
                    message = error.message,
                    fields = mapOf(
                        "model_id" to model.id,
                        "requested_provider" to requestedAcceleration,
                        "attempted_provider" to candidate,
                        "attempt_index" to attemptIndex,
                        "failure_stage" to "configure",
                        "next_provider" to providerChain.getOrNull(attemptIndex + 1),
                    ),
                    error = error,
                )
                continue
            }

            try {
                val session = env.createSession(modelFile.absolutePath, options)
                logInfo(
                    event = "onnx_session_opened",
                    fields = mapOf(
                        "model_id" to model.id,
                        "requested_provider" to requestedAcceleration,
                        "effective_provider" to candidate,
                        "attempt_index" to attemptIndex,
                        "provider_chain" to providerChain.joinToString("->"),
                        "cpu_ep_fallback_enabled" to (candidate == OnnxAcceleration.QNN_GPU),
                    ),
                )
                return OpenedSession(
                    options = options,
                    session = session,
                    provider = candidate,
                )
            } catch (error: Exception) {
                options.close()
                val detail = "${candidate.name}/session: ${error.message ?: error::class.java.simpleName}"
                failures += detail
                DiagnosticLogger.warn(
                    component = TAG,
                    event = "onnx_provider_attempt_failed",
                    sessionId = taskId,
                    message = error.message,
                    fields = mapOf(
                        "model_id" to model.id,
                        "requested_provider" to requestedAcceleration,
                        "attempted_provider" to candidate,
                        "attempt_index" to attemptIndex,
                        "failure_stage" to "create_session",
                        "next_provider" to providerChain.getOrNull(attemptIndex + 1),
                    ),
                    error = error,
                )
            }
        }

        throw IllegalStateException(
            "Không thể mở model ${model.displayName} bằng chuỗi tăng tốc " +
                "${providerChain.joinToString(" -> ")}. ${failures.joinToString(" | ")}",
        )
    }

    suspend fun separate(inputUri: Uri): Flow<SeparationState> = flow {
        val pipelineStartedAt = SystemClock.elapsedRealtime()
        val sourceId = DiagnosticRedactor.stableId(inputUri.toString())
        checkpoint("pipeline_start", 0.01f)
        emit(SeparationState.Progress(0.01f)) // Start

        // Mỗi tác vụ có thư mục riêng để không ghi đè khi service bị khởi động lại.
        val workDir = File(context.cacheDir, "stem-work-${System.currentTimeMillis()}").apply {
            require(mkdirs() || isDirectory) { "Không thể tạo thư mục tạm cho tác vụ tách stem" }
        }
        val tempRawMix = File(workDir, "mix.raw")
        val tempRawInference = File(workDir, "mix-reflect-padded.raw")
        val tempRawVocals = File(workDir, "vocals.raw")
        val tempRawMusic = File(workDir, "music.raw")
        val tempRawDrums = File(workDir, "drums.raw")
        val tempRawBass = File(workDir, "bass.raw")
        val tempRawOther = File(workDir, "other.raw")
        val createdOutputs = mutableListOf<File>()
        var outputsCommitted = false

        val is4StemMode = model.mode == StemMode.FOUR_STEM

        logInfo(
            event = "pipeline_start",
            fields = mapOf(
                "model_id" to model.id,
                "source_id" to sourceId,
                "stem_count" to model.mode.stemCount,
                "sample_rate" to sampleRate,
                "channels" to channels,
                "chunk_frames" to chunkFrames,
                "overlap_frames" to model.chunking.overlapFrames,
                "normalization" to model.normalization,
            ),
        )
        try {
            // 2. Giữ PCM float32 xuyên suốt pipeline để stem vượt 0 dBFS không bị
            // cắt sớm trước khi người dùng chọn codec/định dạng xuất cuối cùng.
            checkpoint("decode_input", 0.05f)
            emit(SeparationState.Progress(0.05f))
            val inputPath = com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(context, inputUri)
            val decodeCmd = "-y -i \"$inputPath\" -f f32le -ac $channels -ar $sampleRate \"${tempRawMix.absolutePath}\""
            val decodeSession = executeFfmpeg(decodeCmd, phase = "decode_input")
            
            if (!ReturnCode.isSuccess(decodeSession.returnCode)) {
                throw Exception("Không thể giải mã tệp đầu vào bằng FFmpeg")
            }
            require(tempRawMix.length() >= bytesPerFrame) { "Tệp đầu vào không chứa âm thanh hợp lệ" }
            logInfo(
                event = "decode_complete",
                fields = mapOf("pcm_bytes" to tempRawMix.length()),
            )

            val originalFrames = tempRawMix.length() / bytesPerFrame
            val requestedBoundaryFrames = model.chunking.reflectBoundaryFrames
            val boundaryPaddingFrames = requestedBoundaryFrames.takeIf {
                it > 0 && originalFrames > it.toLong() * 2L
            } ?: 0
            val inferencePcm = if (boundaryPaddingFrames > 0) {
                val paddingStartedAt = SystemClock.elapsedRealtime()
                createReflectPaddedPcm(
                    source = tempRawMix,
                    destination = tempRawInference,
                    sourceFrames = originalFrames,
                    paddingFrames = boundaryPaddingFrames,
                )
                logInfo(
                    event = "boundary_padding_complete",
                    fields = mapOf(
                        "padding_frames_per_side" to boundaryPaddingFrames,
                        "original_frames" to originalFrames,
                        "padded_bytes" to tempRawInference.length(),
                        "elapsed_ms" to SystemClock.elapsedRealtime() - paddingStartedAt,
                    ),
                )
                if (model.normalization == AudioNormalization.NONE) tempRawMix.delete()
                tempRawInference
            } else {
                logInfo(
                    event = "boundary_padding_skipped",
                    fields = mapOf(
                        "requested_frames" to requestedBoundaryFrames,
                        "original_frames" to originalFrames,
                    ),
                )
                tempRawMix
            }

            checkpoint("decode_complete", 0.10f)
            emit(SeparationState.Progress(0.1f)) // Decode complete

            // 3. Process with ONNX
            checkpoint("session_opening", 0.10f)
            val env = OrtEnvironment.getEnvironment()
            val sessionStartedAt = SystemClock.elapsedRealtime()
            val openedSession = openSession(env)
            val sessionOptions = openedSession.options
            val session = openedSession.session
            val runOptions = OrtSession.RunOptions()
            activeRunOptions = runOptions
            checkpoint("session_opened", 0.11f)
            logInfo(
                event = "onnx_session_opened",
                fields = mapOf(
                    "model_id" to model.id,
                    "provider" to openedSession.provider,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - sessionStartedAt,
                ),
            )

            try {
                val totalBytes = inferencePcm.length()
                val totalFrames = totalBytes / bytesPerFrame
                logInfo(
                    event = "inference_input_ready",
                    fields = mapOf("frames" to totalFrames, "bytes" to totalBytes),
                )

                val (mean, std) = if (model.normalization == AudioNormalization.GLOBAL_MONO_MEAN_STD) {
                    // Chỉ model khai báo rõ mới dùng chuẩn hóa kiểu Demucs. Mel-Band
                    // RoFormer waveform export nhận PCM [-1, 1] trực tiếp.
                    logInfo("normalization_scan_start", mapOf("mode" to model.normalization))
                    var welfordMean = 0.0
                    var welfordM2 = 0.0
                    var globalFramesCount = 0L
                    val statsBuffer = ByteArray(8192 * bytesPerFrame)
                    var scannedBytes = 0L
                    var lastStatsEmission = 0L
                    val statsTotalBytes = tempRawMix.length()
                    DataInputStream(java.io.BufferedInputStream(FileInputStream(tempRawMix), 524288)).use { statsInput ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val readBytes = statsInput.read(statsBuffer)
                            if (readBytes <= 0) break
                            scannedBytes += readBytes
                            if (scannedBytes - lastStatsEmission >= 4L * 1024L * 1024L) {
                                lastStatsEmission = scannedBytes
                                val statsRatio = (scannedBytes.toDouble() / statsTotalBytes.coerceAtLeast(1L)).toFloat()
                                emit(SeparationState.Progress((0.10f + 0.02f * statsRatio).coerceIn(0.10f, 0.12f)))
                            }
                            val frames = readBytes / bytesPerFrame
                            val floatBuffer = ByteBuffer.wrap(statsBuffer, 0, readBytes)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asFloatBuffer()
                            for (f in 0 until frames) {
                                val left = floatBuffer.get(f * channels)
                                val right = floatBuffer.get(f * channels + 1)
                                val mono = (left + right) / 2.0f
                                globalFramesCount++
                                val delta = mono - welfordMean
                                welfordMean += delta / globalFramesCount
                                val delta2 = mono - welfordMean
                                welfordM2 += delta * delta2
                            }
                        }
                    }
                    val variance = if (globalFramesCount > 0) welfordM2 / globalFramesCount else 0.0
                    welfordMean.toFloat() to Math.max(1e-4, Math.sqrt(variance)).toFloat()
                } else {
                    emit(SeparationState.Progress(0.12f))
                    0f to 1f
                }
                logInfo(
                    event = "normalization_ready",
                    fields = mapOf("mode" to model.normalization, "mean" to mean, "std" to std),
                )
                
                var processedFrames = 0L

                checkpoint("buffers_allocating", 0.12f)
                val bSize = 524288 // 512KB buffer I/O
                val inputStream = DataInputStream(java.io.BufferedInputStream(FileInputStream(inferencePcm), bSize))
                val vocalsOut = DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawVocals), bSize))
                val musicOut = DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawMusic), bSize))
                val drumsOut = if (is4StemMode) DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawDrums), bSize)) else null
                val bassOut = if (is4StemMode) DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawBass), bSize)) else null
                val otherOut = if (is4StemMode) DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(tempRawOther), bSize)) else null

                var streamFailure: Throwable? = null
                try {
                val overlapSize = model.chunking.overlapFrames
                val stepSize = model.chunking.stepFrames

                val chunkBufferBytes = ByteArray(chunkFrames * bytesPerFrame)
                val chunkBufferFloat = FloatArray(chunkFrames * channels)
                val sharedInputBufferDirect = ByteBuffer
                    .allocateDirect(chunkFrames * channels * Float.SIZE_BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                
                // Buffer lưu các đoạn âm thanh nối (Overlap)
                val outVocalsOverlap = FloatArray(overlapSize * channels)
                val outBeatOverlap = FloatArray(overlapSize * channels)
                val outDrumsOverlap = if (is4StemMode) FloatArray(overlapSize * channels) else null
                val outBassOverlap = if (is4StemMode) FloatArray(overlapSize * channels) else null
                val outOtherOverlap = if (is4StemMode) FloatArray(overlapSize * channels) else null

                val vocalsMerged = ByteBuffer.allocate(chunkFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
                val vocalsMergedFloat = vocalsMerged.asFloatBuffer()
                val musicMerged = ByteBuffer.allocate(chunkFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
                val musicMergedFloat = musicMerged.asFloatBuffer()
                val drumsMerged = if (is4StemMode) ByteBuffer.allocate(chunkFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN) else null
                val drumsMergedFloat = drumsMerged?.asFloatBuffer()
                val bassMerged = if (is4StemMode) ByteBuffer.allocate(chunkFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN) else null
                val bassMergedFloat = bassMerged?.asFloatBuffer()
                val otherMerged = if (is4StemMode) ByteBuffer.allocate(chunkFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN) else null
                val otherMergedFloat = otherMerged?.asFloatBuffer()

                val inputName = model.tensor.inputName.takeIf(session.inputNames::contains)
                    ?: session.inputNames.singleOrNull()
                    ?: error("Không tìm thấy input '${model.tensor.inputName}'")
                val outputName = model.tensor.outputName.takeIf(session.outputNames::contains)
                    ?: session.outputNames.singleOrNull()
                    ?: error("Không tìm thấy output '${model.tensor.outputName}'")
                val (inShape, inCAxis, inFAxis) = when (model.tensor.inputLayout) {
                    TensorAudioLayout.BATCH_CHANNEL_FRAME -> Triple(
                        longArrayOf(1, channels.toLong(), chunkFrames.toLong()),
                        1,
                        2,
                    )
                    TensorAudioLayout.BATCH_FRAME_CHANNEL -> Triple(
                        longArrayOf(1, chunkFrames.toLong(), channels.toLong()),
                        2,
                        1,
                    )
                }
                val expectedShape = (session.inputInfo[inputName]?.info as? ai.onnxruntime.TensorInfo)?.shape
                require(
                    expectedShape == null ||
                        (expectedShape.size == inShape.size && expectedShape.indices.all { axis ->
                            expectedShape[axis] <= 0L || expectedShape[axis] == inShape[axis]
                        }),
                ) { "Input model không đúng contract: ${expectedShape?.joinToString(" x ")}" }
                val inStrides = LongArray(3)
                inStrides[2] = 1L
                inStrides[1] = inShape[2]
                inStrides[0] = inShape[1] * inShape[2]
                logInfo(
                    event = "tensor_contract_validated",
                    fields = mapOf(
                        "input_name" to inputName,
                        "output_name" to outputName,
                        "input_shape" to inShape.joinToString("x"),
                        "model_input_shape" to expectedShape?.joinToString("x"),
                        "input_layout" to model.tensor.inputLayout,
                        "output_layout" to model.tensor.outputLayout,
                        "source_count" to model.tensor.sourceCount,
                    ),
                )

                checkpoint("buffers_ready", 0.12f)
                logInfo("inference_buffers_ready", memoryFields())
                var isFirstChunk = true
                var chunkIndex = 0

                while (coroutineContext.isActive) {
                        val chunkStartedAt = SystemClock.elapsedRealtime()
                        coroutineContext.ensureActive()
                        val framesToRead = if (isFirstChunk) chunkFrames else stepSize
                        val bytesToRead = framesToRead * bytesPerFrame
                        java.util.Arrays.fill(chunkBufferBytes, 0.toByte())

                    var bytesRead = 0
                    while (bytesRead < bytesToRead) {
                        coroutineContext.ensureActive()
                        val read = inputStream.read(chunkBufferBytes, bytesRead, bytesToRead - bytesRead)
                        if (read == -1) break
                        bytesRead += read
                    }
                        
                        val actualFramesRead = bytesRead / bytesPerFrame
                    logInfo(
                        event = "chunk_read",
                        fields = mapOf(
                            "chunk_index" to chunkIndex,
                            "frames" to actualFramesRead,
                            "bytes" to bytesRead,
                            "first_chunk" to isFirstChunk,
                        ),
                    )

                    if (actualFramesRead == 0 && !isFirstChunk) {
                        logInfo(
                            event = "overlap_tail_flush",
                            fields = mapOf("chunk_index" to chunkIndex, "frames" to overlapSize),
                        )
                        // EOF: Xả nốt đoạn overlap cuối cùng.
                        vocalsMergedFloat.clear()
                        musicMergedFloat.clear()
                        drumsMergedFloat?.clear()
                        bassMergedFloat?.clear()
                        otherMergedFloat?.clear()
                        
                        for(i in 0 until overlapSize * channels) {
                            // Buffer overlap lưu mẫu thô, vì vậy đoạn cuối có thể
                            // được xả trực tiếp mà không khuếch đại nghịch đảo cửa sổ.
                            val v_val = outVocalsOverlap[i]
                            val m_val = outBeatOverlap[i]
                            
                            vocalsMergedFloat.put(v_val)
                            musicMergedFloat.put(m_val)
                            
                            if (is4StemMode) {
                                val d_val = outDrumsOverlap!![i]
                                val b_val = outBassOverlap!![i]
                                val o_val = outOtherOverlap!![i]
                                drumsMergedFloat!!.put(d_val)
                                bassMergedFloat!!.put(b_val)
                                otherMergedFloat!!.put(o_val)
                            }
                        }
                        vocalsOut.write(vocalsMerged.array(), 0, overlapSize * bytesPerFrame)
                        musicOut.write(musicMerged.array(), 0, overlapSize * bytesPerFrame)
                        if (is4StemMode) {
                            drumsOut!!.write(drumsMerged!!.array(), 0, overlapSize * bytesPerFrame)
                            bassOut!!.write(bassMerged!!.array(), 0, overlapSize * bytesPerFrame)
                            otherOut!!.write(otherMerged!!.array(), 0, overlapSize * bytesPerFrame)
                        }
                        break
                    }
                    if (actualFramesRead == 0 && isFirstChunk) break // File rỗng

                    if (!isFirstChunk) {
                        // Giữ overlap của chunk trước, còn vùng mới phải luôn bắt đầu bằng im lặng.
                        System.arraycopy(chunkBufferFloat, stepSize * channels, chunkBufferFloat, 0, overlapSize * channels)
                        java.util.Arrays.fill(chunkBufferFloat, overlapSize * channels, chunkBufferFloat.size, 0f)
                    } else {
                        java.util.Arrays.fill(chunkBufferFloat, 0f)
                    }

                    val floatBuffer = ByteBuffer.wrap(chunkBufferBytes, 0, bytesRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()

                    val offset = if (isFirstChunk) 0 else overlapSize * channels
                    for (i in 0 until actualFramesRead * channels) {
                        chunkBufferFloat[offset + i] = floatBuffer.get(i)
                    }

                    val validFramesInChunk = if (isFirstChunk) actualFramesRead else (overlapSize + actualFramesRead)

                    val isFullRead = (isFirstChunk && actualFramesRead == chunkFrames) || (!isFirstChunk && actualFramesRead == stepSize)
                    val framesToWrite = if (isFullRead) stepSize else validFramesInChunk

                    for (ch in 0 until channels) {
                        for (f in 0 until chunkFrames) {
                            val idx = ch * inStrides[inCAxis] + f * inStrides[inFAxis]
                            val rawVal = chunkBufferFloat[f * channels + ch]
                            val normVal = (rawVal - mean) / std
                            sharedInputBufferDirect.put(idx.toInt(), normVal)
                        }
                    }
                    sharedInputBufferDirect.rewind()

                    val inputTensor = OnnxTensor.createTensor(env, sharedInputBufferDirect, inShape)
                    var result: ai.onnxruntime.OrtSession.Result? = null
                    
                    try {
                        val inputMap = mapOf(inputName to inputTensor)

                        // Inference
                        val inferenceStartedAt = SystemClock.elapsedRealtime()
                        val chunkProgress = if (totalFrames > 0L) {
                            (0.12f + 0.78f * (processedFrames.toFloat() / totalFrames.toFloat()))
                                .coerceIn(0.12f, 0.88f)
                        } else {
                            0.12f
                        }
                        checkpoint("inference_chunk_${chunkIndex}_start", chunkProgress)
                        logInfo(
                            event = "inference_chunk_start",
                            fields = mapOf(
                                "chunk_index" to chunkIndex,
                                "valid_frames" to validFramesInChunk,
                                "frames_to_write" to framesToWrite,
                            ) + memoryFields(),
                        )
                        result = session.run(inputMap, setOf(outputName), runOptions)
                        checkpoint("inference_chunk_${chunkIndex}_complete", chunkProgress)
                        logInfo(
                            event = "inference_chunk_complete",
                            fields = mapOf(
                                "chunk_index" to chunkIndex,
                                "elapsed_ms" to SystemClock.elapsedRealtime() - inferenceStartedAt,
                            ) + memoryFields(),
                        )
                        
                        val outOnnxTensor = result.get(0) as? OnnxTensor
                            ?: error("Model không trả về tensor âm thanh")
                        val outShape = (outOnnxTensor.info as? ai.onnxruntime.TensorInfo)?.shape
                            ?: error("Không đọc được shape đầu ra của model")
                        require(outShape.size == 4 && outShape[0] == 1L) {
                            "Shape đầu ra không được hỗ trợ: ${outShape.joinToString(" x ")}"
                        }

                        val sAxis = 1
                        val (cAxis, fAxis) = when (model.tensor.outputLayout) {
                            TensorSourceLayout.BATCH_SOURCE_CHANNEL_FRAME -> 2 to 3
                            TensorSourceLayout.BATCH_SOURCE_FRAME_CHANNEL -> 3 to 2
                        }

                        val outStrides = LongArray(outShape.size)
                        var currentStr = 1L
                        for(i in outShape.indices.reversed()) {
                            outStrides[i] = currentStr
                            currentStr *= outShape[i]
                        }

                        val outBuffer = outOnnxTensor.floatBuffer
                            ?: error("Tensor đầu ra không chứa dữ liệu float")
                        val sourceCount = outShape[sAxis].toInt()
                        val outputChannels = outShape[cAxis].toInt()
                        val outputFrames = outShape[fAxis].toInt()
                        require(
                            sourceCount == model.tensor.sourceCount &&
                                outputChannels == channels &&
                                outputFrames >= chunkFrames
                        ) {
                            "Model không tương thích: sources=$sourceCount, channels=$outputChannels, frames=$outputFrames"
                        }
                        val expectedElements = outShape.fold(1L) { total, value -> total * value }
                        require(expectedElements <= outBuffer.capacity().toLong()) { "Tensor đầu ra bị thiếu dữ liệu" }
                        if (chunkIndex == 0) {
                            logInfo(
                                event = "output_tensor_validated",
                                fields = mapOf(
                                    "shape" to outShape.joinToString("x"),
                                    "buffer_capacity" to outBuffer.capacity(),
                                    "sources" to sourceCount,
                                    "channels" to outputChannels,
                                    "frames" to outputFrames,
                                ),
                            )
                        }

                        fun sourceValue(source: Int, channel: Int, frame: Int): Float {
                            val offset = source * outStrides[sAxis] +
                                channel * outStrides[cAxis] +
                                frame * outStrides[fAxis]
                            return outBuffer.get(offset.toInt())
                        }

                        fun mixValue(mix: SourceMix, channel: Int, frame: Int): Float {
                            var value = 0f
                            mix.sourceIndices.forEach { source -> value += sourceValue(source, channel, frame) }
                            val denormalized = value * std + mean
                            return if (denormalized.isFinite()) denormalized else 0f
                        }

                        fun musicValue(vocals: Float, channel: Int, frame: Int): Float {
                            if (!model.musicFromMixMinusVocals) {
                                return mixValue(model.sources.music, channel, frame)
                            }
                            val original = chunkBufferFloat[frame * channels + channel]
                            val complement = original - vocals
                            return if (complement.isFinite()) complement else 0f
                        }
                        
                        vocalsMergedFloat.clear()
                        musicMergedFloat.clear()
                        drumsMergedFloat?.clear()
                        bassMergedFloat?.clear()
                        otherMergedFloat?.clear()

                        for (f in 0 until framesToWrite) {
                            for (ch in 0 until channels) {
                                var v_val = mixValue(model.sources.vocals, ch, f)
                                var m_val = musicValue(v_val, ch, f)
                                var d_val = model.sources.drums?.let { mixValue(it, ch, f) } ?: 0f
                                var b_val = model.sources.bass?.let { mixValue(it, ch, f) } ?: 0f
                                var o_val = model.sources.other?.let { mixValue(it, ch, f) } ?: 0f
                                
                                // Crossfade với hai trọng số bổ sung có tổng bằng 1.
                                // Điều này tránh tăng tới khoảng +3 dB khi hai dự
                                // đoán ở vùng overlap có tương quan cao.
                                if (f < overlapSize && !isFirstChunk) {
                                    val weights = OverlapWindow.weights(f, model.chunking)
                                    v_val = v_val * weights.current + outVocalsOverlap[f * channels + ch] * weights.previous
                                    m_val = m_val * weights.current + outBeatOverlap[f * channels + ch] * weights.previous
                                    
                                    if (is4StemMode) {
                                        d_val = d_val * weights.current + outDrumsOverlap!![f * channels + ch] * weights.previous
                                        b_val = b_val * weights.current + outBassOverlap!![f * channels + ch] * weights.previous
                                        o_val = o_val * weights.current + outOtherOverlap!![f * channels + ch] * weights.previous
                                    }
                                }

                                vocalsMergedFloat.put(v_val)
                                musicMergedFloat.put(m_val)
                                
                                if (is4StemMode) {
                                    drumsMergedFloat!!.put(d_val)
                                    bassMergedFloat!!.put(b_val)
                                    otherMergedFloat!!.put(o_val)
                                }
                            }
                        }

                        vocalsOut.write(vocalsMerged.array(), 0, framesToWrite * bytesPerFrame)
                        musicOut.write(musicMerged.array(), 0, framesToWrite * bytesPerFrame)
                        if (is4StemMode) {
                            drumsOut!!.write(drumsMerged!!.array(), 0, framesToWrite * bytesPerFrame)
                            bassOut!!.write(bassMerged!!.array(), 0, framesToWrite * bytesPerFrame)
                            otherOut!!.write(otherMerged!!.array(), 0, framesToWrite * bytesPerFrame)
                        }
                        logInfo(
                            event = "chunk_output_written",
                            fields = mapOf(
                                "chunk_index" to chunkIndex,
                                "frames" to framesToWrite,
                                "full_read" to isFullRead,
                                "elapsed_ms" to SystemClock.elapsedRealtime() - chunkStartedAt,
                            ),
                        )
                        
                        // Lưu Overlap cho chunk kế tiếp
                        if (isFullRead) {
                            for (f in framesToWrite until chunkFrames) {
                                for (ch in 0 until channels) {
                                    val v_val = mixValue(model.sources.vocals, ch, f)
                                    val m_val = musicValue(v_val, ch, f)
                                    val d_val = model.sources.drums?.let { mixValue(it, ch, f) } ?: 0f
                                    val b_val = model.sources.bass?.let { mixValue(it, ch, f) } ?: 0f
                                    val o_val = model.sources.other?.let { mixValue(it, ch, f) } ?: 0f

                                    val overIdx = f - framesToWrite
                                    outVocalsOverlap[overIdx * channels + ch] = v_val
                                    outBeatOverlap[overIdx * channels + ch] = m_val
                                    
                                    if (is4StemMode) {
                                        outDrumsOverlap!![overIdx * channels + ch] = d_val
                                        outBassOverlap!![overIdx * channels + ch] = b_val
                                        outOtherOverlap!![overIdx * channels + ch] = o_val
                                    }
                                }
                            }
                        }
                    } finally {
                        runCatching { result?.close() }.onFailure { closeError ->
                            DiagnosticLogger.warn(
                                component = TAG,
                                event = "onnx_result_close_failed",
                                sessionId = taskId,
                                fields = mapOf("chunk_index" to chunkIndex),
                                error = closeError,
                            )
                        }
                        runCatching { inputTensor.close() }.onFailure { closeError ->
                            DiagnosticLogger.warn(
                                component = TAG,
                                event = "onnx_tensor_close_failed",
                                sessionId = taskId,
                                fields = mapOf("chunk_index" to chunkIndex),
                                error = closeError,
                            )
                        }
                    }

                    processedFrames += actualFramesRead
                    val progressRatio = if (totalFrames > 0) processedFrames.toFloat() / totalFrames.toFloat() else 1.0f
                    val progress = 0.12f + 0.78f * progressRatio
                    emit(SeparationState.Progress(progress.coerceAtMost(0.88f)))
                    
                    isFirstChunk = false
                    chunkIndex++
                    
                    if (!isFullRead) {
                        logInfo(
                            event = "inference_eof",
                            fields = mapOf("chunks" to chunkIndex, "processed_frames" to processedFrames),
                        )
                        break
                    }
                }
                } catch (error: Throwable) {
                    streamFailure = error
                    throw error
                } finally {
                    val closeErrors = mutableListOf<Throwable>()
                    listOf(
                        "input" to inputStream,
                        "vocals" to vocalsOut,
                        "music" to musicOut,
                        "drums" to drumsOut,
                        "bass" to bassOut,
                        "other" to otherOut,
                    ).forEach { (name, resource) ->
                        runCatching { resource?.close() }.onFailure { closeError ->
                            closeErrors += closeError
                            DiagnosticLogger.warn(
                                component = TAG,
                                event = "pcm_stream_close_failed",
                                sessionId = taskId,
                                fields = mapOf("stream" to name),
                                error = closeError,
                            )
                        }
                    }
                    closeErrors.firstOrNull()?.let { firstCloseError ->
                        if (streamFailure != null) {
                            closeErrors.forEach(streamFailure!!::addSuppressed)
                        } else {
                            throw IOException("Không thể chốt dữ liệu PCM đầu ra", firstCloseError).also { wrapped ->
                                closeErrors.drop(1).forEach(wrapped::addSuppressed)
                            }
                        }
                    }
                }

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                logError("Lỗi khi tách: ${e.message}", e)
                throw Exception("Lỗi khi xử lý mô hình AI: ${e.message ?: "không xác định"}", e)
            } finally {
                activeRunOptions = null
                listOf<Pair<String, () -> Unit>>(
                    "run_options" to { runOptions.close() },
                    "session" to { session.close() },
                    "session_options" to { sessionOptions.close() },
                ).forEach { (resource, close) ->
                    runCatching(close).onFailure { closeError ->
                        DiagnosticLogger.warn(
                            component = TAG,
                            event = "onnx_resource_close_failed",
                            sessionId = taskId,
                            fields = mapOf("resource" to resource),
                            error = closeError,
                        )
                    }
                }
            }

            checkpoint("encoding", 0.90f)
            emit(SeparationState.Progress(0.9f)) // Encoding 

            // 4. Encode raw PCM back to selected format
            val ext = SettingsManager.getAudioFormatExt(context)
            val encodingArgs = SettingsManager.getAudioEncodingArgs(context)
            val trimArgs = if (boundaryPaddingFrames > 0) {
                val endSample = boundaryPaddingFrames.toLong() + originalFrames
                "-af \"atrim=start_sample=$boundaryPaddingFrames:end_sample=$endSample,asetpts=N/SR/TB\""
            } else {
                ""
            }

            val outVocals = FileExportManager.resultFile(context, "vocals", ext).also(createdOutputs::add)
            val outMusic = FileExportManager.resultFile(context, "music", ext).also(createdOutputs::add)
            var outDrums: File? = null
            var outBass: File? = null
            var outOther: File? = null

            logInfo(
                event = "encoding_start",
                fields = mapOf("format" to ext, "stem_count" to model.mode.stemCount),
            )
            val encVocalCmd = "-y -f f32le -ac $channels -ar $sampleRate -i \"${tempRawVocals.absolutePath}\" $trimArgs $encodingArgs \"${outVocals.absolutePath}\""
            val encMusicCmd = "-y -f f32le -ac $channels -ar $sampleRate -i \"${tempRawMusic.absolutePath}\" $trimArgs $encodingArgs \"${outMusic.absolutePath}\""

            val res1 = executeFfmpeg(encVocalCmd, phase = "encode_vocals")
            val res2 = executeFfmpeg(encMusicCmd, phase = "encode_music")

            if (!ReturnCode.isSuccess(res1.returnCode) || !ReturnCode.isSuccess(res2.returnCode)) {
                throw Exception("Không thể mã hóa tệp stem đầu ra")
            }
            
            if (is4StemMode) {
                val drumsTarget = FileExportManager.resultFile(context, "drums", ext)
                val bassTarget = FileExportManager.resultFile(context, "bass", ext)
                val otherTarget = FileExportManager.resultFile(context, "other", ext)
                outDrums = drumsTarget.also(createdOutputs::add)
                outBass = bassTarget.also(createdOutputs::add)
                outOther = otherTarget.also(createdOutputs::add)

                val encDrumsCmd = "-y -f f32le -ac $channels -ar $sampleRate -i \"${tempRawDrums.absolutePath}\" $trimArgs $encodingArgs \"${drumsTarget.absolutePath}\""
                val encBassCmd = "-y -f f32le -ac $channels -ar $sampleRate -i \"${tempRawBass.absolutePath}\" $trimArgs $encodingArgs \"${bassTarget.absolutePath}\""
                val encOtherCmd = "-y -f f32le -ac $channels -ar $sampleRate -i \"${tempRawOther.absolutePath}\" $trimArgs $encodingArgs \"${otherTarget.absolutePath}\""
                
                val resD = executeFfmpeg(encDrumsCmd, phase = "encode_drums")
                val resB = executeFfmpeg(encBassCmd, phase = "encode_bass")
                val resO = executeFfmpeg(encOtherCmd, phase = "encode_other")
                
                if (!ReturnCode.isSuccess(resD.returnCode) || !ReturnCode.isSuccess(resB.returnCode) || !ReturnCode.isSuccess(resO.returnCode)) {
                    throw Exception("Lỗi khi xuất file $ext (4 stems)")
                }
            }
            
            require(createdOutputs.all { it.isFile && it.length() > 0L }) { "Một hoặc nhiều stem đầu ra bị rỗng" }
            outputsCommitted = true
            logInfo(
                event = "pipeline_success",
                fields = mapOf(
                    "model_id" to model.id,
                    "source_id" to sourceId,
                    "output_count" to createdOutputs.size,
                    "output_bytes" to createdOutputs.sumOf(File::length),
                    "format" to ext,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - pipelineStartedAt,
                ),
            )
            emit(SeparationState.Progress(1.0f))
            emit(SeparationState.Success(outVocals, outMusic, outDrums, outBass, outOther))

        } catch (cancelled: CancellationException) {
            logInfo(
                event = "pipeline_cancelled",
                fields = mapOf(
                    "model_id" to model.id,
                    "source_id" to sourceId,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - pipelineStartedAt,
                ),
            )
            throw cancelled
        } catch (error: Throwable) {
            DiagnosticLogger.error(
                component = TAG,
                event = "pipeline_failed",
                sessionId = taskId,
                message = error.message,
                fields = mapOf(
                    "model_id" to model.id,
                    "source_id" to sourceId,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - pipelineStartedAt,
                    "out_of_memory" to (error is OutOfMemoryError),
                ),
                error = error,
            )
            throw error
        } finally {
            activeFfmpegSessionId.set(-1L)
            activeRunOptions = null
            var outputDeleteFailures = 0
            if (!outputsCommitted) {
                createdOutputs.forEach { if (it.exists() && !it.delete()) outputDeleteFailures++ }
            }
            // 5. Cleanup
            var tempDeleteFailures = 0
            listOf(
                tempRawMix,
                tempRawInference,
                tempRawVocals,
                tempRawMusic,
                tempRawDrums,
                tempRawBass,
                tempRawOther,
            ).forEach { if (it.exists() && !it.delete()) tempDeleteFailures++ }
            val workDirRemoved = !workDir.exists() || workDir.deleteRecursively()
            logInfo(
                event = "pipeline_cleanup",
                fields = mapOf(
                    "outputs_committed" to outputsCommitted,
                    "output_delete_failures" to outputDeleteFailures,
                    "temp_delete_failures" to tempDeleteFailures,
                    "work_dir_removed" to workDirRemoved,
                ),
            )
        }
    }.flowOn(Dispatchers.IO)
}
