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
    ): StudioPlaybackPlan {
        val hasSolo = project.tracks.any { it.solo }
        val beatTrack = project.tracks.firstOrNull { it.type == StudioTrackType.BEAT }
        val monitorTracks = project.tracks.filter { it.type != StudioTrackType.BEAT }
        val clips = buildList {
            monitorTracks
                .filter { !it.muted && (!hasSolo || it.solo) }
                .forEach { track ->
                    val arrangement = if (track.clips.isNotEmpty()) {
                        track.clips
                    } else {
                        activeTake(track)?.let { listOf(fullTakeClip(it)) }.orEmpty()
                    }
                    arrangement.forEach { clip ->
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
                                pan = track.pan.coerceIn(-1f, 1f),
                                fadeInFrames = clip.fadeInFrames,
                                fadeOutFrames = clip.fadeOutFrames,
                            ),
                        )
                    }
                }
        }
        val beatAudible = beatTrack?.let { !it.muted && (!hasSolo || it.solo) } ?: false
        return StudioPlaybackPlan(
            clips = clips,
            beatGainDb = beatTrack?.volumeDb?.coerceIn(-60f, 18f) ?: 0f,
            beatPan = beatTrack?.pan?.coerceIn(-1f, 1f) ?: 0f,
            beatMuted = !beatAudible,
            masterGainDb = project.masterMix.gainDb.coerceIn(-24f, 12f),
            limiterEnabled = project.masterMix.limiterEnabled,
        )
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