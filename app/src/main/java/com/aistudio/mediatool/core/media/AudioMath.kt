package com.aistudio.mediatool.core.media

import kotlin.math.max
import kotlin.math.min
import kotlin.math.log10

object AudioMath {
    data class StereoGain(val left: Float, val right: Float)

    fun stereoPan(value: Int): StereoGain {
        val pan = value.coerceIn(0, 100)
        val left = if (pan <= 50) 1f else (100 - pan) / 50f
        val right = if (pan >= 50) 1f else pan / 50f
        return StereoGain(left.coerceIn(0f, 1f), right.coerceIn(0f, 1f))
    }

    fun clampedFadeDuration(requestedSeconds: Double, segmentSeconds: Double): Double {
        if (!requestedSeconds.isFinite() || requestedSeconds <= 0.0) return 0.0
        if (!segmentSeconds.isFinite() || segmentSeconds <= 0.0) return 0.0
        return min(requestedSeconds, max(0.0, segmentSeconds / 2.0))
    }

    fun progressPercent(processedMs: Long, totalMs: Long): Int {
        if (totalMs <= 0L) return 0
        return ((processedMs.coerceAtLeast(0L).toDouble() / totalMs.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 99)
    }

    fun truePeakDbFromPercent(percent: Float): Double =
        (20.0 * log10(percent.coerceIn(0.01f, 100f).toDouble() / 100.0))
            .coerceIn(-9.0, 0.0)

    fun canApplyGlobalFade(requestedSeconds: Double, sourceDurationsMs: List<Long?>): Boolean =
        requestedSeconds.isFinite() && requestedSeconds > 0.0 &&
            sourceDurationsMs.isNotEmpty() && sourceDurationsMs.all { it != null && it > 0L }
}
