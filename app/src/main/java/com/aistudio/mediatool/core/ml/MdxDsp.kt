package com.aistudio.mediatool.core.ml

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/** Host-side STFT/iSTFT used by the UVR MDX-Net LiteRT graph. */
internal class MdxDsp(private val contract: MdxSpectrogramContract) {
    private val n = contract.nFft
    private val hop = contract.hopLength
    private val bins = contract.frequencyBins
    private val timeFrames = contract.timeFrames
    private val chunkFrames = contract.chunkFrames
    private val trim = contract.trimFrames
    private val olaLength = (timeFrames - 1) * hop + n

    private val fft = MixedRadixFft(n)
    private val hann = FloatArray(n) { index ->
        (0.5 * (1.0 - cos(2.0 * PI * index.toDouble() / n.toDouble()))).toFloat()
    }
    private val hannSquared = FloatArray(n) { index -> hann[index] * hann[index] }
    private val fftReal = FloatArray(n)
    private val fftImag = FloatArray(n)
    private val overlapAdd = FloatArray(olaLength)
    private val envelope = FloatArray(olaLength).also { destination ->
        for (frame in 0 until timeFrames) {
            val start = frame * hop
            for (i in 0 until n) destination[start + i] += hannSquared[i]
        }
    }

    init {
        require(olaLength == chunkFrames + 2 * trim)
    }

    fun forward(left: FloatArray, right: FloatArray, destination: FloatArray) {
        require(left.size == chunkFrames && right.size == chunkFrames)
        require(destination.size == contract.tensorElements)
        channelStft(left, realPlane = 0, imagPlane = 1, destination)
        channelStft(right, realPlane = 2, imagPlane = 3, destination)
    }

    fun inverse(source: FloatArray, left: FloatArray, right: FloatArray) {
        require(source.size == contract.tensorElements)
        require(left.size == chunkFrames && right.size == chunkFrames)
        channelIstft(source, realPlane = 0, imagPlane = 1, left)
        channelIstft(source, realPlane = 2, imagPlane = 3, right)
    }

    private fun channelStft(
        samples: FloatArray,
        realPlane: Int,
        imagPlane: Int,
        destination: FloatArray,
    ) {
        for (frame in 0 until timeFrames) {
            val sourceStart = frame * hop - trim
            for (i in 0 until n) {
                fftReal[i] = reflectedSample(samples, sourceStart + i) * hann[i]
                fftImag[i] = 0f
            }
            fft.forward(fftReal, fftImag)
            for (bin in 0 until bins) {
                destination[tensorIndex(realPlane, bin, frame)] = fftReal[bin]
                destination[tensorIndex(imagPlane, bin, frame)] = fftImag[bin]
            }
        }
    }

    private fun channelIstft(
        source: FloatArray,
        realPlane: Int,
        imagPlane: Int,
        destination: FloatArray,
    ) {
        Arrays.fill(overlapAdd, 0f)
        for (frame in 0 until timeFrames) {
            Arrays.fill(fftReal, 0f)
            Arrays.fill(fftImag, 0f)
            for (bin in 0 until bins) {
                fftReal[bin] = source[tensorIndex(realPlane, bin, frame)]
                fftImag[bin] = source[tensorIndex(imagPlane, bin, frame)]
            }
            // Real-valued inverse: restore conjugate bins. The dropped Nyquist bin n/2 stays zero.
            fftImag[0] = 0f
            for (bin in 1 until n / 2) {
                fftReal[n - bin] = fftReal[bin]
                fftImag[n - bin] = -fftImag[bin]
            }
            fft.inverse(fftReal, fftImag)
            val start = frame * hop
            for (i in 0 until n) overlapAdd[start + i] += fftReal[i] * hann[i]
        }

        for (i in 0 until chunkFrames) {
            val sourceIndex = trim + i
            destination[i] = overlapAdd[sourceIndex] / (envelope[sourceIndex] + 1e-8f)
        }
    }

    private fun tensorIndex(plane: Int, bin: Int, frame: Int): Int =
        ((plane * bins + bin) * timeFrames) + frame

    private fun reflectedSample(samples: FloatArray, requestedIndex: Int): Float {
        var index = requestedIndex
        val last = samples.lastIndex
        require(last > 0)
        while (index < 0 || index > last) {
            index = if (index < 0) -index else 2 * last - index
        }
        return samples[index]
    }

    companion object {
        /** Reference trapezoid. Strictly positive at both outer edges. */
        fun buildCrossfadeWindow(generatedFrames: Int, overlapFrames: Int): FloatArray {
            require(generatedFrames > 0)
            require(overlapFrames in 0 until generatedFrames)
            if (overlapFrames == 0) return FloatArray(generatedFrames) { 1f }
            val denominator = (overlapFrames + 1).toFloat()
            return FloatArray(generatedFrames) { index ->
                val up = (index + 1).toFloat() / denominator
                val down = (generatedFrames - index).toFloat() / denominator
                min(1f, min(up, down))
            }
        }
    }
}
