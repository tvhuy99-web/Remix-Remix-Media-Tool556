package com.aistudio.mediatool.core.spatial

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

internal data class SpatialStereoPostMetrics(
    val mode: String,
    val preservation: Float,
    val distanceWidthScale: Float,
    val peakBefore: Float,
    val peakAfter: Float,
    val peakGainDb: Float,
    val frames: Long,
) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "stereo_post_mode" to mode,
        "stereo_side_preservation" to preservation,
        "stereo_distance_width_scale" to distanceWidthScale,
        "stereo_post_peak_before" to peakBefore,
        "stereo_post_peak_after" to peakAfter,
        "stereo_post_peak_gain_db" to peakGainDb,
        "stereo_post_frames" to frames,
    )
}

/**
 * Keeps Steam Audio's binaural L/R cues while restoring part of the source Side channel. The
 * processor never applies positive gain: a second read pass only attenuates when the reconstructed
 * stereo image would exceed the -1 dBFS safety ceiling.
 */
internal object SpatialStereoPostProcessor {
    private const val TARGET_PEAK = 0.89125094f
    private const val MAX_SIDE_PRESERVATION = 0.40f
    private const val FRAMES_PER_BLOCK = 4_096
    private const val BYTES_PER_FRAME = 8

    fun process(
        sourceStereo: File,
        pointRenderedStereo: File,
        output: File,
        spatialBlend: Float,
        startDistanceM: Float,
        endDistanceM: Float,
        inputDualMono: Boolean,
    ): SpatialStereoPostMetrics {
        require(sourceStereo.isFile && sourceStereo.length() >= BYTES_PER_FRAME) {
            "PCM nguồn cho stereo preservation không hợp lệ"
        }
        require(pointRenderedStereo.isFile && pointRenderedStereo.length() >= BYTES_PER_FRAME) {
            "PCM binaural cho stereo preservation không hợp lệ"
        }
        output.parentFile?.mkdirs()
        output.delete()

        val distanceScale = distanceWidthScale(max(startDistanceM, endDistanceM))
        val preservation = if (inputDualMono) 0f else {
            MAX_SIDE_PRESERVATION * spatialBlend.coerceIn(0f, 1f) * distanceScale
        }
        if (preservation <= 1e-6f) {
            moveOrCopy(pointRenderedStereo, output)
            return SpatialStereoPostMetrics(
                mode = if (inputDualMono) "dual_mono_binaural" else "point_stereo_bypass",
                preservation = 0f,
                distanceWidthScale = distanceScale,
                peakBefore = scanPeak(output),
                peakAfter = scanPeak(output),
                peakGainDb = 0f,
                frames = output.length() / BYTES_PER_FRAME,
            )
        }

        val firstPass = scanReconstructed(
            sourceStereo = sourceStereo,
            pointRenderedStereo = pointRenderedStereo,
            preservation = preservation,
            output = null,
            gain = 1f,
        )
        val gain = if (firstPass.peak > TARGET_PEAK && firstPass.peak > 0f) {
            TARGET_PEAK / firstPass.peak
        } else 1f
        val secondPass = scanReconstructed(
            sourceStereo = sourceStereo,
            pointRenderedStereo = pointRenderedStereo,
            preservation = preservation,
            output = output,
            gain = gain,
        )
        require(output.isFile && output.length() >= BYTES_PER_FRAME) {
            "Không tạo được PCM stereo Mid/Side"
        }
        return SpatialStereoPostMetrics(
            mode = "mid_side_preserved",
            preservation = preservation,
            distanceWidthScale = distanceScale,
            peakBefore = firstPass.peak,
            peakAfter = secondPass.peak,
            peakGainDb = if (gain > 0f) 20f * log10(gain) else -160f,
            frames = secondPass.frames,
        )
    }

    internal fun reconstructFrame(
        nativeLeft: Float,
        nativeRight: Float,
        sourceLeft: Float,
        sourceRight: Float,
        preservation: Float,
    ): Pair<Float, Float> {
        val amount = preservation.coerceIn(0f, 1f)
        val nativeMid = 0.5f * (nativeLeft + nativeRight)
        val nativeSide = 0.5f * (nativeLeft - nativeRight)
        val sourceSide = 0.5f * (sourceLeft - sourceRight)
        val side = nativeSide + amount * (sourceSide - nativeSide)
        return Pair(nativeMid + side, nativeMid - side)
    }

    internal fun distanceWidthScale(distanceM: Float): Float {
        val distance = distanceM.coerceAtLeast(0.8f)
        return (1f / (1f + 0.08f * (distance - 1f))).coerceIn(0.35f, 1f)
    }

