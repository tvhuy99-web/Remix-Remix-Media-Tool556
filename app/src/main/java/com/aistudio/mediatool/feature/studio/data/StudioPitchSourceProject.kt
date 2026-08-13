package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioMasterMix
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType

object StudioPitchSourceProject {
    fun create(project: StudioProject, trackId: String): StudioProject {
        val track = requireNotNull(project.tracks.firstOrNull { it.id == trackId }) { "Không tìm thấy lớp giọng" }
        require(track.type != StudioTrackType.BEAT && !track.locked) { "Không thể xử lý cao độ lớp nhạc nền" }
        return project.copy(
            name = "${project.name}_pitch_source",
            tracks = listOf(track.copy(volumeDb = 0f, pan = 0f, muted = false, solo = false)),
            masterMix = StudioMasterMix(gainDb = 0f, limiterEnabled = false),
        )
    }
}
