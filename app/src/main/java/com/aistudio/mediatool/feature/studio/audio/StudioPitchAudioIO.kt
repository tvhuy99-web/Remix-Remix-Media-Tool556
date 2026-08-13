package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.data.StudioWavFile
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

data class StudioPitchAnalysisInput(
    val mono: FloatArray,
    val analysisRate: Int,
    val sourceRate: Int,
    val sourceFrames: Long,
)

object StudioPitchAudioIO {
    fun readAnalysisMono(file: File, preferredRate: Int = 12_000): StudioPitchAnalysisInput {
        val info = requireNotNull(StudioWavFile.inspectCanonicalPcm16(file)) {
            "Auto-Tune cần WAV PCM16 nội bộ của Studio"
        }
        val group = max(1, info.sampleRate / preferredRate.coerceAtLeast(4_000))
        val analysisRate = info.sampleRate / group
        val outputFrames = ((info.dataFrames + group - 1L) / group)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val mono = FloatArray(outputFrames)
        val frameBytes = info.channelCount * 2
        val bufferFrames = 4_096
        val buffer = ByteArray(bufferFrames * frameBytes)
        var outputIndex = 0
        var groupCount = 0
        var groupSum = 0.0

        FileInputStream(file).buffered(256 * 1024).use { input ->
            var skipped = 0L
            while (skipped < StudioWavFile.HEADER_BYTES) {
                val value = input.skip(StudioWavFile.HEADER_BYTES.toLong() - skipped)
                if (value <= 0L) break
                skipped += value
            }
            require(skipped == StudioWavFile.HEADER_BYTES.toLong()) { "Không đọc được WAV Studio" }
            var remaining = info.dataFrames
            while (remaining > 0L && outputIndex < mono.size) {
                val framesWanted = minOf(bufferFrames.toLong(), remaining).toInt()
                val bytesWanted = framesWanted * frameBytes
                var bytesRead = 0
                while (bytesRead < bytesWanted) {
                    val read = input.read(buffer, bytesRead, bytesWanted - bytesRead)
                    if (read <= 0) break
                    bytesRead += read
                }
                val usableFrames = bytesRead / frameBytes
                for (frame in 0 until usableFrames) {
                    val base = frame * frameBytes
                    var channelSum = 0
                    for (channel in 0 until info.channelCount) {
                        val offset = base + channel * 2
                        channelSum += pcm16(buffer[offset], buffer[offset + 1])
                    }
                    groupSum += channelSum.toDouble() / info.channelCount.toDouble()
                    groupCount++
                    if (groupCount == group) {
                        mono[outputIndex++] = (groupSum / groupCount / 32768.0).toFloat()
                        groupCount = 0
                        groupSum = 0.0
                    }
                }
                remaining -= usableFrames
                if (usableFrames <= 0) break
            }
        }
        if (groupCount > 0 && outputIndex < mono.size) {
            mono[outputIndex++] = (groupSum / groupCount / 32768.0).toFloat()
        }
        return StudioPitchAnalysisInput(
            mono = if (outputIndex == mono.size) mono else mono.copyOf(outputIndex),
            analysisRate = analysisRate,
            sourceRate = info.sampleRate,
            sourceFrames = info.dataFrames,
        )
    }

    private fun pcm16(low: Byte, high: Byte): Int =
        (((high.toInt() and 0xff) shl 8) or (low.toInt() and 0xff)).toShort().toInt()
}
