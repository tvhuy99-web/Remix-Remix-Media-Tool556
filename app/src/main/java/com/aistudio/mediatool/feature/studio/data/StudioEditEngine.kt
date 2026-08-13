package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement
import java.util.UUID
import kotlin.math.roundToLong

/** Pure, non-destructive transformations for Studio arrangement editing. */
object StudioEditEngine {
    data class EditResult(
        val project: StudioProject,
        val selectedClipId: String? = null,
    )

    fun materializeTrack(project: StudioProject, trackId: String): EditResult {
        val trackIndex = project.tracks.indexOfFirst { it.id == trackId }
        require(trackIndex >= 0) { "Không tìm thấy track Studio" }
        val track = project.tracks[trackIndex]
        if (track.clips.isNotEmpty()) return EditResult(project, track.clips.firstOrNull()?.id)
        return useActiveTakeAsArrangement(project, trackId)
    }

    fun useActiveTakeAsArrangement(project: StudioProject, trackId: String): EditResult {
        val trackIndex = project.tracks.indexOfFirst { it.id == trackId }
        require(trackIndex >= 0) { "Không tìm thấy track Studio" }
        val track = project.tracks[trackIndex]
        val take = requireNotNull(activeTake(track)) { "Track chưa có Active Take" }
        val clip = fullTakeClip(project, take)
        return EditResult(project.replaceTrack(trackIndex, track.copy(clips = listOf(clip))), clip.id)
    }

    fun split(project: StudioProject, clipId: String, timelineFrame: Long): EditResult {
        val location = requireClip(project, clipId)
        requireTimelineInside(project, location.clip, timelineFrame, strictStart = true, strictEnd = true)
        val clip = location.clip
        val sourceAt = sourceFrameAtTimeline(project, clip, timelineFrame)
        require(sourceAt > clip.sourceStartFrame && sourceAt < clip.sourceEndFrame) {
            "Playhead phải nằm bên trong clip để Split"
        }
        val leftLength = sourceAt - clip.sourceStartFrame
        val rightLength = clip.sourceEndFrame - sourceAt
        val left = clip.copy(
            id = UUID.randomUUID().toString(),
            sourceEndFrame = sourceAt,
            fadeInFrames = clip.fadeInFrames.coerceAtMost(leftLength),
            fadeOutFrames = safetyFadeFrames(project, clip, leftLength),
        )
        val right = clip.copy(
            id = UUID.randomUUID().toString(),
            timelineStartFrame = timelineFrame,
            sourceStartFrame = sourceAt,
            fadeInFrames = safetyFadeFrames(project, clip, rightLength),
            fadeOutFrames = clip.fadeOutFrames.coerceAtMost(rightLength),
        )
        val clips = location.track.clips.toMutableList().apply {
            removeAt(location.clipIndex)
            add(location.clipIndex, left)
            add(location.clipIndex + 1, right)
        }
        return EditResult(
            project.replaceTrack(location.trackIndex, location.track.copy(clips = clips)),
            selectedClipId = right.id,
        )
    }

    fun trimStart(project: StudioProject, clipId: String, timelineFrame: Long): EditResult {
        val location = requireClip(project, clipId)
        requireTimelineInside(project, location.clip, timelineFrame, strictStart = false, strictEnd = true)
        val sourceAt = sourceFrameAtTimeline(project, location.clip, timelineFrame)
        require(sourceAt >= location.clip.sourceStartFrame && sourceAt < location.clip.sourceEndFrame) {
            "Playhead không nằm trong clip"
        }
        val length = location.clip.sourceEndFrame - sourceAt
        val updated = location.clip.copy(
            timelineStartFrame = timelineFrame,
            sourceStartFrame = sourceAt,
            fadeInFrames = maxOf(
                location.clip.fadeInFrames.coerceAtMost(length),
                safetyFadeFrames(project, location.clip, length),
            ),
            fadeOutFrames = location.clip.fadeOutFrames.coerceAtMost(length),
        )
        return replaceClip(project, location, updated)
    }

