package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Allocation-free complex FFT for the MDX transform sizes used by this project.
 *
 * 4096 is handled by an iterative radix-2 transform. 6144 is factored as 3 × 2048: three radix-2
 * transforms followed by one radix-3 butterfly per frequency bin. Keeping this implementation local
 * avoids Android-incompatible large-array/Unsafe dependencies and makes the DSP parity testable.
 */
internal class MixedRadixFft(private val size: Int) {
    private val radix3Length = if (size % 3 == 0 && isPowerOfTwo(size / 3)) size / 3 else 0
    private val workReal = if (radix3Length > 0) FloatArray(size) else FloatArray(0)
    private val workImag = if (radix3Length > 0) FloatArray(size) else FloatArray(0)

    init {
        require(isPowerOfTwo(size) || radix3Length > 0) {
            "FFT size $size must be a power of two or 3 × a power of two"
        }
    }

    fun forward(real: FloatArray, imag: FloatArray) {
        require(real.size >= size && imag.size >= size)
        if (radix3Length == 0) {
            radix2(real, imag, offset = 0, length = size)
        } else {
            forwardRadix3(real, imag)
        }
    }

    fun inverse(real: FloatArray, imag: FloatArray) {
        require(real.size >= size && imag.size >= size)
        for (i in 0 until size) imag[i] = -imag[i]
        forward(real, imag)
        val scale = 1f / size.toFloat()
        for (i in 0 until size) {
            real[i] *= scale
            imag[i] = -imag[i] * scale
        }
    }

    private fun forwardRadix3(real: FloatArray, imag: FloatArray) {
        val m = radix3Length
        for (branch in 0..2) {
            val offset = branch * m
            for (index in 0 until m) {
                val source = branch + 3 * index
                workReal[offset + index] = real[source]
                workImag[offset + index] = imag[source]
            }
            radix2(workReal, workImag, offset, m)
        }

        val root3 = sqrt(3.0).toFloat() / 2f
        val twoPiOverN = 2.0 * PI / size.toDouble()
        for (k in 0 until m) {
            val a0r = workReal[k]
            val a0i = workImag[k]

            val theta = twoPiOverN * k.toDouble()
            val c1 = cos(theta).toFloat()
            val s1 = sin(theta).toFloat()
            val a1r = workReal[m + k]
            val a1i = workImag[m + k]
            val b1r = a1r * c1 + a1i * s1
            val b1i = a1i * c1 - a1r * s1

            val c2 = cos(2.0 * theta).toFloat()
            val s2 = sin(2.0 * theta).toFloat()
            val a2r = workReal[2 * m + k]
            val a2i = workImag[2 * m + k]
            val b2r = a2r * c2 + a2i * s2
            val b2i = a2i * c2 - a2r * s2

            // k1 = 0
            real[k] = a0r + b1r + b2r
            imag[k] = a0i + b1i + b2i

            // k1 = 1: B1·exp(-j2π/3) + B2·exp(-j4π/3)
            real[m + k] = a0r - 0.5f * b1r + root3 * b1i - 0.5f * b2r - root3 * b2i
            imag[m + k] = a0i - root3 * b1r - 0.5f * b1i + root3 * b2r - 0.5f * b2i

            // k1 = 2: B1·exp(-j4π/3) + B2·exp(-j2π/3)
            real[2 * m + k] = a0r - 0.5f * b1r - root3 * b1i - 0.5f * b2r + root3 * b2i
            imag[2 * m + k] = a0i + root3 * b1r - 0.5f * b1i - root3 * b2r - 0.5f * b2i
        }
    }

    private fun radix2(real: FloatArray, imag: FloatArray, offset: Int, length: Int) {
        var j = 0
        for (i in 1 until length) {
            var bit = length shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val ri = real[offset + i]
                real[offset + i] = real[offset + j]
                real[offset + j] = ri
                val ii = imag[offset + i]
                imag[offset + i] = imag[offset + j]
                imag[offset + j] = ii
            }
        }

        var block = 2
        while (block <= length) {
            val angle = -2.0 * PI / block.toDouble()
            val stepReal = cos(angle).toFloat()
            val stepImag = sin(angle).toFloat()
            val half = block / 2
            var start = 0
            while (start < length) {
                var twiddleReal = 1f
                var twiddleImag = 0f
                for (k in 0 until half) {
                    val even = offset + start + k
                    val odd = even + half
                    val oddReal = real[odd] * twiddleReal - imag[odd] * twiddleImag
                    val oddImag = real[odd] * twiddleImag + imag[odd] * twiddleReal
                    val evenReal = real[even]
                    val evenImag = imag[even]
                    real[even] = evenReal + oddReal
                    imag[even] = evenImag + oddImag
                    real[odd] = evenReal - oddReal
                    imag[odd] = evenImag - oddImag

                    val nextReal = twiddleReal * stepReal - twiddleImag * stepImag
                    twiddleImag = twiddleReal * stepImag + twiddleImag * stepReal
                    twiddleReal = nextReal
                }
                start += block
            }
            block = block shl 1
        }
    }

    private companion object {
        fun isPowerOfTwo(value: Int): Boolean = value > 0 && value and (value - 1) == 0
    }
}
