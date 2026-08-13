package com.aistudio.mediatool.feature.studio.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class StudioDetectedPitchFrame(
    val startSample: Int,
    val endSample: Int,
    val frequencyHz: Float?,
    val confidence: Float,
)

object StudioPitchDetector {
    private const val DEFAULT_MIN_HZ = 70f
    private const val DEFAULT_MAX_HZ = 560f
    private const val MIN_CORRELATION = 0.56

    fun estimate(
        mono: FloatArray,
        sampleRate: Int,
        minHz: Float = DEFAULT_MIN_HZ,
        maxHz: Float = DEFAULT_MAX_HZ,
    ): List<StudioDetectedPitchFrame> {
        if (sampleRate <= 0 || mono.isEmpty() || minHz <= 0f || maxHz <= minHz) return emptyList()
        val frameSize = max((sampleRate * 0.042f).roundToInt(), 384).coerceAtMost(mono.size)
        val hop = max((sampleRate * 0.020f).roundToInt(), 96)
        if (frameSize < 256 || mono.size < frameSize) return emptyList()

        val minLag = max(2, (sampleRate / maxHz).roundToInt())
        val maxLag = (sampleRate / minHz).roundToInt().coerceAtMost(frameSize / 2)
        if (maxLag <= minLag + 2) return emptyList()

        val output = ArrayList<StudioDetectedPitchFrame>()
        var start = 0
        while (start + frameSize <= mono.size) {
            val end = minOf(mono.size, start + hop)
            val stats = frameStats(mono, start, frameSize)
            if (stats.rms < 0.0085) {
                output += StudioDetectedPitchFrame(start, end, null, 0f)
                start += hop
                continue
            }

            val scores = DoubleArray(maxLag + 1)
            var bestLag = -1
            var bestScore = -1.0
            for (lag in minLag..maxLag) {
                var dot = 0.0
                var leftNorm = 0.0
                var rightNorm = 0.0
                var index = lag
                while (index < frameSize) {
                    val left = mono[start + index] - stats.mean
                    val right = mono[start + index - lag] - stats.mean
                    dot += left * right
                    leftNorm += left * left
                    rightNorm += right * right
                    index++
                }
                val score = dot / sqrt(leftNorm * rightNorm).coerceAtLeast(1e-12)
                scores[lag] = score
                if (score > bestScore) {
                    bestScore = score
                    bestLag = lag
                }
            }

            if (bestLag < 0 || bestScore < MIN_CORRELATION) {
                output += StudioDetectedPitchFrame(
                    startSample = start,
                    endSample = end,
                    frequencyHz = null,
                    confidence = bestScore.coerceIn(0.0, 1.0).toFloat(),
                )
                start += hop
                continue
            }

            val strongThreshold = bestScore * 0.965
            var selectedLag = bestLag
            for (lag in minLag + 1 until bestLag) {
                val value = scores[lag]
                if (value >= strongThreshold && value >= scores[lag - 1] && value >= scores[lag + 1]) {
                    selectedLag = lag
                    break
                }
            }

            val left = scores[(selectedLag - 1).coerceAtLeast(minLag)]
            val center = scores[selectedLag]
            val right = scores[(selectedLag + 1).coerceAtMost(maxLag)]
            val denominator = left - 2.0 * center + right
            val adjustment = if (abs(denominator) > 1e-9) {
                (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
            } else {
                0.0
            }
            val refinedLag = selectedLag + adjustment
            val frequency = (sampleRate.toDouble() / refinedLag).toFloat()
            val confidence = ((bestScore - MIN_CORRELATION) / (1.0 - MIN_CORRELATION))
                .coerceIn(0.0, 1.0)
                .toFloat()
            output += StudioDetectedPitchFrame(start, end, frequency, confidence)
            start += hop
        }
        return output
    }

    private data class FrameStats(val mean: Double, val rms: Double)

    private fun frameStats(samples: FloatArray, start: Int, size: Int): FrameStats {
        var mean = 0.0
        for (index in 0 until size) mean += samples[start + index]
        mean /= size.toDouble()
        var energy = 0.0
        for (index in 0 until size) {
            val centered = samples[start + index] - mean
            energy += centered * centered
        }
        return FrameStats(mean, sqrt(energy / size.toDouble()))
    }
}
