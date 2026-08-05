package com.aistudio.mediatool.core.ml

enum class VoiceCleanupLoudnessMode {
    RAW,
    MATCH_SOURCE,
    TARGET_LUFS,
    ;

    companion object {
        fun fromName(value: String?): VoiceCleanupLoudnessMode =
            entries.firstOrNull { it.name == value } ?: MATCH_SOURCE
    }
}

enum class VoiceCleanupDitherMode(val amplitudeLsb: Float) {
    OFF(0f),
    KALDI_1_LSB(1f),
    ;
}

enum class VoiceCleanupWindowMode(
    val seconds: Int,
    val segmentSamples: Int,
    val onePassLimitSamples: Int,
    val minimumAvailableRamBytes: Long,
) {
    BALANCED_10S(
        seconds = 10,
        segmentSamples = 10 * MossFormer2Dsp.SAMPLE_RATE,
        onePassLimitSamples = 10 * MossFormer2Dsp.SAMPLE_RATE,
        minimumAvailableRamBytes = 1_024L * 1024L * 1024L,
    ),
    QUALITY_20S(
        seconds = 20,
        segmentSamples = 20 * MossFormer2Dsp.SAMPLE_RATE,
        onePassLimitSamples = 20 * MossFormer2Dsp.SAMPLE_RATE,
        minimumAvailableRamBytes = 1_536L * 1024L * 1024L,
    ),
    MAXIMUM_30S(
        seconds = 30,
        segmentSamples = 30 * MossFormer2Dsp.SAMPLE_RATE,
        onePassLimitSamples = 30 * MossFormer2Dsp.SAMPLE_RATE,
        minimumAvailableRamBytes = 2_048L * 1024L * 1024L,
    ),
    ;

    val edgeDiscardSamples: Int
        get() = segmentSamples / 8

    val strideSamples: Int
        get() = segmentSamples - 2 * edgeDiscardSamples

    val frames: Int
        get() = MossFormer2Dsp.frameCount(segmentSamples)

    companion object {
        fun fromName(value: String?): VoiceCleanupWindowMode = when (value) {
            "COMPATIBILITY_4S" -> BALANCED_10S
            "MAXIMUM_15S" -> QUALITY_20S
            else -> entries.firstOrNull { it.name == value } ?: BALANCED_10S
        }
    }
}

data class VoiceCleanupConfig(
    val windowMode: VoiceCleanupWindowMode = VoiceCleanupWindowMode.BALANCED_10S,
    val cleanupStrengthPercent: Int = 65,
    val ditherMode: VoiceCleanupDitherMode = VoiceCleanupDitherMode.KALDI_1_LSB,
    val loudnessMode: VoiceCleanupLoudnessMode = VoiceCleanupLoudnessMode.MATCH_SOURCE,
    val targetLufs: Float = -16f,
    val outputGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -1f,
) {
    init {
        require(cleanupStrengthPercent in 1..100) { "Mức làm sạch phải nằm trong khoảng 1 đến 100" }
        require(targetLufs in -30f..-8f) { "LUFS mục tiêu phải nằm trong khoảng -30 đến -8" }
        require(outputGainDb in -12f..12f) { "Gain bổ sung phải nằm trong khoảng -12 đến +12 dB" }
        require(limiterCeilingDb in -6f..-0.1f) {
            "Ngưỡng limiter phải nằm trong khoảng -6 đến -0,1 dBFS"
        }
    }

    val cleanupStrength: Float
        get() = cleanupStrengthPercent / 100f

    internal fun diagnosticFields(): Map<String, Any?> = mapOf(
        "window_mode" to windowMode.name,
        "window_seconds" to windowMode.seconds,
        "configured_segment_samples" to windowMode.segmentSamples,
        "configured_stride_samples" to windowMode.strideSamples,
        "configured_feature_frames" to windowMode.frames,
        "one_pass_limit_samples" to windowMode.onePassLimitSamples,
        "cleanup_strength_percent" to cleanupStrengthPercent,
        "cleanup_strength" to cleanupStrength,
        "dither_mode" to ditherMode.name,
        "dither_amplitude_lsb" to ditherMode.amplitudeLsb,
        "loudness_mode" to loudnessMode.name,
        "target_lufs" to targetLufs,
        "output_gain_db" to outputGainDb,
        "limiter_enabled" to limiterEnabled,
        "limiter_ceiling_dbfs" to limiterCeilingDb,
    )
}
