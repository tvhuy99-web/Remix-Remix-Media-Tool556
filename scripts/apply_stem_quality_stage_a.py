#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}: {old[:100]!r}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


# 10. Persist the optional MDX two-pass denoise mode without touching global acceleration.
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/SettingsManager.kt",
    '    private const val KEY_STEM_LOW_MEMORY_FALLBACK_TASK = "stem_low_memory_fallback_task"\n',
    '    private const val KEY_STEM_LOW_MEMORY_FALLBACK_TASK = "stem_low_memory_fallback_task"\n'
    '    private const val KEY_STEM_MDX_DENOISE = "stem_mdx_denoise"\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/SettingsManager.kt",
    '    fun setStemLowMemoryFallbackTaskId(context: Context, taskId: String) =\n'
    '        prefs(context).edit().putString(KEY_STEM_LOW_MEMORY_FALLBACK_TASK, taskId).apply()\n\n',
    '    fun setStemLowMemoryFallbackTaskId(context: Context, taskId: String) =\n'
    '        prefs(context).edit().putString(KEY_STEM_LOW_MEMORY_FALLBACK_TASK, taskId).apply()\n\n'
    '    fun isStemMdxDenoiseEnabled(context: Context): Boolean =\n'
    '        prefs(context).getBoolean(KEY_STEM_MDX_DENOISE, false)\n\n'
    '    fun setStemMdxDenoiseEnabled(context: Context, enabled: Boolean) =\n'
    '        prefs(context).edit().putBoolean(KEY_STEM_MDX_DENOISE, enabled).apply()\n\n',
)

replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    'import com.aistudio.mediatool.core.ml.SeparationState\n',
    'import com.aistudio.mediatool.core.ml.SeparationState\n'
    'import com.aistudio.mediatool.core.ml.StemInferenceBackend\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    '    var modeIndex by rememberSaveable { mutableStateOf(SettingsManager.getStemModeIndex(context)) }\n',
    '    var modeIndex by rememberSaveable { mutableStateOf(SettingsManager.getStemModeIndex(context)) }\n'
    '    var mdxDenoiseEnabled by rememberSaveable {\n'
    '        mutableStateOf(SettingsManager.isStemMdxDenoiseEnabled(context))\n'
    '    }\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/ui/screens/StemScreen.kt",
    '                StemDownloadSection(\n',
    '                if (selectedModel.backend == StemInferenceBackend.MDX_LITERT) {\n'
    '                    CompactDropdown(\n'
    '                        label = "Chất lượng UVR",\n'
    '                        values = listOf("Tiêu chuẩn", "Làm sạch kỹ"),\n'
    '                        selectedIndex = if (mdxDenoiseEnabled) 1 else 0,\n'
    '                        onSelected = { index ->\n'
    '                            mdxDenoiseEnabled = index == 1\n'
    '                            SettingsManager.setStemMdxDenoiseEnabled(context, mdxDenoiseEnabled)\n'
    '                            resetResult()\n'
    '                        },\n'
    '                        modifier = Modifier.fillMaxWidth(),\n'
    '                    )\n'
    '                    Text(\n'
    '                        if (mdxDenoiseEnabled) {\n'
    '                            "Chạy hai lượt đối xứng để giảm nhiễu, thời gian xử lý gần gấp đôi."\n'
    '                        } else {\n'
    '                            "Một lượt xử lý, nhanh hơn và dùng ít điện hơn."\n'
    '                        },\n'
    '                        style = MaterialTheme.typography.bodySmall,\n'
    '                        color = MaterialTheme.colorScheme.onSurfaceVariant,\n'
    '                    )\n'
    '                }\n\n'
    '                StemDownloadSection(\n',
)

write(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxDenoise.kt",
    '''package com.aistudio.mediatool.core.ml

/** Reference MDX denoise combination: 0.5 * model(x) - 0.5 * model(-x). */
internal object MdxDenoise {
    fun combineInPlace(positive: FloatArray, negative: FloatArray): FloatArray {
        require(positive.size == negative.size) { "Hai tensor denoise phải cùng kích thước" }
        for (index in positive.indices) {
            val value = 0.5f * (positive[index] - negative[index])
            positive[index] = if (value.isFinite()) value else 0f
        }
        return positive
    }
}
''',
)

