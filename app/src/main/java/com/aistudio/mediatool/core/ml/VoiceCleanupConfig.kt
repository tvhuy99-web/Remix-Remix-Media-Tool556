package com.aistudio.mediatool.core.ml

import kotlin.math.pow

enum class VoiceCleanupStrength(
    val displayName: String,
    val attenuationLimitDb: Float,
) {
    NATURAL("Tự nhiên", 9f),
    BALANCED("Cân bằng", 15f),
    STRONG("Mạnh", 24f),
    ;

    val noisyBlend: Float
        get() = 10f.pow(-attenuationLimitDb / 20f).coerceIn(0f, 1f)

    companion object {
        fun fromName(name: String?): VoiceCleanupStrength =
            entries.firstOrNull { it.name == name } ?: BALANCED
    }
}

data class VoiceCleanupConfig(
    val strength: VoiceCleanupStrength = VoiceCleanupStrength.BALANCED,
    val targetLufs: Int = -16,
) {
    init {
        require(targetLufs in ALLOWED_TARGETS) { "Mục tiêu loudness không được hỗ trợ: $targetLufs LUFS" }
    }

    companion object {
        val ALLOWED_TARGETS: Set<Int> = setOf(-18, -16, -14)
    }
}
