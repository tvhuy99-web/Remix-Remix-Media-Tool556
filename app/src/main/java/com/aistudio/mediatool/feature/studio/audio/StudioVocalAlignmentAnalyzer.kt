package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.data.StudioWavFile
import com.aistudio.mediatool.feature.studio.domain.StudioBeatGrid
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StudioVocalAlignmentSuggestion(
    val offsetMillis: Long,
    val confidence: Float,
    val onsetCount: Int,
    val averageErrorBeforeMillis: Float,
    val averageErrorAfterMillis: Float,
)

class StudioVocalAlignmentAnalyzer(
    private val repository: StudioProjectRepository,
) {
    suspend fun analyze(project: StudioProject, clipId: String): StudioVocalAlignmentSuggestion =
        withContext(Dispatchers.IO) {
            val clip = requireNotNull(project.findClip(clipId)) { "Không tìm thấy đoạn giọng đã chọn" }
            val asset = requireNotNull(project.asset(clip.sourceAssetId)) { "Đoạn giọng thiếu file âm thanh" }
            val file = requireNotNull(repository.assetFile(project.id, asset.id)) {
                "Không tìm thấy file của đoạn giọng"
            }
            val info = requireNotNull(StudioWavFile.inspectCanonicalPcm16(file)) {
                "Căn nhịp hiện hỗ trợ các bản thu WAV của Phòng thu"
            }
            val samples = readClipMono(file, info, clip)
            val sourceOnsets = StudioVocalAlignmentMath.detectOnsets(samples, info.sampleRate)
            require(sourceOnsets.isNotEmpty()) {
                "Chưa tìm thấy điểm vào giọng đủ rõ trong đoạn đã chọn"
            }
            val timelineOnsets = sourceOnsets.map { sourceOffset ->
                clip.timelineStartFrame + sourceToTimelineFrames(
                    sourceOffset,
                    info.sampleRate,
                    project.timelineSampleRate,
                )
            }
            requireNotNull(
                StudioVocalAlignmentMath.suggestOffset(
                    onsetTimelineFrames = timelineOnsets,
                    timelineSampleRate = project.timelineSampleRate,
                    project = project,
                ),
            ) { "Chưa tìm được gợi ý căn nhịp đáng tin cậy" }
        }

    private fun readClipMono(
        file: java.io.File,
        info: StudioWavFile.Info,
        clip: StudioClip,
    ): FloatArray {
        val startFrame = clip.sourceStartFrame.coerceIn(0L, info.dataFrames)
        val requestedEnd = clip.sourceEndFrame.coerceIn(startFrame, info.dataFrames)
        val maxFrames = info.sampleRate.toLong() * MAX_ANALYSIS_SECONDS
        val endFrame = minOf(requestedEnd, startFrame + maxFrames)
        val frameCount = (endFrame - startFrame).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        require(frameCount > info.sampleRate / 4) { "Đoạn giọng quá ngắn để căn nhịp" }
        val output = FloatArray(frameCount)
        val frameBytes = info.channelCount * 2
        val frameBuffer = ByteArray(frameBytes)
        RandomAccessFile(file, "r").use { wav ->
            wav.seek(StudioWavFile.HEADER_BYTES.toLong() + startFrame * frameBytes.toLong())
            for (frame in 0 until frameCount) {
                val read = wav.read(frameBuffer)
                if (read < frameBytes) return output.copyOf(frame)
                var sum = 0f
                var channel = 0
                var offset = 0
                while (channel < info.channelCount) {
                    val sample = (((frameBuffer[offset + 1].toInt() and 0xff) shl 8) or
                        (frameBuffer[offset].toInt() and 0xff)).toShort().toInt()
                    sum += sample / 32768f
                    channel++
                    offset += 2
                }
                output[frame] = sum / info.channelCount.toFloat()
            }
        }
        return output
    }

    private fun sourceToTimelineFrames(sourceFrames: Long, sourceRate: Int, timelineRate: Int): Long =
        (sourceFrames.toDouble() * timelineRate.toDouble() / sourceRate.toDouble()).roundToLong()

    private fun StudioProject.findClip(clipId: String): StudioClip? =
        tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == clipId }

    companion object {
        private const val MAX_ANALYSIS_SECONDS = 45L
    }
}

