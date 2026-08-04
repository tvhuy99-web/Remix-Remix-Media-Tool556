package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Deterministic Gaussian noise used to reproduce Kaldi's 1-LSB frontend dither parameter. */
internal class MossFormer2Dither(seed: Long) {
    private var state = if (seed != 0L) seed else DEFAULT_SEED
    private var spare = 0.0
    private var hasSpare = false

    fun nextGaussian(): Float {
        if (hasSpare) {
            hasSpare = false
            return spare.toFloat()
        }
        val u1 = nextUnit().coerceAtLeast(MIN_POSITIVE_UNIT)
        val u2 = nextUnit()
        val radius = sqrt(-2.0 * ln(u1))
        val angle = 2.0 * PI * u2
        spare = radius * kotlin.math.sin(angle)
        hasSpare = true
        return (radius * cos(angle)).toFloat()
    }

    private fun nextUnit(): Double {
        var value = state
        value = value xor (value ushr 12)
        value = value xor (value shl 25)
        value = value xor (value ushr 27)
        state = value
        val bits = (value * 2_685_821_657_736_338_717L) ushr 11
        return bits.toDouble() * (1.0 / (1L shl 53).toDouble())
    }

    private companion object {
        const val DEFAULT_SEED = -7_046_029_254_386_353_131L
        const val MIN_POSITIVE_UNIT = 1.0e-300
    }
}
