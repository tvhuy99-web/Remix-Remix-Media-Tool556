package com.aistudio.mediatool.feature.studio.data

internal object StudioEditSafety {
    const val DEFAULT_FADE_MS = 8L

    fun frames(sampleRate: Int, clipLengthFrames: Long): Long {
        if (sampleRate <= 0 || clipLengthFrames <= 0L) return 0L
        return (sampleRate.toLong() * DEFAULT_FADE_MS / 1_000L)
            .coerceAtLeast(1L)
            .coerceAtMost(clipLengthFrames / 2L)
    }
}