internal object StudioVocalAlignmentMath {
    fun detectOnsets(mono: FloatArray, sampleRate: Int): List<Long> {
        if (sampleRate <= 0 || mono.size < sampleRate / 4) return emptyList()
        val hop = max(1, sampleRate / 100) // 10 ms
        val buckets = mono.size / hop
        if (buckets < 16) return emptyList()
        val energy = FloatArray(buckets)
        for (bucket in 0 until buckets) {
            val start = bucket * hop
            val end = minOf(mono.size, start + hop)
            var sum = 0.0
            for (i in start until end) sum += abs(mono[i]).toDouble()
            energy[bucket] = (sum / max(1, end - start)).toFloat()
        }
        val novelty = FloatArray(buckets)
        var local = energy.firstOrNull() ?: 0f
        for (i in 1 until buckets) {
            local = local * 0.94f + energy[i - 1] * 0.06f
            novelty[i] = (energy[i] - local).coerceAtLeast(0f)
        }
        val mean = novelty.average().toFloat()
        var variance = 0.0
        for (value in novelty) {
            val delta = value - mean
            variance += delta * delta
        }
        val std = sqrt(variance / novelty.size.coerceAtLeast(1)).toFloat()
        val threshold = mean + std * 0.75f
        val minGapBuckets = 8 // 80 ms
        val result = ArrayList<Long>()
        var lastBucket = -minGapBuckets
        for (i in 1 until novelty.lastIndex) {
            val value = novelty[i]
            if (
                value >= threshold &&
                value >= novelty[i - 1] &&
                value >= novelty[i + 1] &&
                i - lastBucket >= minGapBuckets
            ) {
                result += i.toLong() * hop.toLong()
                lastBucket = i
                if (result.size >= 96) break
            }
        }
        return result
    }

    fun suggestOffset(
        onsetTimelineFrames: List<Long>,
        timelineSampleRate: Int,
        project: StudioProject,
    ): StudioVocalAlignmentSuggestion? {
        if (onsetTimelineFrames.isEmpty() || timelineSampleRate <= 0) return null
        val tempo = project.proSettings.tempo
        if (!tempo.bpm.isFinite() || tempo.bpm <= 0f) return null
        val halfBeatFrames = StudioBeatGrid.framesPerBeat(timelineSampleRate, tempo.bpm) / 2.0
        if (!halfBeatFrames.isFinite() || halfBeatFrames < 1.0) return null
        val maxShiftFrames = minOf(
            (timelineSampleRate * 500L / 1_000L).toDouble(),
            halfBeatFrames * 0.95,
        ).roundToLong().coerceAtLeast(1L)
        val stepFrames = (timelineSampleRate * 10L / 1_000L).coerceAtLeast(1L)

        data class Candidate(val offset: Long, val error: Double, val objective: Double)
        fun averageError(offset: Long): Double {
            var total = 0.0
            for (onset in onsetTimelineFrames) {
                val shifted = onset.toDouble() + offset.toDouble()
                val gridPosition = (shifted - tempo.gridOriginFrame.toDouble()) / halfBeatFrames
                val nearest = gridPosition.roundToLong()
                val target = tempo.gridOriginFrame.toDouble() + nearest * halfBeatFrames
                total += abs(shifted - target)
            }
            return total / onsetTimelineFrames.size.toDouble()
        }

        val before = averageError(0L)
        var best = Candidate(0L, before, before)
        var offset = -maxShiftFrames
        while (offset <= maxShiftFrames) {
            val error = averageError(offset)
            val movementPenalty = abs(offset).toDouble() * 0.035
            val objective = error + movementPenalty
            if (objective < best.objective) best = Candidate(offset, error, objective)
            offset += stepFrames
        }
        if (maxShiftFrames % stepFrames != 0L) {
            for (edge in longArrayOf(-maxShiftFrames, maxShiftFrames)) {
                val error = averageError(edge)
                val objective = error + abs(edge).toDouble() * 0.035
                if (objective < best.objective) best = Candidate(edge, error, objective)
            }
        }

        val improvement = if (before <= 1.0) 0.0 else ((before - best.error) / before).coerceIn(0.0, 1.0)
        val onsetEvidence = (onsetTimelineFrames.size / 8.0).coerceIn(0.25, 1.0)
        val confidence = (improvement * 0.78 + onsetEvidence * 0.22).toFloat().coerceIn(0f, 1f)
        val offsetMillis = (best.offset.toDouble() * 1_000.0 / timelineSampleRate.toDouble()).roundToLong()
        return StudioVocalAlignmentSuggestion(
            offsetMillis = offsetMillis,
            confidence = confidence,
            onsetCount = onsetTimelineFrames.size,
            averageErrorBeforeMillis = (before * 1_000.0 / timelineSampleRate).toFloat(),
            averageErrorAfterMillis = (best.error * 1_000.0 / timelineSampleRate).toFloat(),
        )
    }
}
