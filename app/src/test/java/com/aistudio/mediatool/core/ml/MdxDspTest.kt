package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxDspTest {
    private val contract = MdxSpectrogramContract(
        nFft = 12,
        hopLength = 3,
        frequencyBins = 6,
        timeFrames = 9,
        overlapRatio = 0.25f,
    )

    @Test
    fun forwardMatchesIndependentDftReference() {
        val left = FloatArray(contract.chunkFrames) { index ->
            (
                0.35 * sin(2.0 * PI * index / 12.0) +
                    0.05 * cos(2.0 * PI * 3.0 * index / 12.0) +
                    (index - 7) * 0.03125
                ).toFloat()
        }
        val right = FloatArray(contract.chunkFrames) { index ->
            (0.20 * cos(2.0 * PI * 2.0 * index / 12.0) - index * 0.015).toFloat()
        }
        val expected = referenceForward(left, right)
        val actual = FloatArray(contract.tensorElements)

        MdxDsp(contract).forward(left, right, actual)

        assertMaxErrorBelow(actual, expected, 3e-4f)
    }

    @Test
    fun inverseMatchesIndependentIdftReference() {
        val left = FloatArray(contract.chunkFrames) { index ->
            (0.4 * sin(2.0 * PI * index / 12.0) + 0.02 * index).toFloat()
        }
        val right = FloatArray(contract.chunkFrames) { index ->
            (0.3 * cos(2.0 * PI * 2.0 * index / 12.0) - 0.01 * index).toFloat()
        }
        val source = referenceForward(left, right)
        val expectedLeft = FloatArray(contract.chunkFrames)
        val expectedRight = FloatArray(contract.chunkFrames)
        referenceInverse(source, expectedLeft, expectedRight)
        val actualLeft = FloatArray(contract.chunkFrames)
        val actualRight = FloatArray(contract.chunkFrames)

        MdxDsp(contract).inverse(source, actualLeft, actualRight)

        assertMaxErrorBelow(actualLeft, expectedLeft, 4e-4f)
        assertMaxErrorBelow(actualRight, expectedRight, 4e-4f)
    }

    @Test
    fun constantStereoRoundTripKeepsLengthChannelsAndFiniteSamples() {
        val left = FloatArray(contract.chunkFrames) { 0.25f }
        val right = FloatArray(contract.chunkFrames) { -0.5f }
        val tensor = FloatArray(contract.tensorElements)
        val restoredLeft = FloatArray(contract.chunkFrames)
        val restoredRight = FloatArray(contract.chunkFrames)
        val dsp = MdxDsp(contract)

        dsp.forward(left, right, tensor)
        dsp.inverse(tensor, restoredLeft, restoredRight)

        assertEquals(contract.chunkFrames, restoredLeft.size)
        assertEquals(contract.chunkFrames, restoredRight.size)
        assertTrue(restoredLeft.all(Float::isFinite))
        assertTrue(restoredRight.all(Float::isFinite))
        assertMaxErrorBelow(restoredLeft, left, 2e-4f)
        assertMaxErrorBelow(restoredRight, right, 2e-4f)
    }

    @Test
    fun crossfadeWindowIsStrictlyPositiveAndOverlapSumsToOne() {
        val generatedFrames = 12
        val overlapFrames = 3
        val strideFrames = generatedFrames - overlapFrames
        val window = MdxDsp.buildCrossfadeWindow(generatedFrames, overlapFrames)

        assertTrue(window.all { it > 0f && it <= 1f })
        for (index in 0 until overlapFrames) {
            val sum = window[strideFrames + index] + window[index]
            assertTrue("overlap sum at $index was $sum", abs(sum - 1f) <= 1e-6f)
        }
    }

    /**
     * Deliberately uses the O(N²) DFT instead of [MixedRadixFft]. This keeps the oracle independent
     * from the optimized implementation and locks periodic Hann, reflect padding, tensor planes,
     * complex sign and the dropped Nyquist bin.
     */
    private fun referenceForward(left: FloatArray, right: FloatArray): FloatArray {
        val destination = FloatArray(contract.tensorElements)
        referenceChannelStft(left, realPlane = 0, imagPlane = 1, destination)
        referenceChannelStft(right, realPlane = 2, imagPlane = 3, destination)
        return destination
    }

    private fun referenceChannelStft(
        samples: FloatArray,
        realPlane: Int,
        imagPlane: Int,
        destination: FloatArray,
    ) {
        val window = periodicHann()
        for (frame in 0 until contract.timeFrames) {
            val sourceStart = frame * contract.hopLength - contract.trimFrames
            for (bin in 0 until contract.frequencyBins) {
                var real = 0.0
                var imag = 0.0
                for (sampleIndex in 0 until contract.nFft) {
                    val sample = reflectedSample(samples, sourceStart + sampleIndex) * window[sampleIndex]
                    val angle = -2.0 * PI * bin * sampleIndex / contract.nFft.toDouble()
                    real += sample * cos(angle)
                    imag += sample * sin(angle)
                }
                destination[tensorIndex(realPlane, bin, frame)] = real.toFloat()
                destination[tensorIndex(imagPlane, bin, frame)] = imag.toFloat()
            }
        }
    }

    private fun referenceInverse(source: FloatArray, left: FloatArray, right: FloatArray) {
        referenceChannelIstft(source, realPlane = 0, imagPlane = 1, left)
        referenceChannelIstft(source, realPlane = 2, imagPlane = 3, right)
    }

    private fun referenceChannelIstft(
        source: FloatArray,
        realPlane: Int,
        imagPlane: Int,
        destination: FloatArray,
    ) {
        val n = contract.nFft
        val window = periodicHann()
        val overlapLength = (contract.timeFrames - 1) * contract.hopLength + n
        val overlapAdd = DoubleArray(overlapLength)
        val envelope = DoubleArray(overlapLength)

        for (frame in 0 until contract.timeFrames) {
            val start = frame * contract.hopLength
            for (sampleIndex in 0 until n) {
                val value = window[sampleIndex]
                envelope[start + sampleIndex] += value * value
            }

            val real = DoubleArray(n)
            val imag = DoubleArray(n)
            for (bin in 0 until contract.frequencyBins) {
                real[bin] = source[tensorIndex(realPlane, bin, frame)].toDouble()
                imag[bin] = source[tensorIndex(imagPlane, bin, frame)].toDouble()
            }
            imag[0] = 0.0
            for (bin in 1 until n / 2) {
                real[n - bin] = real[bin]
                imag[n - bin] = -imag[bin]
            }

            for (sampleIndex in 0 until n) {
                var sample = 0.0
                for (bin in 0 until n) {
                    val angle = 2.0 * PI * bin * sampleIndex / n.toDouble()
                    sample += real[bin] * cos(angle) - imag[bin] * sin(angle)
                }
                sample /= n.toDouble()
                overlapAdd[start + sampleIndex] += sample * window[sampleIndex]
            }
        }

        for (index in destination.indices) {
            val sourceIndex = contract.trimFrames + index
            destination[index] = (overlapAdd[sourceIndex] / (envelope[sourceIndex] + 1e-8)).toFloat()
        }
    }

    private fun periodicHann(): DoubleArray = DoubleArray(contract.nFft) { index ->
        0.5 * (1.0 - cos(2.0 * PI * index.toDouble() / contract.nFft.toDouble()))
    }

    private fun reflectedSample(samples: FloatArray, requestedIndex: Int): Double {
        var index = requestedIndex
        val last = samples.lastIndex
        require(last > 0)
        while (index < 0 || index > last) {
            index = if (index < 0) -index else 2 * last - index
        }
        return samples[index].toDouble()
    }

    private fun tensorIndex(plane: Int, bin: Int, frame: Int): Int =
        ((plane * contract.frequencyBins + bin) * contract.timeFrames) + frame

    private fun assertMaxErrorBelow(actual: FloatArray, expected: FloatArray, threshold: Float) {
        require(actual.size == expected.size)
        val maxError = actual.indices.maxOf { index -> abs(actual[index] - expected[index]) }
        assertTrue("max error $maxError exceeds $threshold", maxError <= threshold)
    }
}