    fun trimEnd(project: StudioProject, clipId: String, timelineFrame: Long): EditResult {
        val location = requireClip(project, clipId)
        requireTimelineInside(project, location.clip, timelineFrame, strictStart = true, strictEnd = false)
        val sourceAt = sourceFrameAtTimeline(project, location.clip, timelineFrame)
        require(sourceAt > location.clip.sourceStartFrame && sourceAt <= location.clip.sourceEndFrame) {
            "Playhead không nằm trong clip"
        }
        val length = sourceAt - location.clip.sourceStartFrame
        val updated = location.clip.copy(
            sourceEndFrame = sourceAt,
            fadeInFrames = location.clip.fadeInFrames.coerceAtMost(length),
            fadeOutFrames = maxOf(
                location.clip.fadeOutFrames.coerceAtMost(length),
                safetyFadeFrames(project, location.clip, length),
            ),
        )
        return replaceClip(project, location, updated)
    }

    fun move(project: StudioProject, clipId: String, deltaTimelineFrames: Long): EditResult {
        val location = requireClip(project, clipId)
        val updated = location.clip.copy(
            timelineStartFrame = (location.clip.timelineStartFrame + deltaTimelineFrames).coerceAtLeast(0L),
        )
        return replaceClip(project, location, updated)
    }

    fun delete(project: StudioProject, clipId: String): EditResult {
        val location = requireClip(project, clipId)
        val clips = location.track.clips.toMutableList().apply { removeAt(location.clipIndex) }
        val previousIndex = location.clipIndex - 1
        if (previousIndex in clips.indices) {
            val previous = clips[previousIndex]
            val length = (previous.sourceEndFrame - previous.sourceStartFrame).coerceAtLeast(0L)
            clips[previousIndex] = previous.copy(
                fadeOutFrames = maxOf(previous.fadeOutFrames, safetyFadeFrames(project, previous, length))
                    .coerceAtMost(length),
            )
        }
        val nextIndex = location.clipIndex
        if (nextIndex in clips.indices) {
            val next = clips[nextIndex]
            val length = (next.sourceEndFrame - next.sourceStartFrame).coerceAtLeast(0L)
            clips[nextIndex] = next.copy(
                fadeInFrames = maxOf(next.fadeInFrames, safetyFadeFrames(project, next, length))
                    .coerceAtMost(length),
            )
        }
        val nextSelection = clips.getOrNull(location.clipIndex.coerceAtMost(clips.lastIndex.coerceAtLeast(0)))?.id
        return EditResult(project.replaceTrack(location.trackIndex, location.track.copy(clips = clips)), nextSelection)
    }

    fun setGain(project: StudioProject, clipId: String, gainDb: Float): EditResult {
        val location = requireClip(project, clipId)
        return replaceClip(project, location, location.clip.copy(gainDb = gainDb.coerceIn(-60f, 18f)))
    }

    fun setFades(
        project: StudioProject,
        clipId: String,
        fadeInFrames: Long,
        fadeOutFrames: Long,
    ): EditResult {
        val location = requireClip(project, clipId)
        val length = (location.clip.sourceEndFrame - location.clip.sourceStartFrame).coerceAtLeast(0L)
        val safeIn = fadeInFrames.coerceIn(0L, length)
        val safeOut = fadeOutFrames.coerceIn(0L, (length - safeIn).coerceAtLeast(0L))
        return replaceClip(
            project,
            location,
            location.clip.copy(fadeInFrames = safeIn, fadeOutFrames = safeOut),
        )
    }

