package com.aistudio.mediatool.core.spatial

import java.io.BufferedInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bảo vệ tiếng xì chỉ trên nhánh được spatialize. Đường dry luôn giữ nguyên.
 */
enum class SpatialHissProtection(val label: String) {
    AUTO("Tự động • giữ độ sáng"),
    STRONG("Mạnh • nguồn nhiều xì"),
    OFF("Tắt • giữ nguyên nhánh 3D"),
}

internal data class SpatialHissProfile(
    val analyzedBlocks: Int = 0,
    val quietBlocks: Int = 0,
    val quietBroadbandDbfs: Float = -160f,
    val quietHighBandDbfs: Float = -160f,
    val quietHighBandRatioDb: Float = -160f,
    val highBandStabilityDb: Float = 0f,
    val risk: Float = 0f,
) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "hiss_analyzed_blocks" to analyzedBlocks,
        "hiss_quiet_blocks" to quietBlocks,
        "hiss_quiet_broadband_dbfs" to quietBroadbandDbfs,
        "hiss_quiet_high_band_dbfs" to quietHighBandDbfs,
        "hiss_quiet_high_band_ratio_db" to quietHighBandRatioDb,
        "hiss_high_band_stability_db" to highBandStabilityDb,
        "hiss_risk" to risk,
    )
}

internal data class SpatialHissPlan(
    val mode: SpatialHissProtection,
    val noiseReductionDb: Float,
    val wetHighShelfDb: Float,
    val wetHighShelfHz: Int,
    val reverbHighEqScale: Float,
    val reverbHighRt60Scale: Float,
) {
    val enabled: Boolean
        get() = mode != SpatialHissProtection.OFF

    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "hiss_protection_mode" to mode.name,
        "hiss_noise_reduction_db" to noiseReductionDb,
        "hiss_wet_high_shelf_db" to wetHighShelfDb,
        "hiss_wet_high_shelf_hz" to wetHighShelfHz,
        "hiss_reverb_high_eq_scale" to reverbHighEqScale,
        "hiss_reverb_high_rt60_scale" to reverbHighRt60Scale,
        "hiss_dry_path_untouched" to true,
    )
}

internal object SpatialHissProtector {
    private const val SAMPLE_RATE = 48_000.0
    private const val BYTES_PER_FRAME = 8
    private const val BLOCK_FRAMES = 1_024
    private const val MIN_PARTIAL_BLOCK_FRAMES = 256
    private const val HIGH_PASS_HZ = 7_000.0
    private const val DB_FLOOR = -160f
    private const val BUFFER_BYTES = 64 * 1024

    fun analyze(file: File): SpatialHissProfile {
        require(file.isFile && file.length() >= BYTES_PER_FRAME) {
            "PCM stereo f32le không hợp lệ: ${file.absolutePath}"
        }

        val blocks = ArrayList<BlockEnergy>()
        val pole = exp(-2.0 * PI * HIGH_PASS_HZ / SAMPLE_RATE)
        val feed = 1.0 - pole
        var lowLeft = 0.0
        var lowRight = 0.0
        var blockFrames = 0
        var broadbandEnergy = 0.0
        var highBandEnergy = 0.0

        fun finishBlock() {
            if (blockFrames < MIN_PARTIAL_BLOCK_FRAMES) return
            val divisor = 2.0 * blockFrames.toDouble()
            blocks += BlockEnergy(
                broadbandDbfs = amplitudeDb(sqrt(broadbandEnergy / divisor)),
                highBandDbfs = amplitudeDb(sqrt(highBandEnergy / divisor)),
            )
            blockFrames = 0
            broadbandEnergy = 0.0
            highBandEnergy = 0.0
        }

        BufferedInputStream(file.inputStream(), BUFFER_BYTES).use { input ->
            val buffer = ByteArray(BUFFER_BYTES + BYTES_PER_FRAME)
            var carry = 0
            while (true) {
                val read = input.read(buffer, carry, BUFFER_BYTES - carry)
                if (read < 0) break
                val available = carry + read
                val processBytes = available - (available % BYTES_PER_FRAME)
                val values = ByteBuffer.wrap(buffer, 0, processBytes).order(ByteOrder.LITTLE_ENDIAN)

                while (values.remaining() >= BYTES_PER_FRAME) {
                    val rawLeft = values.float.toDouble()
                    val rawRight = values.float.toDouble()
                    val left = rawLeft.takeIf(Double::isFinite) ?: 0.0
                    val right = rawRight.takeIf(Double::isFinite) ?: 0.0

                    lowLeft = feed * left + pole * lowLeft
                    lowRight = feed * right + pole * lowRight
                    val highLeft = left - lowLeft
                    val highRight = right - lowRight

                    broadbandEnergy += left * left + right * right
                    highBandEnergy += highLeft * highLeft + highRight * highRight
                    blockFrames++
                    if (blockFrames == BLOCK_FRAMES) finishBlock()
                }

                carry = available - processBytes
                if (carry > 0) {
                    buffer.copyInto(buffer, 0, processBytes, available)
                }
            }
        }
        finishBlock()

        val usable = blocks.filter { it.broadbandDbfs in -95f..-5f }
        if (usable.size < 4) return SpatialHissProfile(analyzedBlocks = blocks.size)

        val quietPercentile = percentile(usable.map(BlockEnergy::broadbandDbfs), 0.20f)
        val quietCeiling = min(quietPercentile + 3f, -30f)
        var quiet = usable.filter { it.broadbandDbfs <= quietCeiling }
        var availabilityScale = 1f
        if (quiet.size < 4) {
            quiet = usable.sortedBy(BlockEnergy::broadbandDbfs).take(min(8, usable.size))
            availabilityScale = 0.35f
        }

        val quietBroadband = median(quiet.map(BlockEnergy::broadbandDbfs))
        val quietHighBand = median(quiet.map(BlockEnergy::highBandDbfs))
        val ratios = quiet.map { it.highBandDbfs - it.broadbandDbfs }
        val quietRatio = median(ratios)
        val stability = standardDeviation(ratios)

        val floorScore = smoothStep(-80f, -48f, quietHighBand)
        val ratioScore = smoothStep(-32f, -8f, quietRatio)
        val stationarity = 1f - smoothStep(3f, 10f, stability)
        val audibility = 1f - smoothStep(-55f, -28f, quietBroadband)
        val risk = (
            (0.60f * floorScore + 0.40f * ratioScore) *
                (0.70f + 0.30f * stationarity) *
                (0.65f + 0.35f * audibility) *
                availabilityScale
            ).coerceIn(0f, 1f)

        return SpatialHissProfile(
            analyzedBlocks = usable.size,
            quietBlocks = quiet.size,
            quietBroadbandDbfs = quietBroadband,
            quietHighBandDbfs = quietHighBand,
            quietHighBandRatioDb = quietRatio,
            highBandStabilityDb = stability,
            risk = risk,
        )
    }

