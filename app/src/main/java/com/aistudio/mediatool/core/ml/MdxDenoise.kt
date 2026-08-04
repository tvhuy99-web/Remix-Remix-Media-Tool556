package com.aistudio.mediatool.core.ml

/** Reference MDX denoise combination: 0.5 * model(x) - 0.5 * model(-x). */
internal object MdxDenoise {
    fun combineInPlace(positive: FloatArray, negative: FloatArray): FloatArray {
        require(positive.size == negative.size) { "Hai tensor denoise phải cùng kích thước" }
        for (index in positive.indices) {
            val value = 0.5f * (positive[index] - negative[index])
            positive[index] = if (value.isFinite()) value else 0f
        }
        return positive
    }
}
