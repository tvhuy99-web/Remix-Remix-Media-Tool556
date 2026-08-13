package com.aistudio.mediatool.feature.studio.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

internal object StudioPitchPlanSupport {
    fun build(
        mono: FloatArray,
        analysisRate: Int,
        sourceRate: Int,
        sourceFrames: Long,
        strength: Float,
        maxCents: Float,
        bucketCents: Float,
        targetMidi: (Float) -> Float,
    ): StudioPitchPlan {
        require(analysisRate > 0 && sourceRate > 0 && sourceFrames > 0L)
        val detected = StudioPitchDetector.estimate(mono, analysisRate)
        if (detected.isEmpty()) return passthrough(sourceFrames)
        val smoothed = detected.indices.map { medianMidi(detected, it) }
        val output = ArrayList<StudioPitchSegment>()
        var cursor = 0L
        var voicedSamples = 0L
        var confidenceTotal = 0.0
        var voicedCount = 0

        detected.forEachIndexed { index, frame ->
            val start = scaleFrame(frame.startSample.toLong(), analysisRate, sourceRate).coerceIn(0L, sourceFrames)
            val end = scaleFrame(frame.endSample.toLong(), analysisRate, sourceRate).coerceIn(start, sourceFrames)
            if (end <= start) return@forEachIndexed
            if (start > cursor) append(output, StudioPitchSegment(cursor, start, 1f, 0f))
            val midi = smoothed[index]
            val voiced = midi != null && frame.confidence >= 0.22f
            val cents = if (voiced) {
                ((targetMidi(midi!!) - midi) * 100f).coerceIn(-maxCents, maxCents) * strength
            } else 0f
            val bucket = if (!voiced || abs(cents) < 4f) 0f else (cents / bucketCents).roundToInt() * bucketCents
            append(
                output,
                StudioPitchSegment(
                    startFrame = max(cursor, start),
                    endFrame = end,
                    pitchFactor = StudioPitchTheory.factorForCents(bucket),
                    confidence = frame.confidence,
                ),
            )
            cursor = end
            if (voiced) {
                voicedSamples += (frame.endSample - frame.startSample).coerceAtLeast(0)
                confidenceTotal += frame.confidence
                voicedCount++
            }
        }
        if (cursor < sourceFrames) append(output, StudioPitchSegment(cursor, sourceFrames, 1f, 0f))
        val analyzed = detected.sumOf { (it.endSample - it.startSample).coerceAtLeast(0).toLong() }.coerceAtLeast(1L)
        return StudioPitchPlan(
            segments = clean(output, sourceRate),
            voicedCoverage = (voicedSamples.toDouble() / analyzed).toFloat().coerceIn(0f, 1f),
            averageConfidence = if (voicedCount > 0) (confidenceTotal / voicedCount).toFloat() else 0f,
        )
    }

    private fun append(list: MutableList<StudioPitchSegment>, next: StudioPitchSegment) {
        if (next.endFrame <= next.startFrame) return
        val previous = list.lastOrNull()
        if (previous != null && previous.endFrame == next.startFrame && centsApart(previous, next) <= 12f) {
            list[list.lastIndex] = previous.copy(
                endFrame = next.endFrame,
                confidence = max(previous.confidence, next.confidence),
            )
        } else list += next
    }

    private fun clean(input: List<StudioPitchSegment>, rate: Int): List<StudioPitchSegment> {
        if (input.size < 3) return input
        val minimum = (rate * 0.045f).roundToInt().toLong().coerceAtLeast(1L)
        val result = input.toMutableList()
        var index = 1
        while (index < result.lastIndex) {
            val current = result[index]
            if (current.endFrame - current.startFrame < minimum) {
                val previous = result[index - 1]
                val next = result[index + 1]
                if (centsApart(previous, current) <= centsApart(next, current)) {
                    result[index - 1] = previous.copy(endFrame = current.endFrame)
                } else {
                    result[index + 1] = next.copy(startFrame = current.startFrame)
                }
                result.removeAt(index)
            } else index++
        }
        return result
    }

    private fun centsApart(left: StudioPitchSegment, right: StudioPitchSegment): Float =
        abs(1200f * ((ln(left.pitchFactor.toDouble()) - ln(right.pitchFactor.toDouble())) / ln(2.0)).toFloat())

    private fun medianMidi(frames: List<StudioDetectedPitchFrame>, index: Int): Float? {
        val values = ArrayList<Float>(5)
        for (candidate in (index - 2).coerceAtLeast(0)..(index + 2).coerceAtMost(frames.lastIndex)) {
            val frame = frames[candidate]
            val hz = frame.frequencyHz ?: continue
            if (frame.confidence >= 0.18f) values += StudioPitchTheory.midiForHz(hz)
        }
        if (values.isEmpty()) return null
        values.sort()
        return values[values.size / 2]
    }

    private fun passthrough(frames: Long) = StudioPitchPlan(
        segments = listOf(StudioPitchSegment(0L, frames, 1f, 0f)),
        voicedCoverage = 0f,
        averageConfidence = 0f,
    )

    private fun scaleFrame(value: Long, fromRate: Int, toRate: Int): Long =
        if (fromRate == toRate) value else (value * toRate.toLong() + fromRate / 2L) / fromRate.toLong()
}