    fun plan(mode: SpatialHissProtection, profile: SpatialHissProfile): SpatialHissPlan {
        val risk = profile.risk.coerceIn(0f, 1f)
        return when (mode) {
            SpatialHissProtection.OFF -> SpatialHissPlan(
                mode = mode,
                noiseReductionDb = 0f,
                wetHighShelfDb = 0f,
                wetHighShelfHz = 9_000,
                reverbHighEqScale = 1f,
                reverbHighRt60Scale = 1f,
            )

            SpatialHissProtection.AUTO -> SpatialHissPlan(
                mode = mode,
                noiseReductionDb = 1.5f + 2.5f * risk,
                wetHighShelfDb = -(0.8f + 1.7f * risk),
                wetHighShelfHz = (9_000f - 1_500f * risk).roundToInt(),
                reverbHighEqScale = 0.92f - 0.12f * risk,
                reverbHighRt60Scale = 0.85f - 0.20f * risk,
            )

            SpatialHissProtection.STRONG -> SpatialHissPlan(
                mode = mode,
                noiseReductionDb = 4f + 2f * risk,
                wetHighShelfDb = -(2f + 1.5f * risk),
                wetHighShelfHz = (8_000f - 1_000f * risk).roundToInt(),
                reverbHighEqScale = 0.75f - 0.15f * risk,
                reverbHighRt60Scale = 0.70f - 0.15f * risk,
            )
        }
    }

    fun protectConfig(config: SpatialAudioConfig, plan: SpatialHissPlan): SpatialAudioConfig {
        if (!plan.enabled) return config
        return config.copy(
            reverbEqHigh = (config.reverbEqHigh * plan.reverbHighEqScale).coerceIn(0f, 1f),
            reverbRt60High = max(0.1f, config.reverbRt60High * plan.reverbHighRt60Scale),
        ).normalized()
    }

    fun spatialInputFilter(plan: SpatialHissPlan): String? {
        if (!plan.enabled || plan.noiseReductionDb < 0.5f) return null
        return "afftdn=nr=${decimal(plan.noiseReductionDb)}:nf=-80:tn=1:tr=1:" +
            "ad=0.85:fo=0.25:nl=average:gs=12"
    }

    fun wetBranchFilter(plan: SpatialHissPlan): String? {
        if (!plan.enabled || plan.wetHighShelfDb > -0.05f) return null
        return "highshelf=f=${plan.wetHighShelfHz}:g=${decimal(plan.wetHighShelfDb)}:t=q:w=0.707"
    }

    private fun amplitudeDb(value: Double): Float = if (value <= 1e-8 || !value.isFinite()) {
        DB_FLOOR
    } else {
        (20.0 * kotlin.math.log10(value)).toFloat().coerceAtLeast(DB_FLOOR)
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return DB_FLOOR
        val sorted = values.sorted()
        val position = (sorted.lastIndex * fraction.coerceIn(0f, 1f)).roundToInt()
        return sorted[position]
    }

    private fun median(values: List<Float>): Float = percentile(values, 0.5f)

    private fun standardDeviation(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        val variance = values.sumOf { (it - mean).toDouble().pow(2.0) } / values.size.toDouble()
        return sqrt(variance).toFloat()
    }

    private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
        if (edge1 <= edge0) return 0f
        val x = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    private fun decimal(value: Float): String = String.format(Locale.US, "%.2f", value)

    private data class BlockEnergy(
        val broadbandDbfs: Float,
        val highBandDbfs: Float,
    )
}
