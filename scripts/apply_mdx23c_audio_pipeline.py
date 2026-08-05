#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative: str, old: str, new: str) -> None:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{relative}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


audio = "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt"
replace_once(
    audio,
    "/** Streaming two-stem pipeline for UVR MDX-Net models whose learned core runs in LiteRT. */",
    "/** Streaming two-stem pipeline for MDX-family learned spectrogram cores. */",
)
replace_once(
    audio,
    """    private val activeFfmpegSessionId = AtomicLong(-1L)\n    private val cancelRequested = AtomicBoolean(false)""",
    """    private val activeFfmpegSessionId = AtomicLong(-1L)\n    private val cancelRequested = AtomicBoolean(false)\n    @Volatile private var activeEngine: MdxCoreEngine? = null""",
)
replace_once(
    audio,
    """    init {\n        require(model.backend == StemInferenceBackend.MDX_LITERT)\n        require(model.mode == StemMode.TWO_STEM)\n    }""",
    """    init {\n        require(\n            model.backend == StemInferenceBackend.MDX_LITERT ||\n                model.backend == StemInferenceBackend.MDX_ONNX,\n        )\n        require(model.mode == StemMode.TWO_STEM)\n    }""",
)
replace_once(
    audio,
    """        val sessionId = activeFfmpegSessionId.getAndSet(-1L)\n        if (sessionId >= 0L) FFmpegKit.cancel(sessionId)""",
    """        activeEngine?.cancel()\n        val sessionId = activeFfmpegSessionId.getAndSet(-1L)\n        if (sessionId >= 0L) FFmpegKit.cancel(sessionId)""",
)
replace_once(
    audio,
    """        val tempRawMix = File(context.cacheDir, \"${tempPrefix}_mix.f32\")\n        val tempRawVocals = File(context.cacheDir, \"${tempPrefix}_vocals.f32\")\n        val tempRawMusic = File(context.cacheDir, \"${tempPrefix}_music.f32\")\n        val temporaryFiles = listOf(tempRawMix, tempRawVocals, tempRawMusic)\n        val createdOutputs = mutableListOf<File>()\n        var outputsCommitted = false\n        val denoiseEnabled = SettingsManager.isStemMdxDenoiseEnabled(context)""",
    """        val tempRawMix = File(context.cacheDir, \"${tempPrefix}_mix.f32\")\n        val tempRawInference = File(context.cacheDir, \"${tempPrefix}_reflect.f32\")\n        val tempRawVocals = File(context.cacheDir, \"${tempPrefix}_vocals.f32\")\n        val tempRawMusic = File(context.cacheDir, \"${tempPrefix}_music.f32\")\n        val temporaryFiles = listOf(tempRawMix, tempRawInference, tempRawVocals, tempRawMusic)\n        val createdOutputs = mutableListOf<File>()\n        var outputsCommitted = false\n        val denoiseEnabled = contract.supportsPolarityDenoise &&\n            SettingsManager.isStemMdxDenoiseEnabled(context)""",
)
replace_once(
    audio,
    """                \"generated_frames\" to contract.generatedFrames,\n                \"stride_frames\" to contract.strideFrames,\n                \"overlap_frames\" to contract.overlapFrames,\n                \"denoise_enabled\" to denoiseEnabled,""",
    """                \"generated_frames\" to contract.generatedFrames,\n                \"contribution_trim_frames\" to contract.contributionTrimFrames,\n                \"stride_frames\" to contract.strideFrames,\n                \"overlap_frames\" to contract.overlapFrames,\n                \"window_fade_frames\" to contract.windowFadeFrames,\n                \"reflect_boundary_frames\" to contract.reflectBoundaryFrames,\n                \"denoise_supported\" to contract.supportsPolarityDenoise,\n                \"denoise_enabled\" to denoiseEnabled,""",
)
replace_once(
    audio,
    """            logInfo(\n                event = \"mdx_decode_complete\",\n                fields = mapOf(\"frames\" to totalFrames, \"pcm_bytes\" to tempRawMix.length()),\n            )\n            emit(SeparationState.Progress(0.08f))""",
    """            logInfo(\n                event = \"mdx_decode_complete\",\n                fields = mapOf(\"frames\" to totalFrames, \"pcm_bytes\" to tempRawMix.length()),\n            )\n\n            val requestedBoundaryFrames = contract.reflectBoundaryFrames\n            val boundaryPaddingFrames = requestedBoundaryFrames.takeIf {\n                it > 0 && totalFrames > it.toLong() * 2L\n            } ?: 0\n            val inferencePcm = if (boundaryPaddingFrames > 0) {\n                val paddingStartedAt = SystemClock.elapsedRealtime()\n                createReflectPaddedPcm(\n                    source = tempRawMix,\n                    destination = tempRawInference,\n                    sourceFrames = totalFrames,\n                    paddingFrames = boundaryPaddingFrames,\n                )\n                logInfo(\n                    event = \"mdx_boundary_padding_complete\",\n                    fields = mapOf(\n                        \"padding_frames_per_side\" to boundaryPaddingFrames,\n                        \"padded_bytes\" to tempRawInference.length(),\n                        \"elapsed_ms\" to SystemClock.elapsedRealtime() - paddingStartedAt,\n                    ),\n                )\n                tempRawInference\n            } else {\n                logInfo(\n                    event = \"mdx_boundary_padding_skipped\",\n                    fields = mapOf(\n                        \"requested_frames\" to requestedBoundaryFrames,\n                        \"source_frames\" to totalFrames,\n                    ),\n                )\n                tempRawMix\n            }\n            val inferenceFrames = inferencePcm.length() / bytesPerFrame\n            emit(SeparationState.Progress(0.08f))""",
)
replace_once(
    audio,
    """            val engineStartedAt = SystemClock.elapsedRealtime()\n            val openResult = MdxLiteRtEngine.open(\n                modelFile = modelFile,\n                tensorElements = contract.tensorElements,\n                cpuThreads = SettingsManager.getNumThreads(context),\n                gpuCacheDirectory = File(context.codeCacheDir, \"litert_gpu_cache/${model.id}\"),\n            ) { attemptedBackend, error ->\n                DiagnosticLogger.warn(\n                    component = TAG,\n                    event = \"mdx_backend_attempt_failed\",\n                    sessionId = taskId,\n                    message = error.message,\n                    fields = mapOf(\n                        \"model_id\" to model.id,\n                        \"attempted_backend\" to attemptedBackend,\n                        \"next_backend\" to if (attemptedBackend == MdxExecutionBackend.LITERT_GPU_FP16) {\n                            MdxExecutionBackend.LITERT_CPU_XNNPACK\n                        } else null,\n                    ),\n                    error = error,\n                )\n            }\n            openResult.engine.use { engine ->""",
    """            val engineStartedAt = SystemClock.elapsedRealtime()\n            val openResult = when (model.backend) {\n                StemInferenceBackend.MDX_LITERT -> MdxLiteRtEngine.open(\n                    modelFile = modelFile,\n                    tensorElements = contract.tensorElements,\n                    cpuThreads = SettingsManager.getNumThreads(context),\n                    gpuCacheDirectory = File(context.codeCacheDir, \"litert_gpu_cache/${model.id}\"),\n                ) { attemptedBackend, error ->\n                    logBackendAttemptFailed(\n                        attemptedBackend = attemptedBackend.name,\n                        nextBackend = if (attemptedBackend == MdxExecutionBackend.LITERT_GPU_FP16) {\n                            MdxExecutionBackend.LITERT_CPU_XNNPACK.name\n                        } else null,\n                        error = error,\n                    )\n                }\n\n                StemInferenceBackend.MDX_ONNX -> MdxOnnxEngine.open(\n                    modelFile = modelFile,\n                    model = model,\n                    cpuThreads = SettingsManager.getNumThreads(context),\n                    configuredAcceleration = OnnxAcceleration.fromSettingsIndex(\n                        SettingsManager.getHardwareAccelIndex(context),\n                    ),\n                ) { attemptedBackend, error ->\n                    logBackendAttemptFailed(\n                        attemptedBackend = \"ONNX_${attemptedBackend.name}\",\n                        nextBackend = if (attemptedBackend != OnnxAcceleration.CPU) \"ONNX_CPU\" else null,\n                        error = error,\n                    )\n                }\n\n                StemInferenceBackend.WAVEFORM_ONNX -> error(\"Sai backend cho pipeline MDX\")\n            }\n            activeEngine = openResult.engine\n            openResult.engine.use { engine ->""",
)
path = ROOT / audio
text = path.read_text(encoding="utf-8")
needle = '"effective_backend" to engine.backend,'
if text.count(needle) != 2:
    raise RuntimeError(f"{audio}: expected two backend log matches, found {text.count(needle)}")
