package com.aistudio.mediatool.core.ml

import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming equivalent of the reference full-song weighted overlap-add accumulator.
 * Only the tail that can still receive a contribution from the next chunk is retained.
 */
internal class MdxOverlapAddWriter(
    private val output: DataOutputStream,
    private val totalFrames: Long,
    private val generatedFrames: Int,
    private val strideFrames: Int,
    private val window: FloatArray,
    private val compensation: Float,
) : AutoCloseable {
    private val overlapFrames = generatedFrames - strideFrames
    private val pendingLeft = FloatArray(overlapFrames)
    private val pendingRight = FloatArray(overlapFrames)
    private val pendingEnvelope = FloatArray(overlapFrames)
    private val byteBuffer = ByteBuffer
        .allocate(8 * 8192)
        .order(ByteOrder.LITTLE_ENDIAN)
    private var chunks = 0
    private var writtenFrames = 0L
    private var finished = false

    init {
        require(totalFrames > 0L)
        require(generatedFrames > 0)
        require(strideFrames in 1..generatedFrames)
        require(window.size == generatedFrames)
        require(compensation.isFinite() && compensation > 0f)
    }

    fun append(leftChunk: FloatArray, rightChunk: FloatArray, centralOffset: Int) {
        check(!finished)
        require(centralOffset >= 0)
        require(centralOffset + generatedFrames <= leftChunk.size)
        require(centralOffset + generatedFrames <= rightChunk.size)

        if (chunks == 0) {
            writeDirect(leftChunk, rightChunk, centralOffset, strideFrames)
        } else {
            for (i in 0 until overlapFrames) {
                val currentWeight = window[i]
                val denominator = pendingEnvelope[i] + currentWeight
                writeFrame(
                    (pendingLeft[i] + leftChunk[centralOffset + i] * currentWeight) / (denominator + 1e-8f),
                    (pendingRight[i] + rightChunk[centralOffset + i] * currentWeight) / (denominator + 1e-8f),
                )
            }
            writeDirect(
                leftChunk,
                rightChunk,
                centralOffset + overlapFrames,
                strideFrames - overlapFrames,
            )
        }

        for (i in 0 until overlapFrames) {
            val localIndex = strideFrames + i
            val weight = window[localIndex]
            pendingLeft[i] = leftChunk[centralOffset + localIndex] * weight
            pendingRight[i] = rightChunk[centralOffset + localIndex] * weight
            pendingEnvelope[i] = weight
        }
        chunks++
    }

    fun finish() {
        if (finished) return
        check(chunks > 0) { "MDX overlap-add received no chunks" }
        for (i in 0 until overlapFrames) {
            val denominator = pendingEnvelope[i]
            writeFrame(
                pendingLeft[i] / (denominator + 1e-8f),
                pendingRight[i] / (denominator + 1e-8f),
            )
        }
        flushBuffer()
        check(writtenFrames == totalFrames) {
            "MDX overlap-add wrote $writtenFrames/$totalFrames frames"
        }
        finished = true
    }

    /**
     * A complete use block is finalized automatically. An interrupted block only flushes samples
     * that were already committed, so cleanup cannot hide the original cancellation or inference error.
     */
    override fun close() {
        val availableAfterTail = chunks.toLong() * strideFrames + overlapFrames
        if (!finished && chunks > 0 && availableAfterTail >= totalFrames) {
            finish()
        } else {
            runCatching(::flushBuffer)
        }
        output.close()
    }

    private fun writeDirect(
        left: FloatArray,
        right: FloatArray,
        offset: Int,
        count: Int,
    ) {
        if (count <= 0) return
        for (i in 0 until count) writeFrame(left[offset + i], right[offset + i])
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
