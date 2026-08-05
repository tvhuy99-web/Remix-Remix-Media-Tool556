package com.aistudio.mediatool.core.ml

import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming window/counter overlap-add accumulator.
 *
 * It supports arbitrary overlap, including MDX23C's 75% overlap where more than two chunks can
 * contribute to the same frame. Only the region that may still receive a future contribution is
 * retained in memory.
 */
internal class MdxOverlapAddWriter(
    private val output: DataOutputStream,
    private val totalFrames: Long,
    private val generatedFrames: Int,
    private val strideFrames: Int,
    private val window: FloatArray,
    private val compensation: Float,
    private val discardLeadingFrames: Long = 0L,
) : AutoCloseable {
    private val pendingLeft = FloatArray(generatedFrames)
    private val pendingRight = FloatArray(generatedFrames)
    private val pendingEnvelope = FloatArray(generatedFrames)
    private val byteBuffer = ByteBuffer
        .allocate(8 * 8192)
        .order(ByteOrder.LITTLE_ENDIAN)

    private var pendingCount = 0
    private var chunks = 0
    private var timelineFrames = 0L
    private var writtenFrames = 0L
    private var finished = false

    init {
        require(totalFrames > 0L)
        require(generatedFrames > 0)
        require(strideFrames in 1..generatedFrames)
        require(window.size == generatedFrames)
        require(window.all { it.isFinite() && it > 0f })
        require(compensation.isFinite() && compensation > 0f)
        require(discardLeadingFrames >= 0L)
    }

    fun append(leftChunk: FloatArray, rightChunk: FloatArray, centralOffset: Int) {
        check(!finished)
        require(centralOffset >= 0)
        require(centralOffset + generatedFrames <= leftChunk.size)
        require(centralOffset + generatedFrames <= rightChunk.size)

        if (chunks > 0) flushPrefix(strideFrames)
        for (index in 0 until generatedFrames) {
            val weight = window[index]
            pendingLeft[index] += leftChunk[centralOffset + index] * weight
            pendingRight[index] += rightChunk[centralOffset + index] * weight
            pendingEnvelope[index] += weight
        }
        pendingCount = maxOf(pendingCount, generatedFrames)
        chunks++
    }

    fun finish() {
        if (finished) return
        check(chunks > 0) { "MDX overlap-add received no chunks" }
        flushPrefix(pendingCount)
        flushBuffer()
        check(writtenFrames == totalFrames) {
            "MDX overlap-add wrote $writtenFrames/$totalFrames frames"
        }
        finished = true
    }

    override fun close() {
        val availableTimelineFrames = if (chunks == 0) {
            0L
        } else {
            (chunks - 1L) * strideFrames + generatedFrames
        }
        val requiredTimelineFrames = discardLeadingFrames + totalFrames
        if (!finished && chunks > 0 && availableTimelineFrames >= requiredTimelineFrames) {
            finish()
        } else {
            runCatching(::flushBuffer)
        }
        output.close()
    }

    private fun flushPrefix(requestedFrames: Int) {
        val count = minOf(requestedFrames, pendingCount)
        for (index in 0 until count) {
            val denominator = pendingEnvelope[index]
            emitTimelineFrame(
                pendingLeft[index] / (denominator + 1e-8f),
                pendingRight[index] / (denominator + 1e-8f),
            )
        }

        val remaining = pendingCount - count
        if (remaining > 0) {
            System.arraycopy(pendingLeft, count, pendingLeft, 0, remaining)
            System.arraycopy(pendingRight, count, pendingRight, 0, remaining)
            System.arraycopy(pendingEnvelope, count, pendingEnvelope, 0, remaining)
        }
        java.util.Arrays.fill(pendingLeft, remaining, pendingLeft.size, 0f)
        java.util.Arrays.fill(pendingRight, remaining, pendingRight.size, 0f)
        java.util.Arrays.fill(pendingEnvelope, remaining, pendingEnvelope.size, 0f)
        pendingCount = remaining
    }

    private fun emitTimelineFrame(left: Float, right: Float) {
        val outputEnd = discardLeadingFrames + totalFrames
        if (timelineFrames in discardLeadingFrames until outputEnd) {
            writeFrame(left, right)
        }
        timelineFrames++
    }

    private fun writeFrame(left: Float, right: Float) {
        if (writtenFrames >= totalFrames) return
        if (byteBuffer.remaining() < 8) flushBuffer()
        byteBuffer.putFloat(finiteOrZero(left) * compensation)
        byteBuffer.putFloat(finiteOrZero(right) * compensation)
        writtenFrames++
    }

    private fun flushBuffer() {
        if (byteBuffer.position() == 0) return
        output.write(byteBuffer.array(), 0, byteBuffer.position())
        byteBuffer.clear()
    }

    private fun finiteOrZero(value: Float): Float = if (value.isFinite()) value else 0f
}
