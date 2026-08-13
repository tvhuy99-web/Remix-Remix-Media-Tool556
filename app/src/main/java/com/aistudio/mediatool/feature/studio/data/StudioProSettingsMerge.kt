package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioProSettings

/**
 * The legacy Pro voice card only edits tempo/metronome and vocal FX. It does not expose
 * musical key or beat-grid origin, so those Phase 3 fields must survive its save path.
 */
object StudioProSettingsMerge {
    fun preserveMusicalMetadata(
        existing: StudioProSettings,
        editedByLegacyProUi: StudioProSettings,
    ): StudioProSettings = editedByLegacyProUi.copy(
        tempo = editedByLegacyProUi.tempo.copy(
            gridOriginFrame = existing.tempo.gridOriginFrame,
        ),
        musicalKey = existing.musicalKey,
    )
}
