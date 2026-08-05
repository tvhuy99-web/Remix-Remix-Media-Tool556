package com.aistudio.mediatool.core.ml

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
        return if (filters.isEmpty()) "" else "-af \"${filters.joinToString(",")}\""
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
