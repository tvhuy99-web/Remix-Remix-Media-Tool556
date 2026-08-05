package com.aistudio.mediatool.core.ml

import android.app.ActivityManager
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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
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

/** Streaming two-stem pipeline for MDX-family learned spectrogram cores. */
class MdxAudioSeparator(
    private val context: Context,
    private val modelFile: File,
    private val model: StemModelDescriptor,
    private val taskId: String,
) {
    private val activeFfmpegSessionId = AtomicLong(-1L)
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var activeEngine: MdxCoreEngine? = null
    private val sampleRate = model.sampleRate
    private val channels = model.channels
    private val bytesPerFrame = channels * Float.SIZE_BYTES
    private val contract = requireNotNull(model.mdx)

    init {
        require(
            model.backend == StemInferenceBackend.MDX_LITERT ||
                model.backend == StemInferenceBackend.MDX_ONNX,
        )
        require(model.mode == StemMode.TWO_STEM)
    }

    fun cancel() {
        cancelRequested.set(true)
        DiagnosticLogger.warn(
            component = TAG,
            event = "cancel_requested",
            sessionId = taskId,
            fields = mapOf("model_id" to model.id, "backend" to model.backend),
        )
        activeEngine?.cancel()
        val sessionId = activeFfmpegSessionId.getAndSet(-1L)
        if (sessionId >= 0L) FFmpegKit.cancel(sessionId)
    }

    fun separate(inputUri: Uri): Flow<SeparationState> = flow {
        cancelRequested.set(false)
        val pipelineStartedAt = SystemClock.elapsedRealtime()
        val sourceId = DiagnosticRedactor.stableId(inputUri.toString())
        val tempPrefix = "mdx_${taskId.replace('-', '_')}"
        val tempRawMix = File(context.cacheDir, "${tempPrefix}_mix.f32")
        val tempRawInference = File(context.cacheDir, "${tempPrefix}_reflect.f32")
        val tempRawVocals = File(context.cacheDir, "${tempPrefix}_vocals.f32")
        val tempRawMusic = File(context.cacheDir, "${tempPrefix}_music.f32")
        val temporaryFiles = listOf(tempRawMix, tempRawInference, tempRawVocals, tempRawMusic)
        val createdOutputs = mutableListOf<File>()
        var outputsCommitted = false
        val denoiseEnabled = contract.supportsPolarityDenoise &&
            SettingsManager.isStemMdxDenoiseEnabled(context)
        val isMdx23c = model.id == StemModelRegistry.MDX23C_VOCAL_PERSONAL_ID
        val mdx23cExecutionMode = if (isMdx23c) {
            Mdx23cExecutionMode.fromSettingsIndex(
                SettingsManager.getStemMdx23cAccelerationIndex(context),
            )
        } else null
        val mdx23cOverlapMode = if (isMdx23c) {
            Mdx23cOverlapMode.fromSettingsIndex(
                SettingsManager.getStemMdx23cOverlapIndex(context),
            ).requireCompatible(contract)
        } else null
        val configuredOnnxAcceleration = mdx23cExecutionMode?.acceleration
            ?: OnnxAcceleration.fromSettingsIndex(SettingsManager.getHardwareAccelIndex(context))
        val runtimeStrideFrames = mdx23cOverlapMode?.strideFrames ?: contract.strideFrames
        val runtimeOverlapFrames = contract.generatedFrames - runtimeStrideFrames
        var seamFrames: List<Long> = emptyList()

        logInfo(
            event = "mdx_pipeline_start",
            fields = mapOf(
                "model_id" to model.id,
                "source_id" to sourceId,
                "sample_rate" to sampleRate,
                "channels" to channels,
                "n_fft" to contract.nFft,
                "hop_length" to contract.hopLength,
                "frequency_bins" to contract.frequencyBins,
                "time_frames" to contract.timeFrames,
                "chunk_frames" to contract.chunkFrames,
                "generated_frames" to contract.generatedFrames,
                "contribution_trim_frames" to contract.contributionTrimFrames,
                "stride_frames" to runtimeStrideFrames,
                "overlap_frames" to runtimeOverlapFrames,
                "overlap_mode" to (mdx23cOverlapMode?.name ?: "MODEL_DEFAULT"),
                "overlap_percent" to mdx23cOverlapMode?.overlapPercent,
                "requested_acceleration" to configuredOnnxAcceleration,
                "window_fade_frames" to contract.windowFadeFrames,
                "reflect_boundary_frames" to contract.reflectBoundaryFrames,
                "denoise_supported" to contract.supportsPolarityDenoise,
                "denoise_enabled" to denoiseEnabled,
            ),
        )

        try {
            checkpoint("mdx_decode_input", 0.02f)
            emit(SeparationState.Progress(0.02f))
            val inputPath = FFmpegKitConfig.getSafParameterForRead(context, inputUri)
            val decodeCommand =
                "-y -i \"$inputPath\" -f f32le -ac $channels -ar $sampleRate \"${tempRawMix.absolutePath}\""
            val decode = executeFfmpeg(decodeCommand, "mdx_decode_input")
            require(ReturnCode.isSuccess(decode.returnCode)) { "Không thể giải mã tệp đầu vào" }
            require(tempRawMix.length() >= bytesPerFrame && tempRawMix.length() % bytesPerFrame == 0L) {
                "Tệp đầu vào không chứa PCM stereo hợp lệ"
            }
            val totalFrames = tempRawMix.length() / bytesPerFrame
            logInfo(
                event = "mdx_decode_complete",
                fields = mapOf("frames" to totalFrames, "pcm_bytes" to tempRawMix.length()),
            )

            val requestedBoundaryFrames = contract.reflectBoundaryFrames
            val boundaryPaddingFrames = requestedBoundaryFrames.takeIf {
                it > 0 && totalFrames > it.toLong() * 2L
            } ?: 0
            val inferencePcm = if (boundaryPaddingFrames > 0) {
                val paddingStartedAt = SystemClock.elapsedRealtime()
                createReflectPaddedPcm(
                    source = tempRawMix,
                    destination = tempRawInference,
                    sourceFrames = totalFrames,
                    paddingFrames = boundaryPaddingFrames,
                )
                logInfo(
                    event = "mdx_boundary_padding_complete",
                    fields = mapOf(
                        "padding_frames_per_side" to boundaryPaddingFrames,
                        "padded_bytes" to tempRawInference.length(),
                        "elapsed_ms" to SystemClock.elapsedRealtime() - paddingStartedAt,
                    ),
                )
                tempRawInference
            } else {
                logInfo(
                    event = "mdx_boundary_padding_skipped",
                    fields = mapOf(
                        "requested_frames" to requestedBoundaryFrames,
                        "source_frames" to totalFrames,
                    ),
                )
                tempRawMix
            }
            val inferenceFrames = inferencePcm.length() / bytesPerFrame
            emit(SeparationState.Progress(0.08f))
            checkpoint("mdx_engine_opening", 0.08f)

            val engineStartedAt = SystemClock.elapsedRealtime()
            val openResult = when (model.backend) {
                StemInferenceBackend.MDX_LITERT -> MdxLiteRtEngine.open(
                    modelFile = modelFile,
                    tensorElements = contract.tensorElements,
                    cpuThreads = SettingsManager.getNumThreads(context),
                    gpuCacheDirectory = File(context.codeCacheDir, "litert_gpu_cache/${model.id}"),
                ) { attemptedBackend, error ->
                    logBackendAttemptFailed(
                        attemptedBackend = attemptedBackend.name,
                        nextBackend = if (attemptedBackend == MdxExecutionBackend.LITERT_GPU_FP16) {
                            MdxExecutionBackend.LITERT_CPU_XNNPACK.name
                        } else null,
                        error = error,
                    )
                }

                StemInferenceBackend.MDX_ONNX -> MdxOnnxEngine.open(
                    modelFile = modelFile,
                    model = model,
                    cpuThreads = SettingsManager.getNumThreads(context),
                    configuredAcceleration = configuredOnnxAcceleration,
                ) { attemptedBackend, error ->
                    logBackendAttemptFailed(
                        attemptedBackend = "ONNX_${attemptedBackend.name}",
                        nextBackend = if (attemptedBackend != OnnxAcceleration.CPU) "ONNX_CPU" else null,
                        error = error,
                    )
                }

                StemInferenceBackend.WAVEFORM_ONNX -> error("Sai backend cho pipeline MDX")
            }
            activeEngine = openResult.engine
            openResult.engine.use { engine ->
                logInfo(
                    event = "mdx_engine_opened",
                    fields = mapOf(
                        "model_id" to model.id,
                        "effective_backend" to engine.backendLabel,
                        "requested_acceleration" to configuredOnnxAcceleration,
                        "overlap_mode" to (mdx23cOverlapMode?.name ?: "MODEL_DEFAULT"),
                        "stride_frames" to runtimeStrideFrames,
                        "cpu_threads" to SettingsManager.getNumThreads(context),
                        "failed_attempts" to openResult.failedAttempts.joinToString(" | "),
                        "elapsed_ms" to SystemClock.elapsedRealtime() - engineStartedAt,
                    ) + memoryFields(),
                )
                checkpoint("mdx_engine_opened", 0.10f)

                val generatedFrames = contract.generatedFrames
                val strideFrames = runtimeStrideFrames
                val chunksLong = if (inferenceFrames <= generatedFrames.toLong()) {
                    1L
                } else {
                    (inferenceFrames - generatedFrames + strideFrames - 1L) / strideFrames + 1L
                }
                require(chunksLong in 1..Int.MAX_VALUE.toLong()) { "Tệp quá dài cho pipeline MDX" }
                val chunkCount = chunksLong.toInt()
                seamFrames = (1 until chunkCount)
                    .map { index -> index.toLong() * strideFrames - boundaryPaddingFrames }
                    .filter { frame -> frame in 1 until totalFrames }
                val window = MdxDsp.buildCrossfadeWindow(
                    generatedFrames,
                    contract.windowFadeFrames,
                )
                val dsp = MdxDsp(contract)
                val chunkLeft = FloatArray(contract.chunkFrames)
                val chunkRight = FloatArray(contract.chunkFrames)
                val tensorSlot = MdxTensorSlot(contract.tensorElements)
                val vocalsLeft = FloatArray(contract.chunkFrames)
                val vocalsRight = FloatArray(contract.chunkFrames)
                val pcmScratch = ByteArray(contract.chunkFrames * bytesPerFrame)
                val tensorBytes = contract.tensorElements.toLong() * Float.SIZE_BYTES

                logInfo(
                    event = "mdx_buffers_ready",
                    fields = mapOf(
                        "chunk_count" to chunkCount,
                        "inference_frames" to inferenceFrames,
                        "boundary_padding_frames" to boundaryPaddingFrames,
                        "tensor_elements" to contract.tensorElements,
                        "tensor_bytes" to tensorBytes,
                        "tensor_handoff" to "output_reused_as_next_input",
                    ) + memoryFields(),
                )

                RandomAccessFile(inferencePcm, "r").use { mixInput ->
                    val vocalStream = DataOutputStream(
                        BufferedOutputStream(FileOutputStream(tempRawVocals), 1024 * 1024),
                    )
                    MdxOverlapAddWriter(
                        output = vocalStream,
                        totalFrames = totalFrames,
                        generatedFrames = generatedFrames,
                        strideFrames = strideFrames,
                        window = window,
                        compensation = contract.compensation,
                        discardLeadingFrames = boundaryPaddingFrames.toLong(),
                    ).use { writer ->
                        for (chunkIndex in 0 until chunkCount) {
                            coroutineContext.ensureActive()
                            check(!cancelRequested.get()) { "Đã hủy xử lý" }
                            val chunkStartedAt = SystemClock.elapsedRealtime()
                            val outputStart = chunkIndex.toLong() * strideFrames
                            val inputStart = outputStart - contract.contributionTrimFrames
                            readChunk(
                                input = mixInput,
                                totalFrames = inferenceFrames,
                                startFrame = inputStart,
                                left = chunkLeft,
                                right = chunkRight,
                                scratch = pcmScratch,
                            )
                            val readElapsed = SystemClock.elapsedRealtime() - chunkStartedAt
                            val stftStartedAt = SystemClock.elapsedRealtime()
                            dsp.forward(chunkLeft, chunkRight, tensorSlot.borrow())
                            val stftElapsed = SystemClock.elapsedRealtime() - stftStartedAt
                            val inferenceStartedAt = SystemClock.elapsedRealtime()

                            val modelInput = tensorSlot.borrow()
                            engine.writeInput(modelInput)
                            val outputTensor = if (denoiseEnabled) {
                                engine.execute()
                                val positiveOutput = engine.readOutput()
                                coroutineContext.ensureActive()
                                check(!cancelRequested.get()) { "Đã hủy xử lý" }
                                for (index in modelInput.indices) modelInput[index] = -modelInput[index]
                                engine.writeInput(modelInput)
                                tensorSlot.release()
                                engine.execute()
                                val negativeOutput = engine.readOutput()
                                MdxDenoise.combineInPlace(positiveOutput, negativeOutput)
                            } else {
                                tensorSlot.release()
                                engine.execute()
                                engine.readOutput()
                            }
                            val inferenceElapsed = SystemClock.elapsedRealtime() - inferenceStartedAt
                            coroutineContext.ensureActive()
                            check(!cancelRequested.get()) { "Đã hủy xử lý" }
                            val istftStartedAt = SystemClock.elapsedRealtime()
                            dsp.inverse(outputTensor, vocalsLeft, vocalsRight)
                            tensorSlot.accept(outputTensor)
                            val istftElapsed = SystemClock.elapsedRealtime() - istftStartedAt
                            val writeStartedAt = SystemClock.elapsedRealtime()
                            writer.append(
                                leftChunk = vocalsLeft,
                                rightChunk = vocalsRight,
                                centralOffset = contract.contributionTrimFrames,
                            )
                            val writeElapsed = SystemClock.elapsedRealtime() - writeStartedAt
                            val progress = 0.10f + 0.70f * ((chunkIndex + 1f) / chunkCount.toFloat())
                            emit(SeparationState.Progress(progress.coerceIn(0.10f, 0.80f)))
                            checkpoint("mdx_chunk_${chunkIndex}_complete", progress)
                            logInfo(
                                event = "mdx_chunk_complete",
                                fields = mapOf(
                                    "chunk_index" to chunkIndex,
                                    "chunk_count" to chunkCount,
                                    "output_start_frame" to outputStart,
                                    "read_ms" to readElapsed,
                                    "stft_ms" to stftElapsed,
                                    "inference_ms" to inferenceElapsed,
                                    "istft_ms" to istftElapsed,
                                    "write_ms" to writeElapsed,
                                    "elapsed_ms" to SystemClock.elapsedRealtime() - chunkStartedAt,
                                    "effective_backend" to engine.backendLabel,
                                    "inference_passes" to if (denoiseEnabled) 2 else 1,
                                ) + memoryFields(),
                            )
                        }
                    }
                }
            }

            require(tempRawVocals.length() == totalFrames * bytesPerFrame) {
                "Đầu ra vocals PCM sai độ dài"
            }
            checkpoint("mdx_residual", 0.82f)
            emit(SeparationState.Progress(0.82f))
            val residualStartedAt = SystemClock.elapsedRealtime()
            StemPcmToolkit.createResidual(
                mixFile = tempRawMix,
                vocalsFile = tempRawVocals,
                destination = tempRawMusic,
                channels = channels,
                cancellationCheck = {
                    if (cancelRequested.get()) throw CancellationException("Đã hủy xử lý")
                },
            )
            require(tempRawMusic.length() == totalFrames * bytesPerFrame) {
                "Đầu ra instrumental PCM sai độ dài"
            }
            logInfo(
                event = "mdx_residual_complete",
                fields = mapOf("elapsed_ms" to SystemClock.elapsedRealtime() - residualStartedAt),
            )

            val qualityStartedAt = SystemClock.elapsedRealtime()
            val qualityReport = StemPcmToolkit.analyze(
                referenceMix = tempRawMix,
                stemFiles = linkedMapOf(
                    "vocals" to tempRawVocals,
                    "music" to tempRawMusic,
                ),
                reconstructionStemNames = setOf("vocals", "music"),
                channels = channels,
                seamFrames = seamFrames,
            )
            StemPcmToolkit.logDiagnostics(
                component = TAG,
                taskId = taskId,
                modelId = model.id,
                report = qualityReport,
                analysisElapsedMs = SystemClock.elapsedRealtime() - qualityStartedAt,
            )
            val outputFilterArguments = StemPcmToolkit.buildOutputFilterArguments(
                sharedGainDb = qualityReport.sharedGainDb,
            )

            checkpoint("mdx_encoding", 0.88f)
            emit(SeparationState.Progress(0.88f))
            val extension = SettingsManager.getAudioFormatExt(context)
            val encodingArguments = SettingsManager.getAudioEncodingArgs(context)
            val vocalsOutput = FileExportManager.resultFile(context, "vocals", extension).also(createdOutputs::add)
            val musicOutput = FileExportManager.resultFile(context, "music", extension).also(createdOutputs::add)
            val vocalsCommand =
                "-y -f f32le -ac $channels -ar $sampleRate -i \"${tempRawVocals.absolutePath}\" " +
                    "$outputFilterArguments $encodingArguments \"${vocalsOutput.absolutePath}\""
            val musicCommand =
                "-y -f f32le -ac $channels -ar $sampleRate -i \"${tempRawMusic.absolutePath}\" " +
                    "$outputFilterArguments $encodingArguments \"${musicOutput.absolutePath}\""
            val vocalsEncode = executeFfmpeg(vocalsCommand, "mdx_encode_vocals")
            val musicEncode = executeFfmpeg(musicCommand, "mdx_encode_music")
            require(ReturnCode.isSuccess(vocalsEncode.returnCode) && ReturnCode.isSuccess(musicEncode.returnCode)) {
                "Không thể mã hóa stem đầu ra"
            }
            require(createdOutputs.all { it.isFile && it.length() > 0L }) { "Một stem đầu ra bị rỗng" }
            outputsCommitted = true
            logInfo(
                event = "mdx_pipeline_success",
                fields = mapOf(
                    "model_id" to model.id,
                    "source_id" to sourceId,
                    "output_count" to createdOutputs.size,
                    "output_bytes" to createdOutputs.sumOf(File::length),
                    "format" to extension,
                    "denoise_enabled" to denoiseEnabled,
                    "shared_output_gain_db" to qualityReport.sharedGainDb,
                    "elapsed_ms" to SystemClock.elapsedRealtime() - pipelineStartedAt,
                ) + memoryFields(),
            )
            emit(SeparationState.Progress(1f))
            emit(SeparationState.Success(vocalsOutput, musicOutput))
        } catch (cancelled: CancellationException) {
            logInfo(
                event = "mdx_pipeline_cancelled",
                fields = mapOf("elapsed_ms" to SystemClock.elapsedRealtime() - pipelineStartedAt),
            )
            throw cancelled
        } catch (error: Throwable) {
            logError("Lỗi MDX: ${error.message ?: "không xác định"}", error)
            throw if (error is OutOfMemoryError) error else Exception(
                "Lỗi khi xử lý model MDX: ${error.message ?: "không xác định"}",
                error,
            )
        } finally {
            activeEngine = null
            temporaryFiles.forEach { file -> runCatching { file.delete() } }
            if (!outputsCommitted) createdOutputs.forEach { file -> runCatching { file.delete() } }
        }
    }.flowOn(Dispatchers.IO)

    private fun logBackendAttemptFailed(
        attemptedBackend: String,
        nextBackend: String?,
        error: Throwable,
    ) {
        DiagnosticLogger.warn(
            component = TAG,
            event = "mdx_backend_attempt_failed",
            sessionId = taskId,
            message = error.message,
            fields = mapOf(
                "model_id" to model.id,
                "attempted_backend" to attemptedBackend,
                "next_backend" to nextBackend,
            ),
            error = error,
        )
    }

    private fun createReflectPaddedPcm(
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
            input.seek(bytesPerFrame.toLong())
            input.readFully(prefix)
            input.seek((sourceFrames - paddingFrames.toLong() - 1L) * bytesPerFrame.toLong())
            input.readFully(suffix)
        }

        try {
            FileOutputStream(destination).use { fileOutput ->
                BufferedOutputStream(fileOutput, 1024 * 1024).use { output ->
                    output.write(reverseFrames(prefix))
                    FileInputStream(source).buffered(1024 * 1024).use { input ->
                        input.copyTo(output, 1024 * 1024)
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

    private fun readChunk(
        input: RandomAccessFile,
        totalFrames: Long,
        startFrame: Long,
        left: FloatArray,
        right: FloatArray,
        scratch: ByteArray,
    ) {
        Arrays.fill(left, 0f)
        Arrays.fill(right, 0f)
        val endFrame = startFrame + contract.chunkFrames
        val validStart = maxOf(0L, startFrame)
        val validEnd = minOf(totalFrames, endFrame)
        if (validEnd <= validStart) return
        val frameCount = (validEnd - validStart).toInt()
        val destinationOffset = (validStart - startFrame).toInt()
        val byteCount = frameCount * bytesPerFrame
        input.seek(validStart * bytesPerFrame)
        input.readFully(scratch, 0, byteCount)
        val floats = ByteBuffer.wrap(scratch, 0, byteCount)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
        for (i in 0 until frameCount) {
            left[destinationOffset + i] = floats.get(i * channels)
            right[destinationOffset + i] = floats.get(i * channels + 1)
        }
    }

    private fun createResidualInstrumental(
        mixFile: File,
        vocalsFile: File,
        destination: File,
        totalFrames: Long,
    ) {
        val blockFrames = 8192
        val mixBytes = ByteArray(blockFrames * bytesPerFrame)
        val vocalBytes = ByteArray(blockFrames * bytesPerFrame)
        val outputBytes = ByteBuffer.allocate(blockFrames * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        DataInputStream(BufferedInputStream(FileInputStream(mixFile), 512 * 1024)).use { mixInput ->
            DataInputStream(BufferedInputStream(FileInputStream(vocalsFile), 512 * 1024)).use { vocalInput ->
                DataOutputStream(BufferedOutputStream(FileOutputStream(destination), 512 * 1024)).use { output ->
                    var remaining = totalFrames
                    while (remaining > 0L) {
                        if (cancelRequested.get()) throw CancellationException("Đã hủy xử lý")
                        val frames = minOf(blockFrames.toLong(), remaining).toInt()
                        val bytes = frames * bytesPerFrame
                        mixInput.readFully(mixBytes, 0, bytes)
                        vocalInput.readFully(vocalBytes, 0, bytes)
                        val mixFloats = ByteBuffer.wrap(mixBytes, 0, bytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer()
                        val vocalFloats = ByteBuffer.wrap(vocalBytes, 0, bytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer()
                        outputBytes.clear()
                        for (i in 0 until frames * channels) {
                            val value = mixFloats.get(i) - vocalFloats.get(i)
                            outputBytes.putFloat(if (value.isFinite()) value else 0f)
                        }
                        output.write(outputBytes.array(), 0, bytes)
                        remaining -= frames
                    }
                }
            }
        }
    }

    private suspend fun executeFfmpeg(command: String, phase: String): FFmpegSession =
        suspendCancellableCoroutine { continuation ->
            val terminal = AtomicBoolean(false)
            val sessionId = AtomicLong(-1L)
            val startedAt = SystemClock.elapsedRealtime()
            val commandId = DiagnosticRedactor.stableId(command)
            logInfo(
                event = "ffmpeg_start",
                fields = mapOf("phase" to phase, "command_id" to commandId),
            )
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
                            val success = ReturnCode.isSuccess(completed.returnCode)
                            val fields = mutableMapOf<String, Any?>(
                                "phase" to phase,
                                "command_id" to commandId,
                                "return_code" to completed.returnCode.toString(),
                                "elapsed_ms" to SystemClock.elapsedRealtime() - startedAt,
                            )
                            if (success) {
                                logInfo("ffmpeg_success", fields)
                            } else {
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

    private fun memoryFields(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val memoryInfo = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memoryInfo)
        return mapOf(
            "java_heap_used_bytes" to runtime.totalMemory() - runtime.freeMemory(),
            "java_heap_max_bytes" to runtime.maxMemory(),
            "native_heap_allocated_bytes" to Debug.getNativeHeapAllocatedSize(),
            "process_pss_kb" to Debug.getPss().toLong(),
            "system_available_ram_bytes" to memoryInfo.availMem,
            "system_low_memory" to memoryInfo.lowMemory,
        )
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

    private fun logInfo(event: String, fields: Map<String, Any?> = emptyMap()) {
        DiagnosticLogger.info(TAG, event, taskId, fields = fields)
    }

    private fun logError(message: String, error: Throwable) {
        DiagnosticLogger.error(
            component = TAG,
            event = "mdx_pipeline_error",
            sessionId = taskId,
            message = message,
            fields = memoryFields(),
            error = error,
        )
    }

    private companion object {
        const val TAG = "MdxAudioSeparator"
    }
}