    /** Replaces [punchStart, punchEnd) while preserving all source audio and takes. */
    fun replacePunchRange(
        project: StudioProject,
        trackId: String,
        newTakeId: String,
        punchStart: Long,
        punchEnd: Long,
        recordedTakeStart: Long,
    ): EditResult {
        require(punchStart >= 0L && punchEnd > punchStart) { "Vùng Punch không hợp lệ" }
        val materialized = materializeTrack(project, trackId).project
        val trackIndex = materialized.tracks.indexOfFirst { it.id == trackId }
        require(trackIndex >= 0) { "Không tìm thấy vocal track" }
        val track = materialized.tracks[trackIndex]
        val take = requireNotNull(track.takes.firstOrNull { it.id == newTakeId }) { "Không tìm thấy Punch take" }
        val asset = requireNotNull(materialized.asset(take.assetId)) { "Punch take thiếu audio asset" }
        val sourceRate = asset.sampleRate ?: take.inputSampleRate
        require(sourceRate > 0) { "Sample rate Punch take không hợp lệ" }

        val retained = buildList {
            track.clips.forEach { clip ->
                val clipStart = clip.timelineStartFrame
                val clipEnd = timelineEnd(materialized, clip)
                if (clipEnd <= punchStart || clipStart >= punchEnd) {
                    add(clip)
                    return@forEach
                }
                if (clipStart < punchStart) {
                    val leftEnd = sourceFrameAtTimeline(materialized, clip, punchStart)
                    if (leftEnd > clip.sourceStartFrame) {
                        val leftLength = leftEnd - clip.sourceStartFrame
                        add(
                            clip.copy(
                                sourceEndFrame = leftEnd,
                                fadeInFrames = clip.fadeInFrames.coerceAtMost(leftLength),
                                fadeOutFrames = safetyFadeFrames(materialized, clip, leftLength),
                            ),
                        )
                    }
                }
                if (clipEnd > punchEnd) {
                    val rightStart = sourceFrameAtTimeline(materialized, clip, punchEnd)
                    if (rightStart < clip.sourceEndFrame) {
                        val rightLength = clip.sourceEndFrame - rightStart
                        add(
                            clip.copy(
                                id = UUID.randomUUID().toString(),
                                timelineStartFrame = punchEnd,
                                sourceStartFrame = rightStart,
                                fadeInFrames = safetyFadeFrames(materialized, clip, rightLength),
                                fadeOutFrames = clip.fadeOutFrames.coerceAtMost(rightLength),
                            ),
                        )
                    }
                }
            }
        }.toMutableList()

        val compensatedTakeStart = recordedTakeStart - take.latencyCompensationFrames
        val sourceStart = timelineDeltaToSource(
            (punchStart - compensatedTakeStart).coerceAtLeast(0L),
            sourceRate,
            materialized.timelineSampleRate,
        ).coerceIn(0L, take.recordedFrames)
        val requestedSourceEnd = timelineDeltaToSource(
            (punchEnd - compensatedTakeStart).coerceAtLeast(0L),
            sourceRate,
            materialized.timelineSampleRate,
        )
        val sourceEnd = requestedSourceEnd.coerceIn(sourceStart, take.recordedFrames)
        require(sourceEnd > sourceStart) { "Punch take quá ngắn cho vùng đã chọn" }
        val safetyFade = StudioEditSafety.frames(sourceRate, sourceEnd - sourceStart)
        val punchClip = StudioClip(
            id = UUID.randomUUID().toString(),
            sourceAssetId = take.assetId,
            sourceTakeId = take.id,
            timelineStartFrame = punchStart,
            sourceStartFrame = sourceStart,
            sourceEndFrame = sourceEnd,
            fadeInFrames = safetyFade,
            fadeOutFrames = safetyFade,
        )
        retained += punchClip
        val sorted = retained.sortedBy { it.timelineStartFrame }
        val updatedTrack = track.copy(clips = sorted)
        return EditResult(materialized.replaceTrack(trackIndex, updatedTrack), punchClip.id)
    }

    fun timelineEnd(project: StudioProject, clip: StudioClip): Long {
        val asset = requireNotNull(project.asset(clip.sourceAssetId)) { "Clip thiếu audio asset" }
        val sourceRate = asset.sampleRate ?: project.timelineSampleRate
        val sourceLength = (clip.sourceEndFrame - clip.sourceStartFrame).coerceAtLeast(0L)
        return clip.timelineStartFrame + sourceDeltaToTimeline(sourceLength, sourceRate, project.timelineSampleRate)
    }

