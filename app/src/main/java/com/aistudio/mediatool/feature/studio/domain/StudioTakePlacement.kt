package com.aistudio.mediatool.feature.studio.domain

import kotlin.math.roundToLong

data class StudioTakePlacement(
    val timelineStartFrame: Long,
    val sourceStartFrame: Long,
    val sourceEndFrame: Long,
) {
    val sourceLengthFrames: Long
        get() = (sourceEndFrame - sourceStartFrame).coerceAtLeast(0L)
}

/**
 * Places a dry Take on the project timeline after round-trip latency compensation.
 * If compensation would move the Take before frame zero, the unavailable negative
 * timeline region is trimmed from the beginning of the source instead.
 */
fun StudioTake.latencyCompensatedPlacement(projectSampleRate: Int): StudioTakePlacement {
    val projectRate = projectSampleRate.coerceAtLeast(1)
    val sourceRate = inputSampleRate.coerceAtLeast(1)
    val rawTimelineStart = recordedTimelineFrame - latencyCompensationFrames.coerceAtLeast(0L)
    val timelineStart = rawTimelineStart.coerceAtLeast(0L)
    val negativeTimelineFrames = (-rawTimelineStart).coerceAtLeast(0L)
    val sourceTrim = (negativeTimelineFrames.toDouble() * sourceRate.toDouble() / projectRate.toDouble())
        .roundToLong()
        .coerceIn(0L, recordedFrames.coerceAtLeast(0L))
    return StudioTakePlacement(
        timelineStartFrame = timelineStart,
        sourceStartFrame = sourceTrim,
        sourceEndFrame = recordedFrames.coerceAtLeast(sourceTrim),
    )
}
