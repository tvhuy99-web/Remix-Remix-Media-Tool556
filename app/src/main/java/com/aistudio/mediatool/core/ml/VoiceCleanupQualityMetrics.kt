package com.aistudio.mediatool.core.ml

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

data class VoiceCleanupFrontendComparisonMetrics(
    val valueCount: Int,
    val meanAbsoluteDifference: Double,
    val rootMeanSquareDifference: Double,
    val maximumAbsoluteDifference: Double,
    val changedPercent: Double,
) {
    internal fun diagnosticFields(): Map<String, Any?> = mapOf(
        "frontend_ab_value_count" to valueCount,
        "frontend_ab_mean_abs_diff" to meanAbsoluteDifference,
        "frontend_ab_rmse" to rootMeanSquareDifference,
        "frontend_ab_max_abs_diff" to maximumAbsoluteDifference,
        "frontend_ab_changed_percent" to changedPercent,
    )

    companion object {
        fun compare(reference: FloatArray, candidate: FloatArray): VoiceCleanupFrontendComparisonMetrics {
            require(reference.size == candidate.size && reference.isNotEmpty())
            var sumAbs = 0.0
            var sumSquares = 0.0
            var maximum = 0.0
            var changed = 0
            for (index in reference.indices) {
                val difference = abs(candidate[index].toDouble() - reference[index].toDouble())
                sumAbs += difference
                sumSquares += difference * difference
                maximum = maxOf(maximum, difference)
                if (difference > CHANGED_EPSILON) changed++
            }
            return VoiceCleanupFrontendComparisonMetrics(
                valueCount = reference.size,
                meanAbsoluteDifference = sumAbs / reference.size,
                rootMeanSquareDifference = sqrt(sumSquares / reference.size),
                maximumAbsoluteDifference = maximum,
                changedPercent = changed.toDouble() * 100.0 / reference.size,
            )
        }

        private const val CHANGED_EPSILON = 1.0e-6
    }
}

data class VoiceCleanupSeamMetrics(
    val seamCount: Int,
    val meanAbsoluteJump: Double,
    val maximumAbsoluteJump: Double,
    val maximumRelativeJumpDb: Double?,
    val meanAbsoluteRmsDeltaDb: Double,
    val maximumAbsoluteRmsDeltaDb: Double,
) {
    internal fun diagnosticFields(): Map<String, Any?> = mapOf(
        "seam_count" to seamCount,
        "seam_mean_abs_jump" to meanAbsoluteJump,
        "seam_max_abs_jump" to maximumAbsoluteJump,
        "seam_max_relative_jump_db" to maximumRelativeJumpDb,
        "seam_mean_abs_rms_delta_db" to meanAbsoluteRmsDeltaDb,
        "seam_max_abs_rms_delta_db" to maximumAbsoluteRmsDeltaDb,
    )
}

internal class VoiceCleanupSeamAccumulator(
    private val windowSamples: Int = MossFormer2Dsp.SAMPLE_RATE / 50,
) {
    private var previousTail = FloatArray(0)
    private var seamCount = 0
    private var jumpSum = 0.0
    private var maximumJump = 0.0
    private var maximumRelativeJumpDb: Double? = null
    private var rmsDeltaSum = 0.0
    private var maximumRmsDelta = 0.0

    fun addSegment(
        samples: FloatArray,
        start: Int,
        count: Int,
        scale: Float = 1f,
    ) {
        require(start >= 0 && count >= 0 && start + count <= samples.size)
        require(scale > 0f)
        if (count == 0) return

        if (previousTail.isNotEmpty()) {
            val afterCount = minOf(windowSamples, count)
            val first = samples[start].toDouble() / scale
            val previousLast = previousTail.last().toDouble()
            val jump = abs(first - previousLast)
            val beforeRms = rms(previousTail, 0, previousTail.size)
            val afterRms = rms(samples, start, afterCount, scale)
            val localRms = (beforeRms + afterRms) / 2.0
            val relativeJumpDb = if (jump > 0.0 && localRms > RMS_FLOOR) {
                20.0 * log10(jump / localRms)
            } else {
                null
            }
            val rmsDelta = if (beforeRms > RMS_FLOOR && afterRms > RMS_FLOOR) {
                abs(20.0 * log10(afterRms / beforeRms))
            } else {
                0.0
            }

            seamCount++
            jumpSum += jump
            maximumJump = maxOf(maximumJump, jump)
            if (relativeJumpDb != null) {
                maximumRelativeJumpDb = maxOf(maximumRelativeJumpDb ?: relativeJumpDb, relativeJumpDb)
            }
            rmsDeltaSum += rmsDelta
            maximumRmsDelta = maxOf(maximumRmsDelta, rmsDelta)
        }

        val tailCount = minOf(windowSamples, count)
        previousTail = FloatArray(tailCount) { offset ->
            samples[start + count - tailCount + offset] / scale
        }
    }

    fun snapshot(): VoiceCleanupSeamMetrics = VoiceCleanupSeamMetrics(
        seamCount = seamCount,
        meanAbsoluteJump = if (seamCount > 0) jumpSum / seamCount else 0.0,
        maximumAbsoluteJump = maximumJump,
        maximumRelativeJumpDb = maximumRelativeJumpDb,
        meanAbsoluteRmsDeltaDb = if (seamCount > 0) rmsDeltaSum / seamCount else 0.0,
        maximumAbsoluteRmsDeltaDb = maximumRmsDelta,
    )

    private fun rms(values: FloatArray, start: Int, count: Int, scale: Float = 1f): Double {
        var sumSquares = 0.0
        for (index in start until start + count) {
            val value = values[index].toDouble() / scale
            sumSquares += value * value
        }
        return if (count > 0) sqrt(sumSquares / count) else 0.0
    }

    private companion object {
        const val RMS_FLOOR = 1.0e-12
    }
}

data class VoiceCleanupTimingMetrics(
    val modelOpenMs: Long,
    val frontendMs: Long,
    val onnxMs: Long,
    val maskApplyMs: Long,
    val pcmWriteMs: Long,
    val enhanceMs: Long,
    val onnxRealTimeFactor: Double,
    val enhanceRealTimeFactor: Double,
    val pipelineMs: Long = 0L,
    val pipelineRealTimeFactor: Double = 0.0,
) {
    internal fun diagnosticFields(): Map<String, Any?> = mapOf(
        "model_open_ms" to modelOpenMs,
        "frontend_ms" to frontendMs,
        "onnx_ms" to onnxMs,
        "mask_apply_ms" to maskApplyMs,
        "pcm_write_ms" to pcmWriteMs,
        "enhance_ms" to enhanceMs,
        "onnx_rtf" to onnxRealTimeFactor,
        "enhance_rtf" to enhanceRealTimeFactor,
        "pipeline_ms" to pipelineMs,
        "pipeline_rtf" to pipelineRealTimeFactor,
    )
}
