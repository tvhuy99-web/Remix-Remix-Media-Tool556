package com.aistudio.mediatool.core.ml

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.sin

/** STFT/iSTFT contract used by the official 48 kHz DPDFNet TFLite pipeline. */
internal class DpdfNetDsp {
    private val fft = BluesteinFft(WINDOW_LENGTH)
    private val window = buildVorbisWindow()
    private val analysisReal = FloatArray(WINDOW_LENGTH)
    private val analysisImag = FloatArray(WINDOW_LENGTH)
    private val synthesisReal = FloatArray(WINDOW_LENGTH)
    private val synthesisImag = FloatArray(WINDOW_LENGTH)
    private val overlap = FloatArray(WINDOW_LENGTH)
    private val overlapNorm = FloatArray(WINDOW_LENGTH)

    fun forward(frame: FloatArray, tensor: FloatArray) {
        require(frame.size == WINDOW_LENGTH)
        require(tensor.size == TENSOR_ELEMENTS)
        for (index in 0 until WINDOW_LENGTH) {
            analysisReal[index] = frame[index] * window[index]
            analysisImag[index] = 0f
        }
        fft.forward(analysisReal, analysisImag)
        for (bin in 0 until FREQUENCY_BINS) {
            tensor[bin * 2] = analysisReal[bin] * WINDOW_NORMALIZATION
            tensor[bin * 2 + 1] = analysisImag[bin] * WINDOW_NORMALIZATION
        }
    }

    fun applyAttenuationLimit(
        enhanced: FloatArray,
        alignedNoisy: FloatArray?,
        noisyBlend: Float,
    ) {
        require(enhanced.size == TENSOR_ELEMENTS)
        val reference = alignedNoisy ?: return
        require(reference.size == TENSOR_ELEMENTS)
        val alpha = noisyBlend.coerceIn(0f, 1f)
        val enhancedWeight = 1f - alpha
        for (index in enhanced.indices) {
            enhanced[index] = alpha * reference[index] + enhancedWeight * enhanced[index]
        }
    }

    /** Adds one enhanced spectrum and returns the oldest finalized 10 ms hop. */
    fun inverseHop(tensor: FloatArray, output: FloatArray) {
        require(tensor.size == TENSOR_ELEMENTS)
        require(output.size == HOP_LENGTH)
        Arrays.fill(synthesisReal, 0f)
        Arrays.fill(synthesisImag, 0f)
        for (bin in 0 until FREQUENCY_BINS) {
            synthesisReal[bin] = tensor[bin * 2]
            synthesisImag[bin] = tensor[bin * 2 + 1]
        }
        for (bin in 1 until FREQUENCY_BINS - 1) {
            val mirror = WINDOW_LENGTH - bin
            synthesisReal[mirror] = synthesisReal[bin]
            synthesisImag[mirror] = -synthesisImag[bin]
        }
        fft.inverse(synthesisReal, synthesisImag)

        for (index in 0 until WINDOW_LENGTH) {
            val windowValue = window[index]
            overlap[index] += synthesisReal[index] * windowValue / WINDOW_NORMALIZATION
            overlapNorm[index] += windowValue * windowValue
        }
        finalizeOldestHop(output)
    }

    /** Emits the final overlap tail after the last spectrum. */
    fun flushHop(output: FloatArray) {
        require(output.size == HOP_LENGTH)
        finalizeOldestHop(output)
    }

    private fun finalizeOldestHop(output: FloatArray) {
        for (index in 0 until HOP_LENGTH) {
            val norm = overlapNorm[index]
            output[index] = if (norm > MIN_WINDOW_NORM) overlap[index] / norm else 0f
        }
        System.arraycopy(overlap, HOP_LENGTH, overlap, 0, WINDOW_LENGTH - HOP_LENGTH)
        System.arraycopy(overlapNorm, HOP_LENGTH, overlapNorm, 0, WINDOW_LENGTH - HOP_LENGTH)
        Arrays.fill(overlap, WINDOW_LENGTH - HOP_LENGTH, WINDOW_LENGTH, 0f)
        Arrays.fill(overlapNorm, WINDOW_LENGTH - HOP_LENGTH, WINDOW_LENGTH, 0f)
    }

    internal fun windowSnapshot(): FloatArray = window.copyOf()

    companion object {
        const val SAMPLE_RATE = 48_000
        const val WINDOW_LENGTH = 960
        const val HOP_LENGTH = 480
        const val FREQUENCY_BINS = WINDOW_LENGTH / 2 + 1
        const val TENSOR_ELEMENTS = FREQUENCY_BINS * 2
        const val MODEL_ADVANCE_SAMPLES = WINDOW_LENGTH * 2
        const val CENTER_PADDING_SAMPLES = WINDOW_LENGTH / 2
        const val INPUT_TAIL_PADDING_SAMPLES = WINDOW_LENGTH
        private const val WINDOW_NORMALIZATION = 1f / 960f
        private const val MIN_WINDOW_NORM = 1e-8f

        internal fun buildVorbisWindow(): FloatArray = FloatArray(WINDOW_LENGTH) { index ->
            val inner = sin(0.5 * PI * (index + 0.5) / (WINDOW_LENGTH / 2.0))
            sin(0.5 * PI * inner * inner).toFloat()
        }
    }
}