write(
    "app/src/main/java/com/aistudio/mediatool/core/ml/StemPcmToolkit.kt",
    '''package com.aistudio.mediatool.core.ml

import com.aistudio.mediatool.core.diagnostics.DiagnosticLogger
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
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

internal data class StemSeamMetrics(
    val count: Int,
    val maximumSampleJump: Double,
    val maximumRelativeJumpDb: Double,
    val maximumRmsDeltaDb: Double,
)

internal data class StemFileMetrics(
    val frames: Long,
    val rmsDbfs: Double,
    val samplePeakDbfs: Double,
    val samplePeakLinear: Double,
    val nonFiniteSamples: Long,
    val clippedSamplePercent: Double,
    val seam: StemSeamMetrics,
)

internal data class StemReconstructionMetrics(
    val rmsErrorDbfs: Double,
    val peakErrorDbfs: Double,
    val correlation: Double,
)

internal data class StemQualityReport(
    val stems: Map<String, StemFileMetrics>,
    val reconstruction: StemReconstructionMetrics,
    val maximumRawStemPeakDbfs: Double,
    val sharedGainDb: Double,
)

/** Streaming PCM utilities shared by MDX and Demucs. */
internal object StemPcmToolkit {
    private const val BLOCK_FRAMES = 8_192
    private const val SEAM_WINDOW_FRAMES = 1_024
    private const val MIN_DB = -160.0
    private const val CLIP_THRESHOLD = 1.0
    private const val TARGET_PEAK_DBFS = -1.0

    fun createResidual(
        mixFile: File,
        vocalsFile: File,
        destination: File,
        channels: Int,
        cancellationCheck: () -> Unit = {},
    ) {
        require(channels > 0)
        val bytesPerFrame = channels * Float.SIZE_BYTES
        require(mixFile.length() == vocalsFile.length()) {
            "Mix và vocals phải cùng độ dài để tạo instrumental residual"
        }
        require(mixFile.length() % bytesPerFrame == 0L) { "PCM mix sai căn chỉnh frame" }
        val mixBytes = ByteArray(BLOCK_FRAMES * bytesPerFrame)
        val vocalBytes = ByteArray(BLOCK_FRAMES * bytesPerFrame)
        val outputBytes = ByteBuffer.allocate(BLOCK_FRAMES * bytesPerFrame).order(ByteOrder.LITTLE_ENDIAN)
        var remainingFrames = mixFile.length() / bytesPerFrame
        DataInputStream(BufferedInputStream(FileInputStream(mixFile), 512 * 1024)).use { mixInput ->
            DataInputStream(BufferedInputStream(FileInputStream(vocalsFile), 512 * 1024)).use { vocalInput ->
                DataOutputStream(BufferedOutputStream(FileOutputStream(destination), 512 * 1024)).use { output ->
                    while (remainingFrames > 0L) {
                        cancellationCheck()
                        val frames = minOf(BLOCK_FRAMES.toLong(), remainingFrames).toInt()
                        val bytes = frames * bytesPerFrame
                        mixInput.readFully(mixBytes, 0, bytes)
                        vocalInput.readFully(vocalBytes, 0, bytes)
                        val mix = ByteBuffer.wrap(mixBytes, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        val vocals = ByteBuffer.wrap(vocalBytes, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        outputBytes.clear()
                        repeat(frames * channels) { sample ->
                            val value = finiteOrZero(mix.get(sample)) - finiteOrZero(vocals.get(sample))
                            outputBytes.putFloat(if (value.isFinite()) value else 0f)
                        }
                        output.write(outputBytes.array(), 0, bytes)
                        remainingFrames -= frames
                    }
                }
            }
        }
    }

    fun analyze(
        referenceMix: File,
        stemFiles: Map<String, File>,
        reconstructionStemNames: Set<String>,
        channels: Int,
        seamFrames: List<Long>,
    ): StemQualityReport {
        require(stemFiles.isNotEmpty())
        require(reconstructionStemNames.isNotEmpty())
        require(reconstructionStemNames.all(stemFiles::containsKey))
        val metrics = linkedMapOf<String, StemFileMetrics>()
        stemFiles.forEach { (name, file) -> metrics[name] = scan(file, channels, seamFrames) }
        val reconstruction = analyzeReconstruction(
            referenceMix = referenceMix,
            stems = reconstructionStemNames.map { checkNotNull(stemFiles[it]) },
            channels = channels,
        )
        val maximumPeak = metrics.values.maxOf(StemFileMetrics::samplePeakLinear)
        val sharedGainDb = sharedGainDbForPeak(maximumPeak)
        return StemQualityReport(
            stems = metrics,
            reconstruction = reconstruction,
            maximumRawStemPeakDbfs = linearToDb(maximumPeak),
            sharedGainDb = sharedGainDb,
        )
    }

    fun sharedGainDbForPeak(maximumPeakLinear: Double): Double {
        require(maximumPeakLinear.isFinite() && maximumPeakLinear >= 0.0)
        if (maximumPeakLinear <= CLIP_THRESHOLD) return 0.0
        val targetLinear = 10.0.pow(TARGET_PEAK_DBFS / 20.0)
        return linearToDb(targetLinear / maximumPeakLinear).coerceAtMost(0.0)
    }

    fun buildOutputFilterArguments(
        trimStartFrames: Long = 0L,
        trimFrameCount: Long? = null,
        sharedGainDb: Double,
    ): String {
        val filters = mutableListOf<String>()
        if (trimFrameCount != null) {
            require(trimStartFrames >= 0L && trimFrameCount > 0L)
            val end = trimStartFrames + trimFrameCount
            filters += "atrim=start_sample=$trimStartFrames:end_sample=$end"
            filters += "asetpts=N/SR/TB"
        }
        if (sharedGainDb < -1e-6) {
            filters += "volume=${String.format(Locale.US, "%.6f", sharedGainDb)}dB"
        }
        return if (filters.isEmpty()) "" else "-af \\"${filters.joinToString(",")}\\""
    }

    fun logDiagnostics(
        component: String,
        taskId: String,
        modelId: String,
        report: StemQualityReport,
        analysisElapsedMs: Long,
    ) {
        report.stems.forEach { (stem, metrics) ->
            DiagnosticLogger.info(
                component = component,
                event = "stem_quality_metrics",
                sessionId = taskId,
                fields = mapOf(
                    "model_id" to modelId,
                    "stem" to stem,
                    "frames" to metrics.frames,
                    "rms_dbfs" to metrics.rmsDbfs,
                    "sample_peak_dbfs" to metrics.samplePeakDbfs,
                    "non_finite_samples" to metrics.nonFiniteSamples,
                    "clipped_sample_percent" to metrics.clippedSamplePercent,
                    "seam_count" to metrics.seam.count,
                    "maximum_seam_sample_jump" to metrics.seam.maximumSampleJump,
                    "maximum_seam_relative_jump_db" to metrics.seam.maximumRelativeJumpDb,
                    "maximum_seam_rms_delta_db" to metrics.seam.maximumRmsDeltaDb,
                ),
            )
        }
        DiagnosticLogger.info(
            component = component,
            event = "stem_reconstruction_metrics",
            sessionId = taskId,
            fields = mapOf(
                "model_id" to modelId,
                "reconstruction_rms_error_dbfs" to report.reconstruction.rmsErrorDbfs,
                "reconstruction_peak_error_dbfs" to report.reconstruction.peakErrorDbfs,
                "reconstruction_correlation" to report.reconstruction.correlation,
                "maximum_raw_stem_peak_dbfs" to report.maximumRawStemPeakDbfs,
                "shared_output_gain_db" to report.sharedGainDb,
                "analysis_elapsed_ms" to analysisElapsedMs,
            ),
        )
    }

    private fun scan(file: File, channels: Int, seamFrames: List<Long>): StemFileMetrics {
        require(file.isFile)
        val bytesPerFrame = channels * Float.SIZE_BYTES
        require(file.length() > 0L && file.length() % bytesPerFrame == 0L) {
            "PCM stem không hợp lệ: ${file.name}"
        }
        val frames = file.length() / bytesPerFrame
        val block = ByteArray(BLOCK_FRAMES * bytesPerFrame)
        var remaining = frames
        var sumSquares = 0.0
        var finiteSamples = 0L
        var nonFiniteSamples = 0L
        var clippedSamples = 0L
        var peak = 0.0
        DataInputStream(BufferedInputStream(FileInputStream(file), 512 * 1024)).use { input ->
            while (remaining > 0L) {
                val frameCount = minOf(BLOCK_FRAMES.toLong(), remaining).toInt()
                val byteCount = frameCount * bytesPerFrame
                input.readFully(block, 0, byteCount)
                val floats = ByteBuffer.wrap(block, 0, byteCount).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                repeat(frameCount * channels) { index ->
                    val value = floats.get(index).toDouble()
                    if (!value.isFinite()) {
                        nonFiniteSamples++
                    } else {
                        val magnitude = abs(value)
                        peak = maxOf(peak, magnitude)
                        sumSquares += value * value
                        finiteSamples++
                        if (magnitude > CLIP_THRESHOLD) clippedSamples++
                    }
                }
                remaining -= frameCount
            }
        }
        val rms = if (finiteSamples > 0L) sqrt(sumSquares / finiteSamples) else 0.0
        val clippedPercent = if (finiteSamples > 0L) clippedSamples * 100.0 / finiteSamples else 0.0
        return StemFileMetrics(
            frames = frames,
            rmsDbfs = linearToDb(rms),
            samplePeakDbfs = linearToDb(peak),
            samplePeakLinear = peak,
            nonFiniteSamples = nonFiniteSamples,
            clippedSamplePercent = clippedPercent,
            seam = analyzeSeams(file, channels, frames, seamFrames),
        )
    }

    private fun analyzeSeams(
        file: File,
        channels: Int,
        totalFrames: Long,
        requestedSeams: List<Long>,
    ): StemSeamMetrics {
        val seams = requestedSeams.distinct().sorted().filter { it in 1 until totalFrames }
        if (seams.isEmpty()) return StemSeamMetrics(0, 0.0, MIN_DB, 0.0)
        val bytesPerFrame = channels * Float.SIZE_BYTES
        val buffer = ByteArray((SEAM_WINDOW_FRAMES * 2 + 2) * bytesPerFrame)
        var maxJump = 0.0
        var maxRelativeJumpDb = MIN_DB
        var maxRmsDeltaDb = 0.0
        RandomAccessFile(file, "r").use { input ->
            seams.forEach { seam ->
                val start = maxOf(0L, seam - SEAM_WINDOW_FRAMES)
                val end = minOf(totalFrames, seam + SEAM_WINDOW_FRAMES)
                val frames = (end - start).toInt()
                val bytes = frames * bytesPerFrame
                input.seek(start * bytesPerFrame)
                input.readFully(buffer, 0, bytes)
                val floats = ByteBuffer.wrap(buffer, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val seamOffset = (seam - start).toInt()
                var jump = 0.0
                repeat(channels) { channel ->
                    val before = finiteOrZero(floats.get((seamOffset - 1) * channels + channel)).toDouble()
                    val after = finiteOrZero(floats.get(seamOffset * channels + channel)).toDouble()
                    jump = maxOf(jump, abs(after - before))
                }
                var beforeSquares = 0.0
                var afterSquares = 0.0
                var beforeSamples = 0L
                var afterSamples = 0L
                for (frame in 0 until seamOffset) {
                    repeat(channels) { channel ->
                        val value = finiteOrZero(floats.get(frame * channels + channel)).toDouble()
                        beforeSquares += value * value
                        beforeSamples++
                    }
                }
                for (frame in seamOffset until frames) {
                    repeat(channels) { channel ->
                        val value = finiteOrZero(floats.get(frame * channels + channel)).toDouble()
                        afterSquares += value * value
                        afterSamples++
                    }
                }
                val beforeRms = if (beforeSamples > 0L) sqrt(beforeSquares / beforeSamples) else 0.0
                val afterRms = if (afterSamples > 0L) sqrt(afterSquares / afterSamples) else 0.0
                val localRms = sqrt((beforeSquares + afterSquares) / (beforeSamples + afterSamples).coerceAtLeast(1L))
                maxJump = maxOf(maxJump, jump)
                maxRelativeJumpDb = maxOf(maxRelativeJumpDb, linearToDb(jump / (localRms + 1e-12)))
                maxRmsDeltaDb = maxOf(maxRmsDeltaDb, abs(linearToDb(afterRms) - linearToDb(beforeRms)))
            }
        }
        return StemSeamMetrics(seams.size, maxJump, maxRelativeJumpDb, maxRmsDeltaDb)
    }

    private fun analyzeReconstruction(
        referenceMix: File,
        stems: List<File>,
        channels: Int,
    ): StemReconstructionMetrics {
        val bytesPerFrame = channels * Float.SIZE_BYTES
        require(referenceMix.length() > 0L && referenceMix.length() % bytesPerFrame == 0L)
        require(stems.all { it.length() == referenceMix.length() }) {
            "Các stem dùng để kiểm tra reconstruction phải cùng độ dài với mix"
        }
        val frames = referenceMix.length() / bytesPerFrame
        val referenceBytes = ByteArray(BLOCK_FRAMES * bytesPerFrame)
        val stemBytes = Array(stems.size) { ByteArray(BLOCK_FRAMES * bytesPerFrame) }
        val referenceInput = DataInputStream(BufferedInputStream(FileInputStream(referenceMix), 512 * 1024))
        val stemInputs = stems.map { DataInputStream(BufferedInputStream(FileInputStream(it), 512 * 1024)) }
        var errorSquares = 0.0
        var errorPeak = 0.0
        var referenceSquares = 0.0
        var reconstructionSquares = 0.0
        var dot = 0.0
        var sampleCount = 0L
        var remaining = frames
        try {
            while (remaining > 0L) {
                val frameCount = minOf(BLOCK_FRAMES.toLong(), remaining).toInt()
                val byteCount = frameCount * bytesPerFrame
                referenceInput.readFully(referenceBytes, 0, byteCount)
                stemInputs.forEachIndexed { index, input -> input.readFully(stemBytes[index], 0, byteCount) }
                val reference = ByteBuffer.wrap(referenceBytes, 0, byteCount)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                val stemBuffers = stemBytes.map { bytes ->
                    ByteBuffer.wrap(bytes, 0, byteCount).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                }
                repeat(frameCount * channels) { sample ->
                    val original = finiteOrZero(reference.get(sample)).toDouble()
                    var reconstructed = 0.0
                    stemBuffers.forEach { reconstructed += finiteOrZero(it.get(sample)).toDouble() }
                    val error = original - reconstructed
                    errorSquares += error * error
                    errorPeak = maxOf(errorPeak, abs(error))
                    referenceSquares += original * original
                    reconstructionSquares += reconstructed * reconstructed
                    dot += original * reconstructed
                    sampleCount++
                }
                remaining -= frameCount
            }
        } finally {
            runCatching(referenceInput::close)
            stemInputs.forEach { runCatching(it::close) }
        }
        val rmsError = if (sampleCount > 0L) sqrt(errorSquares / sampleCount) else 0.0
        val denominator = sqrt(referenceSquares * reconstructionSquares)
        val correlation = if (denominator > 1e-20) (dot / denominator).coerceIn(-1.0, 1.0) else 1.0
        return StemReconstructionMetrics(
            rmsErrorDbfs = linearToDb(rmsError),
            peakErrorDbfs = linearToDb(errorPeak),
            correlation = correlation,
        )
    }

    private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

    private fun linearToDb(value: Double): Double =
        if (!value.isFinite() || value <= 1e-8) MIN_DB else 20.0 * log10(value)
}
''',
)

