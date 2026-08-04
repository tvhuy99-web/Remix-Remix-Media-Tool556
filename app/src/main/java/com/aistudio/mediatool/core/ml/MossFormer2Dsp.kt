package com.aistudio.mediatool.core.ml

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/**
 * Host DSP contract for the published MossFormer2_SE_48K mask predictor.
 *
 * This class is single-threaded. Returned feature and audio arrays are borrowed workspace buffers and
 * remain valid only until the next call that produces the same kind of output.
 */
internal class MossFormer2Dsp(
    val windowMode: VoiceCleanupWindowMode = VoiceCleanupWindowMode.BALANCED_10S,
) {
    val segmentSamples: Int = windowMode.segmentSamples
    val strideSamples: Int = windowMode.strideSamples
    val edgeDiscardSamples: Int = windowMode.edgeDiscardSamples
    val frames: Int = windowMode.frames

    private val maskFft = BluesteinFft(FFT_SIZE)
    private val fbankFft = MixedRadixFft(FBANK_FFT_SIZE)
    private val maskWindow = symmetricHamming(FFT_SIZE)
    private val fbankWindow = symmetricHamming(FRAME_LENGTH)
    private val melWeights = buildMelWeights()
    private val workspace = MossFormer2Workspace(segmentSamples, frames)

    private val fbankReal = FloatArray(FBANK_FFT_SIZE)
    private val fbankImag = FloatArray(FBANK_FFT_SIZE)
    private val stftReal = FloatArray(FFT_SIZE)
    private val stftImag = FloatArray(FFT_SIZE)

    fun buildFeatures(samples: FloatArray): FloatArray {
        require(samples.size == segmentSamples) {
            "MossFormer2 cần đúng $segmentSamples mẫu cho mỗi đoạn ${windowMode.seconds} giây"
        }
        val base = workspace.featureBase
        for (frame in 0 until frames) {
            val start = frame * HOP_SIZE
            var mean = 0.0
            for (index in 0 until FRAME_LENGTH) mean += samples[start + index]
            mean /= FRAME_LENGTH.toDouble()

            for (index in 0 until FRAME_LENGTH) {
                fbankReal[index] = samples[start + index] - mean.toFloat()
            }
            for (index in FRAME_LENGTH - 1 downTo 1) {
                fbankReal[index] -= PREEMPHASIS * fbankReal[index - 1]
            }
            fbankReal[0] *= 1f - PREEMPHASIS
            for (index in 0 until FRAME_LENGTH) fbankReal[index] *= fbankWindow[index]
            Arrays.fill(fbankReal, FRAME_LENGTH, FBANK_FFT_SIZE, 0f)
            Arrays.fill(fbankImag, 0f)
            fbankFft.forward(fbankReal, fbankImag)

            val targetOffset = frame * MEL_BINS
            for (mel in 0 until MEL_BINS) {
                val weightOffset = mel * FBANK_BINS
                var energy = 0.0
                for (bin in 0 until FBANK_BINS) {
                    val weight = melWeights[weightOffset + bin]
                    if (weight == 0f) continue
                    val real = fbankReal[bin]
                    val imag = fbankImag[bin]
                    energy += weight * (real * real + imag * imag)
                }
                base[targetOffset + mel] = ln(energy.coerceAtLeast(FLOAT32_EPSILON)).toFloat()
            }
        }

        val delta = workspace.featureDelta
        val deltaDelta = workspace.featureDeltaDelta
        computeDeltas(base, frames, MEL_BINS, delta)
        computeDeltas(delta, frames, MEL_BINS, deltaDelta)
        val features = workspace.features
        for (frame in 0 until frames) {
            val source = frame * MEL_BINS
            val target = frame * FEATURES
            base.copyInto(features, target, source, source + MEL_BINS)
            delta.copyInto(features, target + MEL_BINS, source, source + MEL_BINS)
            deltaDelta.copyInto(features, target + 2 * MEL_BINS, source, source + MEL_BINS)
        }
        check(features.all(Float::isFinite)) { "Frontend MossFormer2 tạo feature không hữu hạn" }
        return features
    }

    fun applyMask(samples: FloatArray, mask: FloatArray): FloatArray {
        require(samples.size == segmentSamples)
        require(mask.size == frames * BINS) {
            "Mask MossFormer2 có ${mask.size} phần tử, cần ${frames * BINS}"
        }
        workspace.clearSynthesis()
        val output = workspace.output
        val envelope = workspace.envelope

        for (frame in 0 until frames) {
            val start = frame * HOP_SIZE
            for (index in 0 until FFT_SIZE) {
                stftReal[index] = samples[start + index] * maskWindow[index]
                stftImag[index] = 0f
            }
            maskFft.forward(stftReal, stftImag)
            val maskOffset = frame * BINS
            for (bin in 0 until BINS) {
                val gain = mask[maskOffset + bin]
                stftReal[bin] *= gain
                stftImag[bin] *= gain
            }
            for (bin in 1 until BINS - 1) {
                val mirror = FFT_SIZE - bin
                stftReal[mirror] = stftReal[bin]
                stftImag[mirror] = -stftImag[bin]
            }
            maskFft.inverse(stftReal, stftImag)
            for (index in 0 until FFT_SIZE) {
                val position = start + index
                val window = maskWindow[index]
                output[position] += stftReal[index] * window
                envelope[position] += window * window
            }
        }

        for (index in output.indices) {
            val weight = envelope[index]
            output[index] = if (weight > MIN_ENVELOPE) output[index] / weight else 0f
        }
        check(output.all(Float::isFinite)) { "MossFormer2 tạo audio không hữu hạn" }
        return output
    }

    fun paddedLength(inputSamples: Long): Long {
        val count = segmentCount(inputSamples)
        return segmentSamples + (count - 1L) * strideSamples
    }

    fun segmentCount(inputSamples: Long): Int {
        require(inputSamples >= 0L)
        val firstRetainedSamples = segmentSamples - edgeDiscardSamples
        if (inputSamples <= firstRetainedSamples) return 1
        val remaining = inputSamples - firstRetainedSamples
        val additional = (remaining + strideSamples - 1L) / strideSamples
        val count = 1L + additional
        require(count <= Int.MAX_VALUE)
        return count.toInt()
    }

    fun retainedRange(segmentIndex: Int): IntRange {
        require(segmentIndex >= 0)
        return if (segmentIndex == 0) {
            0 until segmentSamples - edgeDiscardSamples
        } else {
            edgeDiscardSamples until segmentSamples - edgeDiscardSamples
        }
    }

    internal fun windowSnapshot(): FloatArray = maskWindow.copyOf()

    companion object {
        const val SAMPLE_RATE = 48_000
        const val FFT_SIZE = 1_920
        const val HOP_SIZE = 384
        const val BINS = FFT_SIZE / 2 + 1
        const val MEL_BINS = 60
        const val FEATURES = MEL_BINS * 3
        const val REFERENCE_SEGMENT_SAMPLES = 4 * SAMPLE_RATE
        const val REFERENCE_FRAMES = 496

        private const val FRAME_LENGTH = FFT_SIZE
        private const val FBANK_FFT_SIZE = 2_048
        private const val FBANK_BINS = FBANK_FFT_SIZE / 2 + 1
        private const val PREEMPHASIS = 0.97f
        private const val LOW_FREQUENCY_HZ = 20.0
        private const val FLOAT32_EPSILON = 1.1920928955078125e-7
        private const val MIN_ENVELOPE = 1.0e-8f

        fun frameCount(segmentSamples: Int): Int {
            require(segmentSamples >= FFT_SIZE) { "Cửa sổ MossFormer2 quá ngắn" }
            require((segmentSamples - FFT_SIZE) % HOP_SIZE == 0) {
                "Cửa sổ MossFormer2 phải khớp hop $HOP_SIZE mẫu"
            }
            return 1 + (segmentSamples - FFT_SIZE) / HOP_SIZE
        }

        internal fun computeDeltas(input: FloatArray, frames: Int, bins: Int): FloatArray =
            FloatArray(input.size).also { output ->
                computeDeltas(input, frames, bins, output)
            }

        internal fun computeDeltas(
            input: FloatArray,
            frames: Int,
            bins: Int,
            output: FloatArray,
        ) {
            require(input.size == frames * bins)
            require(output.size == input.size)
            for (frame in 0 until frames) {
                for (bin in 0 until bins) {
                    var numerator = 0f
                    for (distance in 1..2) {
                        val before = (frame - distance).coerceAtLeast(0)
                        val after = (frame + distance).coerceAtMost(frames - 1)
                        numerator += distance * (input[after * bins + bin] - input[before * bins + bin])
                    }
                    output[frame * bins + bin] = numerator / 10f
                }
            }
        }

        private fun symmetricHamming(size: Int): FloatArray = FloatArray(size) { index ->
            (0.54 - 0.46 * cos(2.0 * PI * index.toDouble() / (size - 1).toDouble())).toFloat()
        }

        private fun melScale(frequencyHz: Double): Double = 1127.0 * ln(1.0 + frequencyHz / 700.0)

        private fun buildMelWeights(): FloatArray {
            val weights = FloatArray(MEL_BINS * FBANK_BINS)
            val lowMel = melScale(LOW_FREQUENCY_HZ)
            val highMel = melScale(SAMPLE_RATE / 2.0)
            val points = DoubleArray(MEL_BINS + 2) { index ->
                lowMel + (highMel - lowMel) * index / (MEL_BINS + 1).toDouble()
            }
            for (mel in 0 until MEL_BINS) {
                val left = points[mel]
                val center = points[mel + 1]
                val right = points[mel + 2]
                val offset = mel * FBANK_BINS
                for (bin in 0 until FBANK_BINS) {
                    val frequency = bin.toDouble() * SAMPLE_RATE.toDouble() / FBANK_FFT_SIZE.toDouble()
                    val value = melScale(frequency)
                    weights[offset + bin] = when {
                        value <= left || value >= right -> 0f
                        value <= center -> ((value - left) / (center - left)).toFloat()
                        else -> ((right - value) / (right - center)).toFloat()
                    }
                }
            }
            return weights
        }
    }
}
