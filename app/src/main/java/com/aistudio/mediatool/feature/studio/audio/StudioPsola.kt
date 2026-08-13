package com.aistudio.mediatool.feature.studio.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

/** Small offline PSOLA shifter for monophonic vocal buffers. */
object StudioPsola {
    fun process(
        input: FloatArray,
        sampleRate: Int,
        globalStartFrame: Long,
        plan: StudioPitchPlan,
    ): FloatArray {
        if (input.size < sampleRate / 5) return input.copyOf()
        val detected = StudioPitchDetector.estimate(input, sampleRate)
        val marks = pitchMarks(input, sampleRate, detected)
        if (marks.size < 3) return input.copyOf()

        val sum = FloatArray(input.size)
        val weight = FloatArray(input.size)
        var sourceIndex = 0
        var synthesis = marks.first().toDouble()
        while (synthesis < input.size) {
            val targetSample = synthesis.roundToInt().coerceIn(0, input.lastIndex)
            while (
                sourceIndex + 1 < marks.size &&
                abs(marks[sourceIndex + 1] - targetSample) <= abs(marks[sourceIndex] - targetSample)
            ) sourceIndex++
            val sourceMark = marks[sourceIndex]
            val period = localPeriod(marks, sourceIndex).coerceIn(sampleRate / 600, sampleRate / 65)
            val factor = factorAt(plan, globalStartFrame + targetSample)
            val radius = period.coerceAtLeast(8)
            for (offset in -radius..radius) {
                val source = sourceMark + offset
                val target = targetSample + offset
                if (source !in input.indices || target !in input.indices) continue
                val phase = (offset + radius).toDouble() / (2.0 * radius).coerceAtLeast(1.0)
                val window = (0.5 - 0.5 * cos(2.0 * PI * phase)).toFloat()
                sum[target] += input[source] * window
                weight[target] += window
            }
            synthesis += period.toDouble() / factor.coerceIn(0.5f, 2f)
        }

        return FloatArray(input.size) { index ->
            if (weight[index] > 0.12f) {
                (sum[index] / weight[index]).coerceIn(-1f, 1f)
            } else input[index]
        }
    }

    private fun pitchMarks(
        input: FloatArray,
        sampleRate: Int,
        frames: List<StudioDetectedPitchFrame>,
    ): IntArray {
        if (frames.isEmpty()) return IntArray(0)
        val marks = ArrayList<Int>()
        var position = frames.firstOrNull { it.frequencyHz != null }?.startSample ?: return IntArray(0)
        while (position < input.size) {
            val frame = frames.lastOrNull { it.startSample <= position && it.frequencyHz != null }
                ?: frames.firstOrNull { it.startSample >= position && it.frequencyHz != null }
                ?: break
            val hz = frame.frequencyHz ?: break
            val period = (sampleRate / hz).roundToInt().coerceIn(sampleRate / 600, sampleRate / 65)
            val searchRadius = max(2, period / 4)
            val left = (position - searchRadius).coerceAtLeast(0)
            val right = (position + searchRadius).coerceAtMost(input.lastIndex)
            var mark = position.coerceIn(left, right)
            var peak = -1f
            for (candidate in left..right) {
                val value = abs(input[candidate])
                if (value > peak) {
                    peak = value
                    mark = candidate
                }
            }
            if (marks.isEmpty() || mark > marks.last()) marks += mark
            position = mark + period
            val nextVoiced = frames.firstOrNull { it.startSample >= position && it.frequencyHz != null }
            if (nextVoiced != null && nextVoiced.startSample - position > period * 3) {
                position = nextVoiced.startSample
            }
        }
        return marks.toIntArray()
    }

    private fun localPeriod(marks: IntArray, index: Int): Int = when {
        marks.size < 2 -> 160
        index == 0 -> marks[1] - marks[0]
        index == marks.lastIndex -> marks[index] - marks[index - 1]
        else -> ((marks[index + 1] - marks[index - 1]) / 2).coerceAtLeast(1)
    }

    private fun factorAt(plan: StudioPitchPlan, frame: Long): Float =
        plan.segments.firstOrNull { frame >= it.startFrame && frame < it.endFrame }?.pitchFactor ?: 1f
}
