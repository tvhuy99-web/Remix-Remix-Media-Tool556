package com.aistudio.mediatool.core.ml

import kotlin.math.PI
import kotlin.math.sin

data class OverlapWeights(
    val previous: Float,
    val current: Float,
)

/** Cửa sổ bổ sung: hai trọng số luôn có tổng bằng 1 để tránh tăng biên độ. */
object OverlapWindow {
    fun weights(index: Int, size: Int): OverlapWeights {
        require(size > 0) { "Kích thước overlap phải lớn hơn 0" }
        require(index in 0 until size) { "Chỉ số overlap nằm ngoài phạm vi" }
        if (size == 1) return OverlapWeights(previous = 0f, current = 1f)

        val position = index.toDouble() / (size - 1).toDouble()
        val current = sin(position * PI / 2.0).let { it * it }.toFloat().coerceIn(0f, 1f)
        return OverlapWeights(previous = 1f - current, current = current)
    }

    fun weights(index: Int, chunking: ChunkingSpec): OverlapWeights =
        when (chunking.overlapProfile) {
            OverlapProfile.COMPLEMENTARY_SINE -> weights(index, chunking.overlapFrames)
            OverlapProfile.REFERENCE_LINEAR_WINDOW -> referenceWeights(index, chunking)
        }

    private fun referenceWeights(index: Int, chunking: ChunkingSpec): OverlapWeights {
        require(index in 0 until chunking.overlapFrames) { "Chỉ số overlap nằm ngoài phạm vi" }
        val previousPosition = chunking.stepFrames + index
        val currentPosition = index
        val previousRaw = linearEdgeWindow(
            previousPosition,
            chunking.frames,
            chunking.edgeFadeFrames,
        )
        val currentRaw = linearEdgeWindow(
            currentPosition,
            chunking.frames,
            chunking.edgeFadeFrames,
        )
        val total = previousRaw + currentRaw
        if (total <= 1e-12f) return weights(index, chunking.overlapFrames)
        return OverlapWeights(previous = previousRaw / total, current = currentRaw / total)
    }

    private fun linearEdgeWindow(position: Int, frames: Int, fadeFrames: Int): Float {
        require(position in 0 until frames)
        if (fadeFrames == 1) return if (position == 0 || position == frames - 1) 0f else 1f
        return when {
            position < fadeFrames -> position.toFloat() / (fadeFrames - 1).toFloat()
            position >= frames - fadeFrames -> (frames - 1 - position).toFloat() / (fadeFrames - 1).toFloat()
            else -> 1f
        }.coerceIn(0f, 1f)
    }
}
