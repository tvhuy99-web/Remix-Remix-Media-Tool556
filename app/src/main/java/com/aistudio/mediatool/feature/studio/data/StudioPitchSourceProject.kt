package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioMasterMix
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType

object StudioPitchSourceProject {
    private val supportedVoiceTypes = setOf(
        StudioTrackType.VOCAL,
        StudioTrackType.BACKING_VOCAL,
        StudioTrackType.ADLIB,
        StudioTrackType.OTHER,
    )

    fun create(project: StudioProject, trackId: String): StudioProject {
        val track = requireNotNull(project.tracks.firstOrNull { it.id == trackId }) { "Không tìm thấy lớp giọng" }
        require(!track.locked && track.type in supportedVoiceTypes) {
            "Auto-Tune và tạo bè chỉ hỗ trợ lớp giọng đơn âm"
        }
        return project.copy(
            name = "${project.name}_pitch_source",
            tracks = listOf(track.copy(volumeDb = 0f, pan = 0f, muted = false, solo = false)),
            masterMix = StudioMasterMix(gainDb = 0f, limiterEnabled = false),
        )
    }
}