# 7, 9, 10, 13. MDX: optional two-pass denoise, quality metrics, seams and shared anti-clip gain.
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '        val createdOutputs = mutableListOf<File>()\n        var outputsCommitted = false\n',
    '        val createdOutputs = mutableListOf<File>()\n'
    '        var outputsCommitted = false\n'
    '        val denoiseEnabled = SettingsManager.isStemMdxDenoiseEnabled(context)\n'
    '        var seamFrames: List<Long> = emptyList()\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '                "overlap_frames" to contract.overlapFrames,\n',
    '                "overlap_frames" to contract.overlapFrames,\n'
    '                "denoise_enabled" to denoiseEnabled,\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '                val chunkCount = chunksLong.toInt()\n',
    '                val chunkCount = chunksLong.toInt()\n'
    '                seamFrames = (1 until chunkCount).map { index -> index.toLong() * strideFrames }\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '''                            // LiteRT copies the input during writeInput. Drop the Java reference before
                            // native inference so the previous ~12 MiB tensor can be reclaimed before
                            // readOutput materializes the next one.
                            engine.writeInput(tensorSlot.borrow())
                            tensorSlot.release()
                            engine.execute()
                            val outputTensor = engine.readOutput()
                            val inferenceElapsed = SystemClock.elapsedRealtime() - inferenceStartedAt
                            coroutineContext.ensureActive()
                            check(!cancelRequested.get()) { "Đã hủy xử lý" }
                            val istftStartedAt = SystemClock.elapsedRealtime()
                            dsp.inverse(outputTensor, vocalsLeft, vocalsRight)
                            tensorSlot.accept(outputTensor)