    private data class ClipLocation(
        val trackIndex: Int,
        val clipIndex: Int,
        val track: StudioTrack,
        val clip: StudioClip,
    )

    private fun requireClip(project: StudioProject, clipId: String): ClipLocation {
        project.tracks.forEachIndexed { trackIndex, track ->
            val clipIndex = track.clips.indexOfFirst { it.id == clipId }
            if (clipIndex >= 0) return ClipLocation(trackIndex, clipIndex, track, track.clips[clipIndex])
        }
        error("Không tìm thấy clip Studio")
    }

    private fun requireTimelineInside(
        project: StudioProject,
        clip: StudioClip,
        timelineFrame: Long,
        strictStart: Boolean,
        strictEnd: Boolean,
    ) {
        val start = clip.timelineStartFrame
        val end = timelineEnd(project, clip)
        val afterStart = if (strictStart) timelineFrame > start else timelineFrame >= start
        val beforeEnd = if (strictEnd) timelineFrame < end else timelineFrame <= end
        require(afterStart && beforeEnd) { "Playhead không nằm trong clip" }
    }

    private fun replaceClip(project: StudioProject, location: ClipLocation, updated: StudioClip): EditResult {
        val clips = location.track.clips.toMutableList().apply { this[location.clipIndex] = updated }
        return EditResult(
            project.replaceTrack(location.trackIndex, location.track.copy(clips = clips)),
            selectedClipId = updated.id,
        )
    }

    private fun fullTakeClip(project: StudioProject, take: StudioTake): StudioClip {
        val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)
        val length = (placement.sourceEndFrame - placement.sourceStartFrame).coerceAtLeast(0L)
        val fade = StudioEditSafety.frames(take.inputSampleRate, length)
        return StudioClip(
            id = UUID.randomUUID().toString(),
            sourceAssetId = take.assetId,
            sourceTakeId = take.id,
            timelineStartFrame = placement.timelineStartFrame,
            sourceStartFrame = placement.sourceStartFrame,
            sourceEndFrame = placement.sourceEndFrame,
            fadeInFrames = fade,
            fadeOutFrames = fade,
        )
    }

    private fun activeTake(track: StudioTrack): StudioTake? =
        track.activeTakeId?.let { id -> track.takes.firstOrNull { it.id == id } }
            ?: track.takes.lastOrNull()

    private fun sourceFrameAtTimeline(project: StudioProject, clip: StudioClip, timelineFrame: Long): Long {
        val asset = requireNotNull(project.asset(clip.sourceAssetId)) { "Clip thiếu audio asset" }
        val sourceRate = asset.sampleRate ?: project.timelineSampleRate
        val delta = (timelineFrame - clip.timelineStartFrame).coerceAtLeast(0L)
        return (clip.sourceStartFrame + timelineDeltaToSource(delta, sourceRate, project.timelineSampleRate))
            .coerceIn(clip.sourceStartFrame, clip.sourceEndFrame)
    }

    private fun safetyFadeFrames(project: StudioProject, clip: StudioClip, lengthFrames: Long): Long {
        val asset = project.asset(clip.sourceAssetId)
        val sampleRate = asset?.sampleRate ?: project.timelineSampleRate
        return StudioEditSafety.frames(sampleRate, lengthFrames)
    }

    private fun sourceDeltaToTimeline(sourceFrames: Long, sourceRate: Int, timelineRate: Int): Long =
        (sourceFrames.toDouble() * timelineRate.toDouble() / sourceRate.toDouble()).roundToLong()

    private fun timelineDeltaToSource(timelineFrames: Long, sourceRate: Int, timelineRate: Int): Long =
        (timelineFrames.toDouble() * sourceRate.toDouble() / timelineRate.toDouble()).roundToLong()

    private fun StudioProject.replaceTrack(index: Int, track: StudioTrack): StudioProject =
        copy(tracks = tracks.toMutableList().apply { this[index] = track })
}
