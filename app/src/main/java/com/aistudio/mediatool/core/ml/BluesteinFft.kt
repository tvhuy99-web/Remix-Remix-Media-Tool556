package com.aistudio.mediatool.core.ml

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Allocation-free complex Bluestein FFT for transform lengths not handled directly by MixedRadixFft. */
internal class BluesteinFft(private val size: Int) {
    private val convolutionSize = nextPowerOfTwo(2 * size - 1)
    private val convolutionFft = MixedRadixFft(convolutionSize)
    private val chirpReal = FloatArray(size)
    private val chirpImag = FloatArray(size)
    private val kernelFftReal = FloatArray(convolutionSize)
    private val kernelFftImag = FloatArray(convolutionSize)
    private val workReal = FloatArray(convolutionSize)
    private val workImag = FloatArray(convolutionSize)

    init {
        require(size > 0)
        for (index in 0 until size) {
            val angle = PI * index.toDouble() * index.toDouble() / size.toDouble()
            val real = cos(angle).toFloat()
            val imag = sin(angle).toFloat()
            chirpReal[index] = real
            chirpImag[index] = imag
            kernelFftReal[index] = real
            kernelFftImag[index] = imag
            if (index != 0) {
                kernelFftReal[convolutionSize - index] = real
                kernelFftImag[convolutionSize - index] = imag
            }
        }
        convolutionFft.forward(kernelFftReal, kernelFftImag)
    }

    fun forward(real: FloatArray, imag: FloatArray) {
        require(real.size >= size && imag.size >= size)
        Arrays.fill(workReal, 0f)
        Arrays.fill(workImag, 0f)
        for (index in 0 until size) {
            val inputReal = real[index]
            val inputImag = imag[index]
            val chirpR = chirpReal[index]
            val chirpI = chirpImag[index]
            workReal[index] = inputReal * chirpR + inputImag * chirpI
            workImag[index] = inputImag * chirpR - inputReal * chirpI
        }

        convolutionFft.forward(workReal, workImag)
        for (index in 0 until convolutionSize) {
            val leftReal = workReal[index]
            val leftImag = workImag[index]
            val rightReal = kernelFftReal[index]
            val rightImag = kernelFftImag[index]
            workReal[index] = leftReal * rightReal - leftImag * rightImag
            workImag[index] = leftReal * rightImag + leftImag * rightReal
        }
        convolutionFft.inverse(workReal, workImag)

        for (index in 0 until size) {
            val valueReal = workReal[index]
            val valueImag = workImag[index]
            val chirpR = chirpReal[index]
            val chirpI = chirpImag[index]
            real[index] = valueReal * chirpR + valueImag * chirpI
            imag[index] = valueImag * chirpR - valueReal * chirpI
        }
    }

    fun inverse(real: FloatArray, imag: FloatArray) {
        require(real.size >= size && imag.size >= size)
        for (index in 0 until size) imag[index] = -imag[index]
        forward(real, imag)
        val scale = 1f / size.toFloat()
        for (index in 0 until size) {
            real[index] *= scale
            imag[index] = -imag[index] * scale
        }
    }

    private companion object {
        fun nextPowerOfTwo(value: Int): Int {
            require(value > 0)
            var result = 1
            while (result < value) result = result shl 1
            return result
        }
    }
}
