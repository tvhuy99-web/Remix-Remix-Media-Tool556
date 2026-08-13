package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import com.aistudio.mediatool.feature.studio.domain.latencyCompensatedPlacement
import java.util.UUID

/** Pure, non-destructive project transforms for Studio track/layer management. */
object StudioTrackEditor {
    fun rename(project: StudioProject, trackId: String, requestedName: String): StudioProject {
        val safeName = requestedName.trim().replace(Regex("\\s+"), " ").take(48)
        require(safeName.isNotBlank()) { "Tên lớp giọng không được để trống" }
        val track = editableTrack(project, trackId)
        if (track.name == safeName) return project
        return replaceTrack(project, track.copy(name = safeName))
    }

    fun setRole(project: StudioProject, trackId: String, type: StudioTrackType): StudioProject {
        require(type != StudioTrackType.BEAT) { "Nhạc nền không phải là lớp giọng" }
        val track = editableTrack(project, trackId)
        if (track.type == type) return project
        return replaceTrack(project, track.copy(type = type))
    }

    fun duplicate(project: StudioProject, trackId: String): StudioProject {
        val source = editableTrack(project, trackId)
        val sourceIndex = project.tracks.indexOfFirst { it.id == source.id }
        val clonedClips = materializedClips(project, source).map { clip ->
            clip.copy(
                id = UUID.randomUUID().toString(),
                sourceTakeId = null,
            )
        }
        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            name = uniqueCopyName(project, source),
            primaryAssetId = null,
            activeTakeId = null,
            muted = false,
            solo = false,
            locked = false,
            takes = emptyList(),
            clips = clonedClips,
        )
        val tracks = project.tracks.toMutableList().apply {
            add((sourceIndex + 1).coerceAtMost(size), duplicate)
        }
        return project.copy(tracks = tracks)
    }

    fun delete(project: StudioProject, trackId: String): StudioProject {
        val track = editableTrack(project, trackId)
        return project.copy(tracks = project.tracks.filterNot { it.id == track.id })
    }

    fun move(project: StudioProject, trackId: String, direction: Int): StudioProject {
        require(direction != 0) { "Hướng di chuyển lớp giọng không hợp lệ" }
        val track = editableTrack(project, trackId)
        val movableIndices = project.tracks.indices.filter { index ->
            project.tracks[index].type != StudioTrackType.BEAT
        }
        val currentOrder = movableIndices.indexOfFirst { project.tracks[it].id == track.id }
        if (currentOrder < 0) return project
        val targetOrder = (currentOrder + direction.sign()).coerceIn(0, movableIndices.lastIndex)
        if (targetOrder == currentOrder) return project
        val fromIndex = movableIndices[currentOrder]
        val toIndex = movableIndices[targetOrder]
        val tracks = project.tracks.toMutableList()
        val swap = tracks[fromIndex]
        tracks[fromIndex] = tracks[toIndex]
        tracks[toIndex] = swap
        return project.copy(tracks = tracks)
    }

    private fun editableTrack(project: StudioProject, trackId: String): StudioTrack {
        val track = requireNotNull(project.tracks.firstOrNull { it.id == trackId }) {
            "Không tìm thấy lớp âm thanh"
        }
        require(track.type != StudioTrackType.BEAT && !track.locked) {
            "Không thể thay đổi lớp nhạc nền"
        }
        return track
    }

    private fun replaceTrack(project: StudioProject, replacement: StudioTrack): StudioProject =
        project.copy(tracks = project.tracks.map { track ->
            if (track.id == replacement.id) replacement else track
        })

    private fun materializedClips(project: StudioProject, track: StudioTrack): List<StudioClip> {
        if (track.clips.isNotEmpty()) return track.clips
        val take = activeTake(track) ?: return emptyList()
        val placement = take.latencyCompensatedPlacement(project.timelineSampleRate)
        return listOf(
            StudioClip(
                id = UUID.randomUUID().toString(),
                sourceAssetId = take.assetId,
                sourceTakeId = take.id,
                timelineStartFrame = placement.timelineStartFrame,
                sourceStartFrame = placement.sourceStartFrame,
                sourceEndFrame = placement.sourceEndFrame,
            ),
        )
    }

    private fun activeTake(track: StudioTrack): StudioTake? =
        track.activeTakeId?.let { id -> track.takes.firstOrNull { it.id == id } }
            ?: track.takes.lastOrNull()

    private fun uniqueCopyName(project: StudioProject, source: StudioTrack): String {
        val base = source.name.trim().takeIf { it.isNotBlank() && it != "Vocal" }
            ?: defaultTrackName(source.type)
        val existing = project.tracks.map { it.name }.toSet()
        var candidate = "$base bản sao"
        var number = 2
        while (candidate in existing) {
            candidate = "$base bản sao $number"
            number++
        }
        return candidate.take(48)
    }

    private fun defaultTrackName(type: StudioTrackType): String = when (type) {
        StudioTrackType.VOCAL -> "Giọng chính"
        StudioTrackType.BACKING_VOCAL -> "Giọng bè"
        StudioTrackType.ADLIB -> "Giọng phụ"
        StudioTrackType.INSTRUMENT -> "Nhạc cụ"
        StudioTrackType.OTHER -> "Lớp âm thanh"
        StudioTrackType.BEAT -> "Nhạc nền"
    }

    private fun Int.sign(): Int = if (this < 0) -1 else 1
}