path.write_text(text.replace(needle, '"effective_backend" to engine.backendLabel,'), encoding="utf-8")
replace_once(
    audio,
    """                val chunksLong = if (totalFrames <= generatedFrames.toLong()) {\n                    1L\n                } else {\n                    (totalFrames - generatedFrames + strideFrames - 1L) / strideFrames + 1L\n                }\n                require(chunksLong in 1..Int.MAX_VALUE.toLong()) { \"Tệp quá dài cho pipeline MDX\" }\n                val chunkCount = chunksLong.toInt()\n                seamFrames = (1 until chunkCount).map { index -> index.toLong() * strideFrames }\n                val window = MdxDsp.buildCrossfadeWindow(generatedFrames, contract.overlapFrames)""",
    """                val chunksLong = if (inferenceFrames <= generatedFrames.toLong()) {\n                    1L\n                } else {\n                    (inferenceFrames - generatedFrames + strideFrames - 1L) / strideFrames + 1L\n                }\n                require(chunksLong in 1..Int.MAX_VALUE.toLong()) { \"Tệp quá dài cho pipeline MDX\" }\n                val chunkCount = chunksLong.toInt()\n                seamFrames = (1 until chunkCount)\n                    .map { index -> index.toLong() * strideFrames - boundaryPaddingFrames }\n                    .filter { frame -> frame in 1 until totalFrames }\n                val window = MdxDsp.buildCrossfadeWindow(\n                    generatedFrames,\n                    contract.windowFadeFrames,\n                )""",
)
replace_once(audio, 'RandomAccessFile(tempRawMix, "r").use { mixInput ->', 'RandomAccessFile(inferencePcm, "r").use { mixInput ->')
replace_once(
    audio,
    """                        window = window,\n                        compensation = contract.compensation,\n                    ).use { writer ->""",
    """                        window = window,\n                        compensation = contract.compensation,\n                        discardLeadingFrames = boundaryPaddingFrames.toLong(),\n                    ).use { writer ->""",
)
replace_once(
    audio,
    """                            val inputStart = outputStart - contract.trimFrames\n                            readChunk(\n                                input = mixInput,\n                                totalFrames = totalFrames,""",
    """                            val inputStart = outputStart - contract.contributionTrimFrames\n                            readChunk(\n                                input = mixInput,\n                                totalFrames = inferenceFrames,""",
)
replace_once(audio, "centralOffset = contract.trimFrames,", "centralOffset = contract.contributionTrimFrames,")
replace_once(
    audio,
    """        } finally {\n            temporaryFiles.forEach { file -> runCatching { file.delete() } }""",
    """        } finally {\n            activeEngine = null\n            temporaryFiles.forEach { file -> runCatching { file.delete() } }""",
)
replace_once(
    audio,
    "    private fun readChunk(\n",
    """    private fun logBackendAttemptFailed(\n        attemptedBackend: String,\n        nextBackend: String?,\n        error: Throwable,\n    ) {\n        DiagnosticLogger.warn(\n            component = TAG,\n            event = \"mdx_backend_attempt_failed\",\n            sessionId = taskId,\n            message = error.message,\n            fields = mapOf(\n                \"model_id\" to model.id,\n                \"attempted_backend\" to attemptedBackend,\n                \"next_backend\" to nextBackend,\n            ),\n            error = error,\n        )\n    }\n\n    private fun createReflectPaddedPcm(\n        source: File,\n        destination: File,\n        sourceFrames: Long,\n        paddingFrames: Int,\n    ) {\n        require(sourceFrames > paddingFrames.toLong() * 2L)\n        val edgeByteCount = Math.multiplyExact(paddingFrames, bytesPerFrame)\n\n        fun reverseFrames(bytes: ByteArray): ByteArray {\n            val reversed = ByteArray(bytes.size)\n            for (frame in 0 until paddingFrames) {\n                val sourceOffset = (paddingFrames - 1 - frame) * bytesPerFrame\n                System.arraycopy(bytes, sourceOffset, reversed, frame * bytesPerFrame, bytesPerFrame)\n            }\n            return reversed\n        }\n\n        val prefix = ByteArray(edgeByteCount)\n        val suffix = ByteArray(edgeByteCount)\n        RandomAccessFile(source, \"r\").use { input ->\n            input.seek(bytesPerFrame.toLong())\n            input.readFully(prefix)\n            input.seek((sourceFrames - paddingFrames.toLong() - 1L) * bytesPerFrame.toLong())\n            input.readFully(suffix)\n        }\n\n        try {\n            FileOutputStream(destination).use { fileOutput ->\n                BufferedOutputStream(fileOutput, 1024 * 1024).use { output ->\n                    output.write(reverseFrames(prefix))\n                    FileInputStream(source).buffered(1024 * 1024).use { input ->\n                        input.copyTo(output, 1024 * 1024)\n                    }\n                    output.write(reverseFrames(suffix))\n                    output.flush()\n                    fileOutput.fd.sync()\n                }\n            }\n        } catch (error: Throwable) {\n            destination.delete()\n            throw error\n        }\n    }\n\n    private fun readChunk(\n""",
)

