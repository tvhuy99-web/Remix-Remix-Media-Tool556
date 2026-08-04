package com.aistudio.mediatool.core.ml

import kotlin.math.ceil
import kotlin.math.roundToInt

data class VoiceCleanupAudioMetrics(
    val integratedLufs: Double?,
    val rmsDbfs: Double?,
    val samplePeakDbfs: Double?,
    val truePeakDbfs: Double?,
) {
    internal fun diagnosticFields(prefix: String): Map<String, Any?> = mapOf(
        "${prefix}_integrated_lufs" to integratedLufs,
        "${prefix}_rms_dbfs" to rmsDbfs,
        "${prefix}_sample_peak_dbfs" to samplePeakDbfs,
        "${prefix}_true_peak_dbfs" to truePeakDbfs,
    )
}

data class VoiceCleanupMaskMetrics(
    val valueCount: Long,
    val frameCount: Long,
    val minimum: Double,
    val maximum: Double,
    val mean: Double,
    val p10: Double,
    val p50: Double,
    val p90: Double,
    val belowPointFivePercent: Double,
    val belowPointNinePercent: Double,
    val nearUnityPercent: Double,
    val outsideZeroOnePercent: Double,
) {
    internal fun diagnosticFields(prefix: String = "mask"): Map<String, Any?> = mapOf(
        "${prefix}_value_count" to valueCount,
        "${prefix}_frame_count" to frameCount,
        "${prefix}_min" to minimum,
        "${prefix}_max" to maximum,
        "${prefix}_mean" to mean,
        "${prefix}_p10" to p10,
        "${prefix}_p50" to p50,
        "${prefix}_p90" to p90,
        "${prefix}_below_0_5_percent" to belowPointFivePercent,
        "${prefix}_below_0_9_percent" to belowPointNinePercent,
        "${prefix}_near_unity_percent" to nearUnityPercent,
        "${prefix}_outside_0_1_percent" to outsideZeroOnePercent,
    )
}

data class VoiceCleanupReport(
    val source: VoiceCleanupAudioMetrics,
    val afterAi: VoiceCleanupAudioMetrics,
    val finalOutput: VoiceCleanupAudioMetrics,
    val mask: VoiceCleanupMaskMetrics,
    val appliedGainDb: Float,
    val segmentCount: Int,
    val inferenceRealTimeFactor: Double,
    val timing: VoiceCleanupTimingMetrics? = null,
    val seams: VoiceCleanupSeamMetrics? = null,
    val frontendComparison: VoiceCleanupFrontendComparisonMetrics? = null,
)

internal object VoiceCleanupMetricsParser {
    private val integratedLufs = Regex("\\\"input_i\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val truePeakDbfs = Regex("\\\"input_tp\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val rmsDbfs = Regex("mean_volume:\\s*([^\\s]+)\\s*dB")
    private val samplePeakDbfs = Regex("max_volume:\\s*([^\\s]+)\\s*dB")

    fun parse(logs: String): VoiceCleanupAudioMetrics = VoiceCleanupAudioMetrics(
        integratedLufs = findLastFinite(integratedLufs, logs),
        rmsDbfs = findLastFinite(rmsDbfs, logs),
        samplePeakDbfs = findLastFinite(samplePeakDbfs, logs),
        truePeakDbfs = findLastFinite(truePeakDbfs, logs),
    )

    private fun findLastFinite(regex: Regex, text: String): Double? = regex
        .findAll(text)
        .lastOrNull()
        ?.groupValues
        ?.getOrNull(1)
        ?.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
}

internal class VoiceCleanupMaskAccumulator {
    private val histogram = LongArray(HISTOGRAM_BINS)
    private var count = 0L
    private var frames = 0L
    private var sum = 0.0
    private var minimum = Double.POSITIVE_INFINITY
    private var maximum = Double.NEGATIVE_INFINITY
    private var belowPointFive = 0L
    private var belowPointNine = 0L
    private var nearUnity = 0L
    private var outsideZeroOne = 0L

    fun add(values: FloatArray) {
        addRange(values, 0, values.size)
    }

    fun addFrames(values: FloatArray, frameRange: IntRange, bins: Int) {
        require(bins > 0 && values.size % bins == 0)
        if (frameRange.isEmpty()) return
        val availableFrames = values.size / bins
        require(frameRange.first >= 0 && frameRange.last < availableFrames)
        frames += frameRange.count().toLong()
        addRange(values, frameRange.first * bins, (frameRange.last + 1) * bins)
    }

    fun addEffectiveFrames(
        values: FloatArray,
        frameRange: IntRange,
        bins: Int,
        cleanupStrength: Float,
    ) {
        require(bins > 0 && values.size % bins == 0)
        if (frameRange.isEmpty()) return
        val availableFrames = values.size / bins
        require(frameRange.first >= 0 && frameRange.last < availableFrames)
        frames += frameRange.count().toLong()
        val start = frameRange.first * bins
        val endExclusive = (frameRange.last + 1) * bins
        for (index in start until endExclusive) {
            addValue(MossFormer2Dsp.effectiveMaskGain(values[index], cleanupStrength))
        }
    }

    private fun addRange(values: FloatArray, start: Int, endExclusive: Int) {
        require(start in 0..values.size && endExclusive in start..values.size)
        for (index in start until endExclusive) addValue(values[index])
    }

    private fun addValue(raw: Float) {
        require(raw.isFinite()) { "Mask MossFormer2 chứa giá trị không hữu hạn" }
        val value = raw.toDouble()
        count++
        sum += value
        minimum = minOf(minimum, value)
        maximum = maxOf(maximum, value)
        if (value < 0.5) belowPointFive++
        if (value < 0.9) belowPointNine++
        if (value in 0.98..1.02) nearUnity++
        if (value < 0.0 || value > 1.0) outsideZeroOne++
        val index = (value.coerceIn(0.0, 1.0) * (HISTOGRAM_BINS - 1))
            .roundToInt()
            .coerceIn(0, HISTOGRAM_BINS - 1)
        histogram[index]++
    }

    fun snapshot(): VoiceCleanupMaskMetrics {
        require(count > 0L) { "Chưa có mask để thống kê" }
        return VoiceCleanupMaskMetrics(
            valueCount = count,
            frameCount = frames.takeIf { it > 0L } ?: (count / MossFormer2Dsp.BINS),
            minimum = minimum,
            maximum = maximum,
            mean = sum / count.toDouble(),
            p10 = percentile(0.10),
            p50 = percentile(0.50),
            p90 = percentile(0.90),
            belowPointFivePercent = percent(belowPointFive),
            belowPointNinePercent = percent(belowPointNine),
            nearUnityPercent = percent(nearUnity),
            outsideZeroOnePercent = percent(outsideZeroOne),
        )
    }

    private fun percentile(fraction: Double): Double {
        val target = ceil(count * fraction).toLong().coerceAtLeast(1L)
        var cumulative = 0L
        for (index in histogram.indices) {
            cumulative += histogram[index]
            if (cumulative >= target) {
                return index.toDouble() / (HISTOGRAM_BINS - 1).toDouble()
            }
        }
        return 1.0
    }

    private fun percent(value: Long): Double = value.toDouble() * 100.0 / count.toDouble()

    private companion object {
        const val HISTOGRAM_BINS = 1_001
    }
}
