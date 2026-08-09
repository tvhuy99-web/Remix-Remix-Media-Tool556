package com.aistudio.mediatool.feature.studio.data

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import kotlin.math.roundToLong

data class StudioAudioMetadata(
    val sampleRate: Int?,
    val channelCount: Int?,
    val durationFrames: Long?,
)

/**
 * Best-effort metadata probe for app-owned audio files.
 *
 * Studio project assets may predate metadata persistence, and the Lab can be opened
 * before the realtime beat preparation path runs. Keep this probe off realtime paths
 * and use it only when persisted metadata is missing.
 */
object StudioAudioMetadataProbe {
    fun probe(file: File): StudioAudioMetadata? {
        if (!file.isFile || file.length() <= 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.stringOrNull(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioFormat = format
                    break
                }
            }
            val format = audioFormat ?: return null
            val sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 }
            val channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.takeIf { it > 0 }
            val durationUs = format.longOrNull(MediaFormat.KEY_DURATION)?.takeIf { it > 0L }
            val durationFrames = if (sampleRate != null && durationUs != null) {
                (durationUs.toDouble() * sampleRate.toDouble() / 1_000_000.0)
                    .roundToLong()
                    .coerceAtLeast(1L)
            } else {
                null
            }
            StudioAudioMetadata(sampleRate, channelCount, durationFrames)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    private fun MediaFormat.stringOrNull(key: String): String? =
        if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null
}
