package com.aistudio.mediatool.core.media

import kotlin.math.min
import kotlin.math.pow

object VideoCompressionPolicy {
    fun requestedHeight(resolutionIndex: Int): Int? = when (resolutionIndex) {
        1 -> 720
        2 -> 480
        else -> null
    }

    fun effectiveHeight(sourceHeight: Int, resolutionIndex: Int): Int? {
        val requested = requestedHeight(resolutionIndex) ?: return null
        if (sourceHeight <= 0 || sourceHeight <= requested) return null
        return requested
    }

    /**
     * Converts the UI quality percentage into a practical H.264 target bitrate.
     * The result is resolution-aware and never intentionally exceeds 95% of the estimated
     * source video bitrate, because a compression action should not make the video larger.
     */
    fun targetVideoBitrate(
        sourceBitrate: Int,
        sourceHeight: Int,
        outputHeight: Int?,
        qualityPercent: Int,
    ): Int {
        val quality = qualityPercent.coerceIn(10, 100) / 100.0
        val height = (outputHeight ?: sourceHeight).takeIf { it > 0 } ?: 1080
        val (floor, ceiling) = when {
            height <= 480 -> 450_000 to 4_000_000
            height <= 720 -> 750_000 to 7_000_000
            height <= 1080 -> 1_200_000 to 12_000_000
            height <= 1440 -> 2_000_000 to 18_000_000
            else -> 3_000_000 to 26_000_000
        }

        // Curved scale keeps low values genuinely small while preserving noticeably more detail
        // near the top of the slider.
        val desired = floor + ((ceiling - floor) * quality.pow(1.35)).toInt()
        if (sourceBitrate <= 0) return desired.coerceIn(floor, ceiling)

        val sourceVideoEstimate = estimateScaledSourceBitrate(
            sourceBitrate = sourceBitrate,
            sourceHeight = sourceHeight,
            outputHeight = height,
        )
        val compressionCeiling = (sourceVideoEstimate * 0.95).toInt().coerceAtLeast(250_000)
        return min(desired, compressionCeiling).coerceAtLeast(250_000)
    }

    private fun estimateScaledSourceBitrate(
        sourceBitrate: Int,
        sourceHeight: Int,
        outputHeight: Int,
    ): Double {
        if (sourceHeight <= 0 || outputHeight >= sourceHeight) return sourceBitrate.toDouble()
        val ratio = outputHeight.toDouble() / sourceHeight.toDouble()
        // Pixel count scales approximately with the square of the linear resolution ratio.
        return sourceBitrate * ratio * ratio
    }
}
