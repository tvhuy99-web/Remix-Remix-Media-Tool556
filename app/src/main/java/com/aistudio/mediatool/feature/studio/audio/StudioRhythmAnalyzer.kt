package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.data.StudioWaveformStore
import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode
import java.io.File
import java.io.FileInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StudioRhythmSuggestion(
    val bpm: Float?,
    val bpmConfidence: Float,
    val root: StudioPitchClass?,
    val scale: StudioScaleMode?,
    val keyConfidence: Float,
) {
    val hasTempo: Boolean get() = bpm != null
    val hasKey: Boolean get() = root != null && scale != null
}

class StudioRhythmAnalyzer(
    context: Context,
    private val repository: StudioProjectRepository,
) {
    private val appContext = context.applicationContext

    suspend fun analyze(projectId: String): StudioRhythmSuggestion = withContext(Dispatchers.IO) {
        val prepared = StudioBeatPreparer(
            context = appContext,
            repository = repository,
            waveformStore = StudioWaveformStore(appContext),
        ).prepare(projectId)
        val mono = readPreparedBeatMono(
            file = prepared.pcmFile,
            maxFrames = prepared.project.timelineSampleRate.toLong() * MAX_ANALYSIS_SECONDS,
        )
        require(mono.size >= prepared.project.timelineSampleRate) {
            "Nhạc nền quá ngắn để phân tích nhịp và tông"
        }
        val tempo = StudioRhythmAnalysisMath.estimateBpm(mono, prepared.project.timelineSampleRate)
        val key = StudioRhythmAnalysisMath.estimateKey(mono, prepared.project.timelineSampleRate)
        StudioRhythmSuggestion(
            bpm = tempo?.bpm,
            bpmConfidence = tempo?.confidence ?: 0f,
            root = key?.root,
            scale = key?.scale,
            keyConfidence = key?.confidence ?: 0f,
        )
    }

    private fun readPreparedBeatMono(file: File, maxFrames: Long): FloatArray {
        require(file.isFile && file.length() >= 4L) { "Không tìm thấy PCM beat để phân tích" }
        val availableFrames = file.length() / 4L
        val frames = minOf(availableFrames, maxFrames.coerceAtLeast(1L), Int.MAX_VALUE.toLong()).toInt()
        val output = FloatArray(frames)
        FileInputStream(file).buffered(256 * 1024).use { input ->
            val buffer = ByteArray(256 * 1024)
            var frameIndex = 0
            var carry = ByteArray(0)
            while (frameIndex < frames) {
                val read = input.read(buffer)
                if (read <= 0) break
                val data = if (carry.isEmpty()) {
                    buffer.copyOf(read)
                } else {
                    carry + buffer.copyOf(read)
                }
                val usable = data.size - (data.size % 4)
                var offset = 0
                while (offset < usable && frameIndex < frames) {
                    val left = pcm16(data[offset], data[offset + 1])
                    val right = pcm16(data[offset + 2], data[offset + 3])
                    output[frameIndex++] = (left + right) * (0.5f / 32768f)
                    offset += 4
                }
                carry = if (usable < data.size) data.copyOfRange(usable, data.size) else ByteArray(0)
            }
            return if (frameIndex == output.size) output else output.copyOf(frameIndex)
        }
    }

    private fun pcm16(low: Byte, high: Byte): Int =
        (((high.toInt() and 0xff) shl 8) or (low.toInt() and 0xff)).toShort().toInt()

    companion object {
        private const val MAX_ANALYSIS_SECONDS = 60L
    }
}

internal object StudioRhythmAnalysisMath {
    data class TempoEstimate(val bpm: Float, val confidence: Float)
    data class KeyEstimate(
        val root: StudioPitchClass,
        val scale: StudioScaleMode,
        val confidence: Float,
    )

