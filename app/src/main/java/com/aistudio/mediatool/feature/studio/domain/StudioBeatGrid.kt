package com.aistudio.mediatool.feature.studio.domain

import kotlin.math.ceil
import kotlin.math.roundToLong

/** A timeline beat marker derived only from persisted tempo/grid metadata. */
data class StudioBeatMarker(
    val frame: Long,
    val beatIndex: Long,
    val barIndex: Long,
    val beatInBar: Int,
) {
    val isBarStart: Boolean
        get() = beatInBar == 1
}

object StudioBeatGrid {
    fun framesPerBeat(sampleRate: Int, bpm: Float): Double {
        require(sampleRate > 0) { "Sample rate phải lớn hơn 0" }
        require(bpm.isFinite() && bpm > 0f) { "BPM phải lớn hơn 0" }
        return sampleRate.toDouble() * 60.0 / bpm.toDouble()
    }

    fun frameForBeat(
        beatIndex: Long,
        sampleRate: Int,
        tempo: StudioTempoSettings,
    ): Long = (tempo.gridOriginFrame.toDouble() + beatIndex * framesPerBeat(sampleRate, tempo.bpm))
        .roundToLong()

    fun nearestBeat(
        frame: Long,
        sampleRate: Int,
        tempo: StudioTempoSettings,
    ): StudioBeatMarker {
        val safeFrame = frame.coerceAtLeast(0L)
        val framesPerBeat = framesPerBeat(sampleRate, tempo.bpm)
        val beatIndex = ((safeFrame - tempo.gridOriginFrame).toDouble() / framesPerBeat).roundToLong()
        val beatFrame = frameForBeat(beatIndex, sampleRate, tempo).coerceAtLeast(0L)
        return marker(beatFrame, beatIndex, tempo.beatsPerBar)
    }

    fun markersBetween(
        startFrame: Long,
        endFrame: Long,
        sampleRate: Int,
        tempo: StudioTempoSettings,
        maxMarkers: Int = 4_096,
    ): List<StudioBeatMarker> {
        require(maxMarkers in 1..100_000) { "Giới hạn beat-grid không hợp lệ" }
        val start = startFrame.coerceAtLeast(0L)
        val end = endFrame.coerceAtLeast(start)
        val framesPerBeat = framesPerBeat(sampleRate, tempo.bpm)
        val firstIndex = ceil((start - tempo.gridOriginFrame).toDouble() / framesPerBeat).toLong()
        return buildList {
            var beatIndex = firstIndex
            while (size < maxMarkers) {
                val frame = frameForBeat(beatIndex, sampleRate, tempo)
                if (frame > end) break
                if (frame >= start && frame >= 0L) {
                    add(marker(frame, beatIndex, tempo.beatsPerBar))
                }
                beatIndex++
            }
        }
    }

    private fun marker(frame: Long, beatIndex: Long, beatsPerBar: Int): StudioBeatMarker {
        val safeBeatsPerBar = beatsPerBar.coerceAtLeast(1)
        val barIndex = Math.floorDiv(beatIndex, safeBeatsPerBar.toLong())
        val beatInBar = Math.floorMod(beatIndex, safeBeatsPerBar.toLong()).toInt() + 1
        return StudioBeatMarker(
            frame = frame,
            beatIndex = beatIndex,
            barIndex = barIndex,
            beatInBar = beatInBar,
        )
    }
}
