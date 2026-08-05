package com.aistudio.mediatool.core.spatial

internal enum class SpatialTailPolicy(val diagnosticName: String) {
    PRESERVE_AUDIO_TAIL("preserve_audio_tail"),
    TRUNCATE_PREVIEW_10_SECONDS("truncate_preview_10_seconds"),
    TRUNCATE_TO_VIDEO("truncate_to_video_duration"),
    ;

    fun diagnosticFields(): Map<String, Any?> = mapOf("tail_policy" to diagnosticName)

    companion object {
        fun resolve(isVideoMode: Boolean, preview: Boolean): SpatialTailPolicy = when {
            preview -> TRUNCATE_PREVIEW_10_SECONDS
            isVideoMode -> TRUNCATE_TO_VIDEO
            else -> PRESERVE_AUDIO_TAIL
        }
    }
}
