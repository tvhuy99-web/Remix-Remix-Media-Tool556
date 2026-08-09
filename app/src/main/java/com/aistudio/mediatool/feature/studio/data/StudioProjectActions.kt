package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioProject

fun StudioProjectRepository.selectActiveTake(
    projectId: String,
    trackId: String,
    takeId: String,
): StudioProject {
    val project = requireNotNull(load(projectId)) { "Không tìm thấy dự án Studio" }
    val trackIndex = project.tracks.indexOfFirst { it.id == trackId }
    require(trackIndex >= 0) { "Không tìm thấy vocal track" }
    val track = project.tracks[trackIndex]
    val take = requireNotNull(track.takes.firstOrNull { it.id == takeId }) { "Không tìm thấy take" }
    val updatedTrack = track.copy(activeTakeId = take.id, primaryAssetId = take.assetId)
    val updatedTracks = project.tracks.toMutableList().apply { this[trackIndex] = updatedTrack }
    return save(project.copy(tracks = updatedTracks))
}