test = "app/src/test/java/com/aistudio/mediatool/core/ml/MdxOverlapAddWriterTest.kt"
replace_once(
    test,
    """    @Test\n    fun crossfadeWindowIsStrictlyPositiveAndSymmetric() {""",
    """    @Test\n    fun supportsSeventyFivePercentOverlapAndTrimsReflectPadding() {\n        val generated = 16\n        val stride = 4\n        val chunks = 7\n        val discard = 12\n        val totalFrames = 16\n        val window = MdxDsp.buildCrossfadeWindow(generated, 3)\n        val timelineLength = (chunks - 1) * stride + generated\n        val leftChunks = List(chunks) { chunk ->\n            FloatArray(generated) { local -> (chunk * stride + local).toFloat() }\n        }\n        val rightChunks = List(chunks) { chunk ->\n            FloatArray(generated) { local -> -(chunk * stride + local).toFloat() }\n        }\n        assertTrue(timelineLength >= discard + totalFrames)\n\n        val bytes = ByteArrayOutputStream()\n        MdxOverlapAddWriter(\n            output = DataOutputStream(bytes),\n            totalFrames = totalFrames.toLong(),\n            generatedFrames = generated,\n            strideFrames = stride,\n            window = window,\n            compensation = 1f,\n            discardLeadingFrames = discard.toLong(),\n        ).use { writer ->\n            for (chunk in 0 until chunks) writer.append(leftChunks[chunk], rightChunks[chunk], 0)\n        }\n\n        val result = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()\n        assertEquals(totalFrames * 2, result.remaining())\n        for (frame in 0 until totalFrames) {\n            val expected = (discard + frame).toFloat()\n            assertEquals(expected, result.get(frame * 2), 1e-5f)\n            assertEquals(-expected, result.get(frame * 2 + 1), 1e-5f)\n        }\n    }\n\n    @Test\n    fun crossfadeWindowIsStrictlyPositiveAndSymmetric() {""",
)

print("MDX23C audio pipeline patch applied")
