package com.aistudio.mediatool.core.ml

import kotlin.math.ceil

data class StemResourceEstimate(
    val durationMs: Long,
    val stemCount: Int,
    val temporaryBytes: Long,
    val recommendedFreeBytes: Long,
    val recommendedRamBytes: Long,
)

object StemPreflight {
    private const val SAMPLE_RATE = 44_100L
    private const val CHANNELS = 2L
    // Pipeline stem dùng PCM float32 để tránh clip/quantize trước bước encode cuối.
    private const val BYTES_PER_SAMPLE = 4L
    private const val BASE_RAM = 900L * 1024L * 1024L

    fun estimate(
        durationMs: Long,
        stemCount: Int,
        modelMinimumAvailableRamBytes: Long = 0L,
    ): StemResourceEstimate {
        require(durationMs > 0L)
        require(stemCount == 2 || stemCount == 4)
        val seconds = ceil(durationMs / 1_000.0).toLong()
        val pcmPerTrack = Math.multiplyExact(
            Math.multiplyExact(Math.multiplyExact(seconds, SAMPLE_RATE), CHANNELS),
            BYTES_PER_SAMPLE,
        )
        val rawTracks = 1L + if (stemCount == 4) 5L else 2L
        val temp = Math.multiplyExact(pcmPerTrack, rawTracks)
        val margin = temp / 3L + 512L * 1024L * 1024L
        val baselineRam = BASE_RAM + if (stemCount == 4) 700L * 1024L * 1024L else 300L * 1024L * 1024L
        val ram = maxOf(baselineRam, modelMinimumAvailableRamBytes)
        return StemResourceEstimate(durationMs, stemCount, temp, temp + margin, ram)
    }
}
