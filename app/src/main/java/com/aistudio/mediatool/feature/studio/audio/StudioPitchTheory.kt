package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class StudioHarmonyPreset(
    val degreeOffset: Int,
    val label: String,
) {
    THIRD_ABOVE(2, "Bè quãng 3 trên"),
    THIRD_BELOW(-2, "Bè quãng 3 dưới"),
    FIFTH_ABOVE(4, "Bè quãng 5 trên"),
}

object StudioPitchTheory {
    fun midiForHz(hz: Float): Float =
        (69.0 + 12.0 * ln(hz.coerceAtLeast(1f).toDouble() / 440.0) / ln(2.0)).toFloat()

    fun hzForMidi(midi: Float): Float =
        (440.0 * 2.0.pow((midi.toDouble() - 69.0) / 12.0)).toFloat()

    fun nearestScaleMidi(
        midi: Float,
        root: StudioPitchClass,
        scale: StudioScaleMode,
    ): Float {
        val allowed = allowedPitchClasses(root, scale)
        val center = midi.roundToInt()
        var best = center
        var bestDistance = Float.MAX_VALUE
        for (candidate in center - 12..center + 12) {
            if (Math.floorMod(candidate, 12) !in allowed) continue
            val distance = abs(candidate - midi)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
        }
        return best.toFloat()
    }

    fun harmonyTargetMidi(
        midi: Float,
        root: StudioPitchClass,
        scale: StudioScaleMode,
        preset: StudioHarmonyPreset,
    ): Float {
        val allowed = allowedPitchClasses(root, scale)
        val center = midi.roundToInt()
        val notes = (center - 36..center + 36).filter { Math.floorMod(it, 12) in allowed }
        val sourceIndex = notes.indices.minByOrNull { abs(notes[it] - midi) } ?: return midi
        val targetIndex = (sourceIndex + preset.degreeOffset).coerceIn(0, notes.lastIndex)
        return notes[targetIndex].toFloat()
    }

    fun factorForCents(cents: Float): Float =
        2.0.pow(cents.toDouble() / 1200.0).toFloat()

    private fun allowedPitchClasses(
        root: StudioPitchClass,
        scale: StudioScaleMode,
    ): Set<Int> {
        val intervals = when (scale) {
            StudioScaleMode.MAJOR -> intArrayOf(0, 2, 4, 5, 7, 9, 11)
            StudioScaleMode.MINOR -> intArrayOf(0, 2, 3, 5, 7, 8, 10)
        }
        return intervals.mapTo(linkedSetOf()) { Math.floorMod(root.ordinal + it, 12) }
    }
}
