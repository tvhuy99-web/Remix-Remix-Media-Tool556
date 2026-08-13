package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.audio.StudioHarmonyPreset
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import java.util.UUID

data class StudioGeneratedLayerResult(
    val project: StudioProject,
    val generatedTrackId: String,
)

object StudioGeneratedVocalEditor {
    fun addAutoTuneVersion(
        project: StudioProject,
        sourceTrackId: String,
        assetId: String,
    ): StudioGeneratedLayerResult {
        val source = editableTrack(project, sourceTrackId)
        val generated = generatedTrack(
            project = project,
            assetId = assetId,
            type = source.type,
            requestedName = "${source.name} • Auto-Tune",
            volumeDb = source.volumeDb,
            pan = source.pan,
        )
        val tracks = project.tracks.toMutableList()
        val index = tracks.indexOfFirst { it.id == source.id }
        tracks[index] = source.copy(muted = true)
        tracks.add(index + 1, generated)
        return StudioGeneratedLayerResult(project.copy(tracks = tracks), generated.id)
    }

    fun addHarmonyLayer(
        project: StudioProject,
        sourceTrackId: String,
        assetId: String,
        preset: StudioHarmonyPreset,
        volumeDb: Float,
        pan: Float,
    ): StudioGeneratedLayerResult {
        val source = editableTrack(project, sourceTrackId)
        val generated = generatedTrack(
            project = project,
            assetId = assetId,
            type = StudioTrackType.BACKING_VOCAL,
            requestedName = "${source.name} • ${preset.label}",
            volumeDb = volumeDb.coerceIn(-24f, 6f),
            pan = pan.coerceIn(-1f, 1f),
        )
        val tracks = project.tracks.toMutableList()
        val index = tracks.indexOfFirst { it.id == source.id }
        tracks.add(index + 1, generated)
        return StudioGeneratedLayerResult(project.copy(tracks = tracks), generated.id)
    }

    fun restoreAutoTune(
        project: StudioProject,
        sourceTrackId: String,
        generatedTrackId: String,
        sourceWasMuted: Boolean,
    ): StudioProject {
        require(project.tracks.any { it.id == generatedTrackId }) { "Không tìm thấy lớp Auto-Tune" }
        return project.copy(
            tracks = project.tracks
                .filterNot { it.id == generatedTrackId }
                .map { if (it.id == sourceTrackId) it.copy(muted = sourceWasMuted) else it },
        )
    }

    private fun generatedTrack(
        project: StudioProject,
        assetId: String,
        type: StudioTrackType,
        requestedName: String,
        volumeDb: Float,
        pan: Float,
    ): StudioTrack {
        val asset = requireNotNull(project.asset(assetId)) { "Không tìm thấy bản giọng đã xử lý" }
        val frames = requireNotNull(asset.durationFrames) { "Bản giọng đã xử lý thiếu thời lượng" }
        require(frames > 0L) { "Bản giọng đã xử lý bị rỗng" }
        val id = UUID.randomUUID().toString()
        return StudioTrack(
            id = id,
            type = type,
            name = uniqueName(project, requestedName),
            primaryAssetId = asset.id,
            volumeDb = volumeDb,
            pan = pan,
            clips = listOf(
                StudioClip(
                    id = UUID.randomUUID().toString(),
                    sourceAssetId = asset.id,
                    timelineStartFrame = 0L,
                    sourceStartFrame = 0L,
                    sourceEndFrame = frames,
                    fadeInFrames = (asset.sampleRate ?: project.timelineSampleRate) / 200L,
                    fadeOutFrames = (asset.sampleRate ?: project.timelineSampleRate) / 200L,
                ),
            ),
        )
    }

    private fun editableTrack(project: StudioProject, trackId: String): StudioTrack {
        val track = requireNotNull(project.tracks.firstOrNull { it.id == trackId }) { "Không tìm thấy lớp giọng" }
        require(track.type != StudioTrackType.BEAT && !track.locked) { "Không thể xử lý lớp nhạc nền" }
        return track
    }

    private fun uniqueName(project: StudioProject, requested: String): String {
        val existing = project.tracks.mapTo(hashSetOf()) { it.name }
        val base = requested.take(48)
        if (base !in existing) return base
        var number = 2
        while ("$base $number" in existing) number++
        return "$base $number".take(48)
    }
}