''',
    '''                            val modelInput = tensorSlot.borrow()
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
''',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '                                    "effective_backend" to engine.backend,\n',
    '                                    "effective_backend" to engine.backend,\n'
    '                                    "inference_passes" to if (denoiseEnabled) 2 else 1,\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '            createResidualInstrumental(tempRawMix, tempRawVocals, tempRawMusic, totalFrames)\n',
    '            StemPcmToolkit.createResidual(\n'
    '                mixFile = tempRawMix,\n'
    '                vocalsFile = tempRawVocals,\n'
    '                destination = tempRawMusic,\n'
    '                channels = channels,\n'
    '                cancellationCheck = {\n'
    '                    if (cancelRequested.get()) throw CancellationException("Đã hủy xử lý")\n'
    '                },\n'
    '            )\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '''            logInfo(
                event = "mdx_residual_complete",
                fields = mapOf("elapsed_ms" to SystemClock.elapsedRealtime() - residualStartedAt),
            )

            checkpoint("mdx_encoding", 0.88f)
''',
    '''            logInfo(
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
''',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '                    "$encodingArguments \\"${vocalsOutput.absolutePath}\\""\n',
    '                    "$outputFilterArguments $encodingArguments \\"${vocalsOutput.absolutePath}\\""\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '                    "$encodingArguments \\"${musicOutput.absolutePath}\\""\n',
    '                    "$outputFilterArguments $encodingArguments \\"${musicOutput.absolutePath}\\""\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/MdxAudioSeparator.kt",
    '                    "format" to extension,\n',
    '                    "format" to extension,\n'
    '                    "denoise_enabled" to denoiseEnabled,\n'
    '                    "shared_output_gain_db" to qualityReport.sharedGainDb,\n',
)

# 7, 8, 9, 13. Demucs: residual two-stem, quality/reconstruction metrics, seams and shared gain.
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    '        val is4StemMode = model.mode == StemMode.FOUR_STEM\n',
    '        val is4StemMode = model.mode == StemMode.FOUR_STEM\n'
    '        val seamFrames = mutableListOf<Long>()\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    '                        if (actualFramesRead == 0 && isFirstChunk) break\n\n                        if (!isFirstChunk) {\n',
    '                        if (actualFramesRead == 0 && isFirstChunk) break\n'
    '                        if (!isFirstChunk) {\n'
    '                            seamFrames += chunkIndex.toLong() * stepSize.toLong()\n'
    '                        }\n\n'
    '                        if (!isFirstChunk) {\n',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    '''            checkpoint("encoding", 0.90f)
            emit(SeparationState.Progress(0.9f))

            val ext = SettingsManager.getAudioFormatExt(context)
            val encodingArgs = SettingsManager.getAudioEncodingArgs(context)
            val trimArgs = if (boundaryPaddingFrames > 0) {
                val endSample = boundaryPaddingFrames.toLong() + originalFrames
                "-af \\"atrim=start_sample=$boundaryPaddingFrames:end_sample=$endSample,asetpts=N/SR/TB\\""
            } else {
                ""
            }
''',
    '''            if (!is4StemMode) {
                StemPcmToolkit.createResidual(
                    mixFile = inferencePcm,
                    vocalsFile = tempRawVocals,
                    destination = tempRawMusic,
                    channels = channels,
                    cancellationCheck = { coroutineContext.ensureActive() },
                )
                logInfo(
                    event = "two_stem_residual_complete",
                    fields = mapOf("pcm_bytes" to tempRawMusic.length()),
                )
            }

            val qualityStemFiles = linkedMapOf(
                "vocals" to tempRawVocals,
                "music" to tempRawMusic,
            ).apply {
                if (is4StemMode) {
                    put("drums", tempRawDrums)
                    put("bass", tempRawBass)
                    put("other", tempRawOther)
                }
            }
            val qualityStartedAt = SystemClock.elapsedRealtime()
            val qualityReport = StemPcmToolkit.analyze(
                referenceMix = inferencePcm,
                stemFiles = qualityStemFiles,
                reconstructionStemNames = if (is4StemMode) {
                    setOf("vocals", "drums", "bass", "other")
                } else {
                    setOf("vocals", "music")
                },
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

            checkpoint("encoding", 0.90f)
            emit(SeparationState.Progress(0.9f))

            val ext = SettingsManager.getAudioFormatExt(context)
            val encodingArgs = SettingsManager.getAudioEncodingArgs(context)
            val outputFilterArgs = StemPcmToolkit.buildOutputFilterArguments(
                trimStartFrames = boundaryPaddingFrames.toLong(),
                trimFrameCount = originalFrames.takeIf { boundaryPaddingFrames > 0 },
                sharedGainDb = qualityReport.sharedGainDb,
            )
''',
)
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    ' $trimArgs $encodingArgs ',
    ' $outputFilterArgs $encodingArgs ',
)
# Four more trimArgs occurrences.
for _ in range(4):
    replace_once(
        "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
        ' $trimArgs $encodingArgs ',
        ' $outputFilterArgs $encodingArgs ',
    )
replace_once(
    "app/src/main/java/com/aistudio/mediatool/core/ml/AudioSeparator.kt",
    '                    "format" to ext,\n',
    '                    "format" to ext,\n'
    '                    "shared_output_gain_db" to qualityReport.sharedGainDb,\n',
)

write(
    "app/src/test/java/com/aistudio/mediatool/core/ml/MdxDenoiseTest.kt",
    '''package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MdxDenoiseTest {
    @Test
    fun combinesPositiveAndNegativePredictionsInPlace() {
        val positive = floatArrayOf(4f, 1f, -2f)
        val negative = floatArrayOf(2f, -1f, 2f)

        val result = MdxDenoise.combineInPlace(positive, negative)

        assertSame(positive, result)
        assertArrayEquals(floatArrayOf(1f, 1f, -2f), result, 0f)
    }
}
''',
)

write(
    "app/src/test/java/com/aistudio/mediatool/core/ml/StemPcmToolkitTest.kt",
    '''package com.aistudio.mediatool.core.ml

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StemPcmToolkitTest {
    @Test
    fun residualReconstructsOriginalMix() {
        val mix = pcmFile(floatArrayOf(0.8f, -0.4f, 0.2f, 0.6f))
        val vocals = pcmFile(floatArrayOf(0.3f, -0.1f, -0.2f, 0.1f))
        val music = File.createTempFile("stem-music", ".f32")
        try {
            StemPcmToolkit.createResidual(mix, vocals, music, channels = 2)
            val report = StemPcmToolkit.analyze(
                referenceMix = mix,
                stemFiles = linkedMapOf("vocals" to vocals, "music" to music),
                reconstructionStemNames = setOf("vocals", "music"),
                channels = 2,
                seamFrames = emptyList(),
            )
            assertTrue(report.reconstruction.rmsErrorDbfs <= -130.0)
            assertTrue(report.reconstruction.correlation >= 0.999999)
        } finally {
            mix.delete()
            vocals.delete()
            music.delete()
        }
    }

    @Test
    fun sharedGainOnlyActivatesAboveFullScale() {
        assertEquals(0.0, StemPcmToolkit.sharedGainDbForPeak(1.0), 0.0)
        val gain = StemPcmToolkit.sharedGainDbForPeak(2.0)
        assertTrue(gain < -6.9 && gain > -7.1)
    }

    @Test
    fun seamMetricsDetectDiscontinuity() {
        val samples = FloatArray(4_096 * 2)
        for (frame in 2_048 until 4_096) {
            samples[frame * 2] = 0.8f
            samples[frame * 2 + 1] = 0.8f
        }
        val mix = pcmFile(samples)
        val silence = pcmFile(FloatArray(samples.size))
        try {
            val report = StemPcmToolkit.analyze(
                referenceMix = mix,
                stemFiles = linkedMapOf("main" to mix, "zero" to silence),
                reconstructionStemNames = setOf("main", "zero"),
                channels = 2,
                seamFrames = listOf(2_048L),
            )
            assertTrue(checkNotNull(report.stems["main"]).seam.maximumSampleJump > 0.7)
        } finally {
            mix.delete()
            silence.delete()
        }
    }

    private fun pcmFile(samples: FloatArray): File = File.createTempFile("stem-pcm", ".f32").also { file ->
        DataOutputStream(FileOutputStream(file)).use { output -> samples.forEach(output::writeFloatLittleEndian) }
    }

    private fun DataOutputStream.writeFloatLittleEndian(value: Float) {
        val bits = value.toRawBits()
        writeByte(bits and 0xff)
        writeByte((bits ushr 8) and 0xff)
        writeByte((bits ushr 16) and 0xff)
        writeByte((bits ushr 24) and 0xff)
    }
}
''',
)

print("Stem quality stage A patch applied")
