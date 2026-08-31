package com.aistudio.mediatool.core.media

import kotlin.math.roundToInt

object VideoCompressionPolicy {
    /** AAC target used by the hardware compressor. Kept explicit so file-size percentages are predictable. */
    const val TARGET_AUDIO_BITRATE = 128_000

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
     * The UI percentage is a target FILE-SIZE ratio, not a subjective quality score.
     *
     * For a video whose duration is unchanged, output size is approximately proportional to
     * total bitrate. Therefore 70% means target total bitrate ~= 70% of the source total bitrate.
     * The AAC budget is subtracted so video + audio together stay close to the requested ratio.
     * Container overhead and device encoder behaviour can still introduce a small deviation.
     */
    fun targetVideoBitrate(
        sourceBitrate: Int,
        sourceHeight: Int,
        outputHeight: Int?,
        qualityPercent: Int,
    ): Int {
        val targetPercent = qualityPercent.coerceIn(10, 100)
        if (sourceBitrate > 0) {
            val targetTotalBitrate = (sourceBitrate.toLong() * targetPercent / 100L)
                .coerceAtLeast(MIN_TOTAL_BITRATE.toLong())
            return (targetTotalBitrate - TARGET_AUDIO_BITRATE)
                .coerceAtLeast(MIN_VIDEO_BITRATE.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }

        // Metadata bitrate is occasionally unavailable. In that case use a conservative
        // resolution-aware baseline, still scaled linearly by the requested size percentage.
        val height = (outputHeight ?: sourceHeight).takeIf { it > 0 } ?: 1080
        val baselineTotalBitrate = when {
            height <= 480 -> 2_000_000
            height <= 720 -> 4_000_000
            height <= 1080 -> 8_000_000
            height <= 1440 -> 12_000_000
            else -> 20_000_000
        }
        val targetTotalBitrate = (baselineTotalBitrate * (targetPercent / 100.0)).roundToInt()
        return (targetTotalBitrate - TARGET_AUDIO_BITRATE).coerceAtLeast(MIN_VIDEO_BITRATE)
    }

    fun targetTotalBitrate(sourceBitrate: Int, targetPercent: Int): Int =
        if (sourceBitrate > 0) {
            (sourceBitrate.toLong() * targetPercent.coerceIn(10, 100) / 100L)
                .coerceAtLeast(MIN_TOTAL_BITRATE.toLong())
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } else {
            0
        }

    private const val MIN_VIDEO_BITRATE = 250_000
    private const val MIN_TOTAL_BITRATE = MIN_VIDEO_BITRATE + TARGET_AUDIO_BITRATE
}
