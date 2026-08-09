package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.data.StudioProjectRepository
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType

/** Builds the validated PCM/WAV monitor snapshot before the realtime callback sees it. */
object StudioPlaybackPlanner {
    fun build(
        project: StudioProject,
        repository: StudioProjectRepository,
    ): List<StudioPlaybackClip> {
        val monitorTracks = project.tracks.filter { it.type != StudioTrackType.BEAT }
        val hasSolo = monitorTracks.any { it.solo }
        return buildList {
            monitorTracks
                .filter { !it.muted && (!hasSolo || it.solo) }
                .forEach { track ->
                    val clips = if (track.clips.isNotEmpty()) {
                        track.clips
                    } else {
                        activeTake(track)?.let { listOf(fullTakeClip(it)) }.orEmpty()
                    }
                    clips.forEach { clip ->
                        val file = requireNotNull(repository.assetFile(project.id, clip.sourceAssetId)) {
                            "Không tìm thấy audio asset cho clip ${clip.id}"
                        }
                        add(
                            StudioPlaybackClip(
                                file = file,
                                timelineStartFrame = clip.timelineStartFrame,
                                sourceStartFrame = clip.sourceStartFrame,
                                sourceEndFrame = clip.sourceEndFrame,
                                gainDb = (clip.gainDb + track.volumeDb).coerceIn(-60f, 18f),
                                fadeInFrames = clip.fadeInFrames,
                                fadeOutFrames = clip.fadeOutFrames,
                            ),
                        )
                    }
                }
        }
    }

    private fun activeTake(track: StudioTrack): StudioTake? =
        track.activeTakeId?.let { id -> track.takes.firstOrNull { it.id == id } }
            ?: track.takes.lastOrNull()

    private fun fullTakeClip(take: StudioTake): StudioClip = StudioClip(
        id = "monitor-${take.id}",
        sourceAssetId = take.assetId,
        sourceTakeId = take.id,
        timelineStartFrame = (take.recordedTimelineFrame - take.latencyCompensationFrames).coerceAtLeast(0L),
        sourceStartFrame = 0L,
        sourceEndFrame = take.recordedFrames,
    )
}
