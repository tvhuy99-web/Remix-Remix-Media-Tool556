package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Allocation-free complex FFT for the MDX transform sizes used by this project.
 *
 * 4096 is handled by an iterative radix-2 transform. 6144 is factored as 3 × 2048: three radix-2
 * transforms followed by one radix-3 butterfly per frequency bin. Bit-reversal indices and every
 * twiddle used by both stages are precomputed once, keeping transcendental functions out of the
 * per-frame STFT/iSTFT hot path.
 */
internal class MixedRadixFft(private val size: Int) {
    private val radix3Length = if (size % 3 == 0 && isPowerOfTwo(size / 3)) size / 3 else 0
    private val radix2Length = if (radix3Length > 0) radix3Length else size
    private val workReal = if (radix3Length > 0) FloatArray(size) else FloatArray(0)
    private val workImag = if (radix3Length > 0) FloatArray(size) else FloatArray(0)

    private val bitReversed = IntArray(radix2Length) { index ->
        Integer.reverse(index) ushr (Int.SIZE_BITS - Integer.numberOfTrailingZeros(radix2Length))
    }
    private val radix2StageOffsets = IntArray(Integer.numberOfTrailingZeros(radix2Length))
    private val radix2TwiddleReal = FloatArray(radix2Length - 1)
    private val radix2TwiddleImag = FloatArray(radix2Length - 1)

    private val radix3Twiddle1Real = if (radix3Length > 0) FloatArray(radix3Length) else FloatArray(0)
    private val radix3Twiddle1Imag = if (radix3Length > 0) FloatArray(radix3Length) else FloatArray(0)
    private val radix3Twiddle2Real = if (radix3Length > 0) FloatArray(radix3Length) else FloatArray(0)
    private val radix3Twiddle2Imag = if (radix3Length > 0) FloatArray(radix3Length) else FloatArray(0)

    init {
        require(isPowerOfTwo(size) || radix3Length > 0) {
            "FFT size $size must be a power of two or 3 × a power of two"
        }
        precomputeRadix2Tables()
        precomputeRadix3Tables()
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

        for (k in 0 until m) {
            val a0r = workReal[k]
            val a0i = workImag[k]

            val c1 = radix3Twiddle1Real[k]
            val s1 = radix3Twiddle1Imag[k]
            val a1r = workReal[m + k]
            val a1i = workImag[m + k]
            val b1r = a1r * c1 + a1i * s1
            val b1i = a1i * c1 - a1r * s1

            val c2 = radix3Twiddle2Real[k]
            val s2 = radix3Twiddle2Imag[k]
            val a2r = workReal[2 * m + k]
            val a2i = workImag[2 * m + k]
            val b2r = a2r * c2 + a2i * s2
            val b2i = a2i * c2 - a2r * s2

            real[k] = a0r + b1r + b2r
            imag[k] = a0i + b1i + b2i

            real[m + k] = a0r - 0.5f * b1r + ROOT3_OVER_2 * b1i - 0.5f * b2r - ROOT3_OVER_2 * b2i
            imag[m + k] = a0i - ROOT3_OVER_2 * b1r - 0.5f * b1i + ROOT3_OVER_2 * b2r - 0.5f * b2i

            real[2 * m + k] = a0r - 0.5f * b1r - ROOT3_OVER_2 * b1i - 0.5f * b2r + ROOT3_OVER_2 * b2i
            imag[2 * m + k] = a0i + ROOT3_OVER_2 * b1r - 0.5f * b1i - ROOT3_OVER_2 * b2r - 0.5f * b2i
        }
    }

    private fun radix2(real: FloatArray, imag: FloatArray, offset: Int, length: Int) {
        require(length == radix2Length)
        for (i in 0 until length) {
            val j = bitReversed[i]
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
        var stage = 0
        while (block <= length) {
            val half = block / 2
            val tableOffset = radix2StageOffsets[stage]
            var start = 0
            while (start < length) {
                for (k in 0 until half) {
                    val twiddleReal = radix2TwiddleReal[tableOffset + k]
                    val twiddleImag = radix2TwiddleImag[tableOffset + k]
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
                }
                start += block
            }
            block = block shl 1
            stage++
        }
    }

    private fun precomputeRadix2Tables() {
        var block = 2
        var stage = 0
        var tableOffset = 0
        while (block <= radix2Length) {
            radix2StageOffsets[stage] = tableOffset
            val half = block / 2
            for (k in 0 until half) {
                val angle = -2.0 * PI * k.toDouble() / block.toDouble()
                radix2TwiddleReal[tableOffset + k] = cos(angle).toFloat()
                radix2TwiddleImag[tableOffset + k] = sin(angle).toFloat()
            }
            tableOffset += half
            block = block shl 1
            stage++
        }
        check(tableOffset == radix2TwiddleReal.size)
    }

    private fun precomputeRadix3Tables() {
        if (radix3Length == 0) return
        val twoPiOverN = 2.0 * PI / size.toDouble()
        for (k in 0 until radix3Length) {
            val theta = twoPiOverN * k.toDouble()
            radix3Twiddle1Real[k] = cos(theta).toFloat()
            radix3Twiddle1Imag[k] = sin(theta).toFloat()
            radix3Twiddle2Real[k] = cos(2.0 * theta).toFloat()
            radix3Twiddle2Imag[k] = sin(2.0 * theta).toFloat()
        }
    }

    private companion object {
        val ROOT3_OVER_2: Float = sqrt(3.0).toFloat() / 2f

        fun isPowerOfTwo(value: Int): Boolean = value > 0 && value and (value - 1) == 0
    }
}