    private data class ScanResult(val peak: Float, val frames: Long)

    private fun scanReconstructed(
        sourceStereo: File,
        pointRenderedStereo: File,
        preservation: Float,
        output: File?,
        gain: Float,
    ): ScanResult {
        BufferedInputStream(pointRenderedStereo.inputStream(), BLOCK_BYTES).use { renderedInput ->
            BufferedInputStream(sourceStereo.inputStream(), BLOCK_BYTES).use { sourceInput ->
                val outputStream = output?.let {
                    BufferedOutputStream(it.outputStream(), BLOCK_BYTES)
                }
                outputStream.use { renderedOutput ->
                    val renderedBytes = ByteArray(BLOCK_BYTES)
                    val sourceBytes = ByteArray(BLOCK_BYTES)
                    val outputBytes = ByteArray(BLOCK_BYTES)
                    var peak = 0f
                    var frames = 0L
                    while (true) {
                        val renderedCount = readAligned(renderedInput, renderedBytes)
                        if (renderedCount <= 0) break
                        val sourceCount = readAlignedUpTo(sourceInput, sourceBytes, renderedCount)
                        val renderedFloats = ByteBuffer.wrap(renderedBytes, 0, renderedCount)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer()
                        val sourceFloats = ByteBuffer.wrap(sourceBytes, 0, sourceCount)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asFloatBuffer()
                        val outputBuffer = ByteBuffer.wrap(outputBytes)
                            .order(ByteOrder.LITTLE_ENDIAN)
                        val renderedFrames = renderedCount / BYTES_PER_FRAME
                        val sourceFrames = sourceCount / BYTES_PER_FRAME
                        for (frame in 0 until renderedFrames) {
                            val nativeLeft = finiteOrZero(renderedFloats.get(frame * 2))
                            val nativeRight = finiteOrZero(renderedFloats.get(frame * 2 + 1))
                            val sourceLeft = if (frame < sourceFrames) {
                                finiteOrZero(sourceFloats.get(frame * 2))
                            } else 0f
                            val sourceRight = if (frame < sourceFrames) {
                                finiteOrZero(sourceFloats.get(frame * 2 + 1))
                            } else 0f
                            val reconstructed = reconstructFrame(
                                nativeLeft,
                                nativeRight,
                                sourceLeft,
                                sourceRight,
                                preservation,
                            )
                            val left = finiteOrZero(reconstructed.first * gain)
                            val right = finiteOrZero(reconstructed.second * gain)
                            peak = max(peak, max(kotlin.math.abs(left), kotlin.math.abs(right)))
                            if (renderedOutput != null) {
                                outputBuffer.putFloat(left)
                                outputBuffer.putFloat(right)
                            }
                            ++frames
                        }
                        if (renderedOutput != null) {
                            renderedOutput.write(outputBytes, 0, renderedFrames * BYTES_PER_FRAME)
                        }
                    }
                    renderedOutput?.flush()
                    return ScanResult(peak = peak, frames = frames)
                }
            }
        }
    }

    private fun scanPeak(file: File): Float {
        var peak = 0f
        BufferedInputStream(file.inputStream(), BLOCK_BYTES).use { input ->
            val bytes = ByteArray(BLOCK_BYTES)
            while (true) {
                val count = readAligned(input, bytes)
                if (count <= 0) break
                val floats = ByteBuffer.wrap(bytes, 0, count)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer()
                for (index in 0 until floats.limit()) {
                    peak = max(peak, kotlin.math.abs(finiteOrZero(floats.get(index))))
                }
            }
        }
        return peak
    }

    private fun readAligned(input: BufferedInputStream, target: ByteArray): Int =
        readAlignedUpTo(input, target, target.size)

    private fun readAlignedUpTo(
        input: BufferedInputStream,
        target: ByteArray,
        maximum: Int,
    ): Int {
        var total = 0
        while (total < maximum) {
            val count = input.read(target, total, maximum - total)
            if (count < 0) break
            if (count == 0) continue
            total += count
        }
        val aligned = total - total % BYTES_PER_FRAME
        if (aligned < target.size) target.fill(0, aligned, target.size)
        return aligned
    }

    private fun moveOrCopy(source: File, output: File) {
        if (!source.renameTo(output)) {
            source.copyTo(output, overwrite = true)
            source.delete()
        }
    }

    private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f

    private const val BLOCK_BYTES = FRAMES_PER_BLOCK * BYTES_PER_FRAME
}