    fun estimateBpm(
        mono: FloatArray,
        sampleRate: Int,
        minBpm: Int = 60,
        maxBpm: Int = 200,
    ): TempoEstimate? {
        if (sampleRate <= 0 || mono.size < sampleRate || minBpm <= 0 || maxBpm <= minBpm) return null
        val hop = max(1, sampleRate / 100) // 10 ms envelope
        val envelopeSize = mono.size / hop
        if (envelopeSize < 200) return null
        val energy = FloatArray(envelopeSize)
        var index = 0
        for (bucket in 0 until envelopeSize) {
            var sum = 0.0
            val end = minOf(mono.size, index + hop)
            while (index < end) {
                sum += abs(mono[index]).toDouble()
                index++
            }
            energy[bucket] = (sum / max(1, end - (bucket * hop))).toFloat()
        }

        val onset = FloatArray(envelopeSize)
        var smooth = energy.firstOrNull() ?: 0f
        var onsetEnergy = 0.0
        for (i in 1 until envelopeSize) {
            smooth = smooth * 0.96f + energy[i - 1] * 0.04f
            val value = (energy[i] - smooth).coerceAtLeast(0f)
            onset[i] = value
            onsetEnergy += value * value
        }
        if (onsetEnergy <= 1e-8) return null

        val envelopeRate = sampleRate.toDouble() / hop.toDouble()
        val minLag = (envelopeRate * 60.0 / maxBpm).roundToInt().coerceAtLeast(1)
        val maxLag = (envelopeRate * 60.0 / minBpm).roundToInt().coerceAtMost(envelopeSize / 2)
        if (maxLag <= minLag) return null

        data class Candidate(val lag: Int, val score: Double)
        val candidates = ArrayList<Candidate>(maxLag - minLag + 1)
        for (lag in minLag..maxLag) {
            var dot = 0.0
            var leftNorm = 0.0
            var rightNorm = 0.0
            for (i in lag until onset.size) {
                val left = onset[i].toDouble()
                val right = onset[i - lag].toDouble()
                dot += left * right
                leftNorm += left * left
                rightNorm += right * right
            }
            val denom = sqrt(leftNorm * rightNorm).coerceAtLeast(1e-12)
            candidates += Candidate(lag, dot / denom)
        }
        val rawBest = candidates.maxByOrNull { it.score } ?: return null
        val nearBest = candidates
            .filter { it.score >= rawBest.score * 0.97 }
            .minByOrNull { it.lag } ?: rawBest
        val best = nearBest
        if (!best.score.isFinite() || best.score < 0.05) return null

        val second = candidates
            .asSequence()
            .filter { abs(it.lag - best.lag) > 2 }
            .filter { candidate ->
                val ratio = candidate.lag.toDouble() / best.lag.toDouble()
                abs(ratio - 2.0) > 0.06 && abs(ratio - 0.5) > 0.06
            }
            .maxOfOrNull { it.score } ?: 0.0
        val separation = ((best.score - second) / best.score.coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        val peakStrength = ((best.score - 0.05) / 0.55).coerceIn(0.0, 1.0)
        val confidence = (0.62 * peakStrength + 0.38 * separation).toFloat().coerceIn(0f, 1f)
        val bpm = (60.0 * envelopeRate / best.lag.toDouble()).toFloat().coerceIn(minBpm.toFloat(), maxBpm.toFloat())
        return TempoEstimate(bpm = bpm, confidence = confidence)
    }

    fun estimateKey(mono: FloatArray, sampleRate: Int): KeyEstimate? {
        if (sampleRate <= 0 || mono.size < sampleRate) return null
        val windowSize = 4096.coerceAtMost(mono.size)
        if (windowSize < 1024) return null
        val step = max(windowSize, sampleRate / 2)
        val chroma = DoubleArray(12)
        var usefulWindows = 0
        var start = 0
        while (start + windowSize <= mono.size && usefulWindows < 96) {
            var rms = 0.0
            for (i in 0 until windowSize) {
                val sample = mono[start + i].toDouble()
                rms += sample * sample
            }
            rms = sqrt(rms / windowSize.toDouble())
            if (rms > 0.004) {
                for (midi in 36..95) {
                    val frequency = 440.0 * 2.0.pow((midi - 69) / 12.0)
                    if (frequency >= sampleRate * 0.46) continue
                    val power = goertzelPower(mono, start, windowSize, sampleRate, frequency)
                    val pitchClass = Math.floorMod(midi, 12)
                    chroma[pitchClass] += sqrt(power.coerceAtLeast(0.0))
                }
                usefulWindows++
            }
            start += step
        }
        if (usefulWindows < 2 || chroma.sum() <= 1e-9) return null

        val norm = sqrt(chroma.sumOf { it * it }).coerceAtLeast(1e-12)
        for (i in chroma.indices) chroma[i] /= norm

        data class Candidate(val root: Int, val scale: StudioScaleMode, val score: Double)
        val candidates = buildList {
            for (root in 0 until 12) {
                add(Candidate(root, StudioScaleMode.MAJOR, profileScore(chroma, MAJOR_PROFILE, root)))
                add(Candidate(root, StudioScaleMode.MINOR, profileScore(chroma, MINOR_PROFILE, root)))
            }
        }.sortedByDescending { it.score }
        val best = candidates.firstOrNull() ?: return null
        val second = candidates.drop(1).firstOrNull()?.score ?: 0.0
        if (!best.score.isFinite() || best.score <= 0.0) return null
        val separation = ((best.score - second) / abs(best.score).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
        val confidence = (separation * 2.2).coerceIn(0.0, 1.0).toFloat()
        return KeyEstimate(
            root = StudioPitchClass.entries[best.root],
            scale = best.scale,
            confidence = confidence,
        )
    }

    private fun goertzelPower(
        samples: FloatArray,
        offset: Int,
        size: Int,
        sampleRate: Int,
        frequency: Double,
    ): Double {
        val omega = 2.0 * PI * frequency / sampleRate.toDouble()
        val coefficient = 2.0 * cos(omega)
        var s0: Double
        var s1 = 0.0
        var s2 = 0.0
        for (i in 0 until size) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (size - 1).toDouble())
            s0 = samples[offset + i].toDouble() * window + coefficient * s1 - s2
            s2 = s1
            s1 = s0
        }
        val real = s1 - s2 * cos(omega)
        val imag = s2 * sin(omega)
        return real * real + imag * imag
    }

    private fun profileScore(chroma: DoubleArray, profile: DoubleArray, root: Int): Double {
        var dot = 0.0
        var profileNorm = 0.0
        for (i in 0 until 12) {
            val weight = profile[Math.floorMod(i - root, 12)]
            dot += chroma[i] * weight
            profileNorm += weight * weight
        }
        return dot / sqrt(profileNorm).coerceAtLeast(1e-12)
    }

    private val MAJOR_PROFILE = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR_PROFILE = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
}
