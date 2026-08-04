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

data class VoiceCleanupConfig(
    val loudnessMode: VoiceCleanupLoudnessMode = VoiceCleanupLoudnessMode.MATCH_SOURCE,
    val targetLufs: Float = -16f,
    val outputGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Float = -1f,
) {
    init {
        require(targetLufs in -30f..-8f) { "LUFS mục tiêu phải nằm trong khoảng -30 đến -8" }
        require(outputGainDb in -12f..12f) { "Gain bổ sung phải nằm trong khoảng -12 đến +12 dB" }
        require(limiterCeilingDb in -6f..-0.1f) {
            "Ngưỡng limiter phải nằm trong khoảng -6 đến -0,1 dBFS"
        }
    }

    internal fun diagnosticFields(): Map<String, Any?> = mapOf(
        "loudness_mode" to loudnessMode.name,
        "target_lufs" to targetLufs,
        "output_gain_db" to outputGainDb,
        "limiter_enabled" to limiterEnabled,
        "limiter_ceiling_dbfs" to limiterCeilingDb,
    )
}
