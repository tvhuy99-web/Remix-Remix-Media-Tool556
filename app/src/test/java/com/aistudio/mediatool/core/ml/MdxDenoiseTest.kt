package com.aistudio.mediatool.core.ml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MdxDenoiseTest {
    @Test
    fun combinesPositiveAndNegativePredictionsInPlace() {
        val positive = floatArrayOf(4f, 1f, -2f)
        val negative = floatArrayOf(2f, -1f, 2f)

        val result = MdxDenoise.combineInPlace(positive, negative)

        assertSame(positive, result)
        assertArrayEquals(floatArrayOf(1f, 1f, -2f), result, 0f)
    }
}
