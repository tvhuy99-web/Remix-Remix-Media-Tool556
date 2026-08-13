package com.aistudio.mediatool.feature.studio.data

import java.util.Locale

enum class StudioBeatFormat(val extension: String) {
    MP3("mp3"),
    WAV("wav"),
    M4A("m4a"),
    FLAC("flac"),
}

object StudioBeatFormatDetector {
    fun detect(displayName: String, mimeType: String?): StudioBeatFormat? {
        val extension = displayName
            .substringAfterLast('.', "")
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }

        if (extension != null) {
            return StudioBeatFormat.entries.firstOrNull { it.extension == extension }
        }

        return when (mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)) {
            "audio/mpeg", "audio/mp3" -> StudioBeatFormat.MP3
            "audio/wav", "audio/x-wav", "audio/wave" -> StudioBeatFormat.WAV
            "audio/mp4", "audio/m4a", "audio/x-m4a" -> StudioBeatFormat.M4A
            "audio/flac", "audio/x-flac" -> StudioBeatFormat.FLAC
            else -> null
        }
    }

    fun requireSupported(displayName: String, mimeType: String?): StudioBeatFormat =
        requireNotNull(detect(displayName, mimeType)) {
            "Định dạng nhạc nền chưa được hỗ trợ. Hãy chọn MP3, WAV, M4A hoặc FLAC."
        }
}
