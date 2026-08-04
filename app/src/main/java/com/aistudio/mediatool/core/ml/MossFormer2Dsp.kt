package com.aistudio.mediatool.core.ml

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/** Host DSP contract for the published MossFormer2_SE_48K mask predictor. */
internal class MossFormer2Dsp {
    private val maskFft = BluesteinFft(FFT_SIZE)
    private val fbankFft = MixedRadixFft(FBANK_FFT_SIZE)
    private val maskWindow = symmetricHamming(FFT_SIZE)
    private val fbankWindow = symmetricHamming(FRAME_LENGTH)
    private val melWeights = buildMelWeights()

    private val fbankReal = FloatArray(FBANK_FFT_SIZE)
    private val fbankImag = FloatArray(FBANK_FFT_SIZE)
    private val stftReal = FloatArray(FFT_SIZE)
    private val stftImag = FloatArray(FFT_SIZE)

    fun buildFeatures(samples: FloatArray): FloatArray {
        require(samples.size == SEGMENT_SAMPLES) {
            "MossFormer2 cần đúng $SEGMENT_SAMPLES mẫu cho mỗi đoạn"
        }
        val base = FloatArray(FRAMES * MEL_BINS)
        for (frame in 0 until FRAMES) {
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
                base[targetOffset + mel] = ln(energy.coerceAtLeast(ENERGY_FLOOR)).toFloat()
            }
        }

        val delta = computeDeltas(base, FRAMES, MEL_BINS)
        val deltaDelta = computeDeltas(delta, FRAMES, MEL_BINS)
        val features = FloatArray(FRAMES * FEATURES)
        for (frame in 0 until FRAMES) {
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
        require(samples.size == SEGMENT_SAMPLES)
        require(mask.size == FRAMES * BINS) {
            "Mask MossFormer2 có ${mask.size} phần tử, cần ${FRAMES * BINS}"
        }
        val output = FloatArray(SEGMENT_SAMPLES)
        val envelope = FloatArray(SEGMENT_SAMPLES)

        for (frame in 0 until FRAMES) {
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

    internal fun windowSnapshot(): FloatArray = maskWindow.copyOf()

    companion object {
        const val SAMPLE_RATE = 48_000
        const val SEGMENT_SAMPLES = 192_000
        const val STRIDE_SAMPLES = 144_000
        const val EDGE_DISCARD_SAMPLES = 24_000
        const val FFT_SIZE = 1_920
        const val HOP_SIZE = 384
        const val FRAMES = 496
        const val BINS = FFT_SIZE / 2 + 1
        const val MEL_BINS = 60
        const val FEATURES = MEL_BINS * 3

        private const val FRAME_LENGTH = FFT_SIZE
        private const val FBANK_FFT_SIZE = 2_048
        private const val FBANK_BINS = FBANK_FFT_SIZE / 2 + 1
        private const val PREEMPHASIS = 0.97f
        private const val LOW_FREQUENCY_HZ = 20.0
        private const val ENERGY_FLOOR = 1.0e-10
        private const val MIN_ENVELOPE = 1.0e-8f

        fun paddedLength(inputSamples: Long): Long {
            require(inputSamples >= 0L)
            if (inputSamples <= SEGMENT_SAMPLES) return SEGMENT_SAMPLES.toLong()
            val extra = inputSamples - SEGMENT_SAMPLES
            val strides = (extra + STRIDE_SAMPLES - 1L) / STRIDE_SAMPLES
            return SEGMENT_SAMPLES + strides * STRIDE_SAMPLES
        }

        fun segmentCount(inputSamples: Long): Int {
            val padded = paddedLength(inputSamples)
            val count = 1L + (padded - SEGMENT_SAMPLES) / STRIDE_SAMPLES
            require(count <= Int.MAX_VALUE)
            return count.toInt()
        }

        fun retainedRange(segmentIndex: Int): IntRange {
            require(segmentIndex >= 0)
            return if (segmentIndex == 0) {
                0 until SEGMENT_SAMPLES - EDGE_DISCARD_SAMPLES
            } else {
                EDGE_DISCARD_SAMPLES until SEGMENT_SAMPLES - EDGE_DISCARD_SAMPLES
            }
        }

        internal fun computeDeltas(input: FloatArray, frames: Int, bins: Int): FloatArray {
            require(input.size == frames * bins)
            val output = FloatArray(input.size)
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
            return output
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
