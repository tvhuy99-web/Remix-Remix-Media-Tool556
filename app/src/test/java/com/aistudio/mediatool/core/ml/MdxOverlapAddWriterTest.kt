package com.aistudio.mediatool.core.ml

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdxOverlapAddWriterTest {
    @Test
    fun streamingWriterMatchesReferenceAccumulator() {
        val generated = 10
        val stride = 7
        val overlap = generated - stride
        val chunks = 3
        val totalFrames = (chunks - 1) * stride + generated - 2
        val window = MdxDsp.buildCrossfadeWindow(generated, overlap)
        val leftChunks = List(chunks) { chunk -> FloatArray(generated + 4) { index -> chunk * 10f + index } }
        val rightChunks = List(chunks) { chunk -> FloatArray(generated + 4) { index -> -(chunk * 10f + index) } }
        val referenceLeft = FloatArray((chunks - 1) * stride + generated)
        val referenceRight = FloatArray(referenceLeft.size)
        val envelope = FloatArray(referenceLeft.size)
        for (chunk in 0 until chunks) {
            val start = chunk * stride
            for (i in 0 until generated) {
                referenceLeft[start + i] += leftChunks[chunk][2 + i] * window[i]
                referenceRight[start + i] += rightChunks[chunk][2 + i] * window[i]
                envelope[start + i] += window[i]
            }
        }
        for (i in referenceLeft.indices) {
            referenceLeft[i] /= envelope[i] + 1e-8f
            referenceRight[i] /= envelope[i] + 1e-8f
        }

        val bytes = ByteArrayOutputStream()
        MdxOverlapAddWriter(
            output = DataOutputStream(bytes),
            totalFrames = totalFrames.toLong(),
            generatedFrames = generated,
            strideFrames = stride,
            window = window,
            compensation = 1f,
        ).use { writer ->
            for (chunk in 0 until chunks) writer.append(leftChunks[chunk], rightChunks[chunk], 2)
        }

        val result = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals(totalFrames * 2, result.remaining())
        var maxError = 0f
        for (frame in 0 until totalFrames) {
            maxError = maxOf(maxError, abs(result.get(frame * 2) - referenceLeft[frame]))
            maxError = maxOf(maxError, abs(result.get(frame * 2 + 1) - referenceRight[frame]))
        }
        assertTrue("max error $maxError", maxError <= 1e-5f)
    }

    @Test
    fun supportsSeventyFivePercentOverlapAndTrimsReflectPadding() {
        val generated = 16
        val stride = 4
        val chunks = 7
        val discard = 12
        val totalFrames = 16
        val window = MdxDsp.buildCrossfadeWindow(generated, 3)
        val timelineLength = (chunks - 1) * stride + generated
        val leftChunks = List(chunks) { chunk ->
            FloatArray(generated) { local -> (chunk * stride + local).toFloat() }
        }
        val rightChunks = List(chunks) { chunk ->
            FloatArray(generated) { local -> -(chunk * stride + local).toFloat() }
        }
        assertTrue(timelineLength >= discard + totalFrames)

        val bytes = ByteArrayOutputStream()
        MdxOverlapAddWriter(
            output = DataOutputStream(bytes),
            totalFrames = totalFrames.toLong(),
            generatedFrames = generated,
            strideFrames = stride,
            window = window,
            compensation = 1f,
            discardLeadingFrames = discard.toLong(),
        ).use { writer ->
            for (chunk in 0 until chunks) writer.append(leftChunks[chunk], rightChunks[chunk], 0)
        }

        val result = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        assertEquals(totalFrames * 2, result.remaining())
        for (frame in 0 until totalFrames) {
            val expected = (discard + frame).toFloat()
            assertEquals(expected, result.get(frame * 2), 1e-5f)
            assertEquals(-expected, result.get(frame * 2 + 1), 1e-5f)
        }
    }

    @Test
    fun crossfadeWindowIsStrictlyPositiveAndSymmetric() {
        val window = MdxDsp.buildCrossfadeWindow(100, 10)
        assertTrue(window.all { it > 0f && it <= 1f })
        for (i in window.indices) assertEquals(window[i], window[window.lastIndex - i], 0f)
    }
}
