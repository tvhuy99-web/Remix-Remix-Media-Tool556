package com.aistudio.mediatool.feature.studio.ui

import com.aistudio.mediatool.feature.studio.audio.StudioHarmonyPreset
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.integration.StudioPitchPreviewResult

internal data class StudioPitchUiModel(
    val project: StudioProject? = null,
    val selectedTrackId: String? = null,
    val mode: StudioPitchToolMode = StudioPitchToolMode.AUTO_TUNE,
    val strength: Float = 0.70f,
    val maxCents: Float = 180f,
    val harmonyPreset: StudioHarmonyPreset = StudioHarmonyPreset.THIRD_ABOVE,
    val harmonyVolume: Float = -7f,
    val harmonyPan: Float = 0.28f,
    val processing: Boolean = false,
    val preview: StudioPitchPreviewResult? = null,
    val status: String = "Chọn lớp giọng và công cụ, sau đó tạo bản nghe thử.",
    val appliedAutoTune: StudioAppliedPitchVersion? = null,
) {
    val canProcess: Boolean
        get() = !processing && project?.proSettings?.musicalKey?.isKnown == true && selectedTrackId != null
}
