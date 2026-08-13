package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioMusicalKeySettings
import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioProSettings
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode
import com.aistudio.mediatool.feature.studio.domain.StudioTempoSettings
import com.aistudio.mediatool.feature.studio.domain.StudioVocalFxSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioProSettingsMergeTest {
    @Test
    fun legacyVoiceSaveKeepsKeyAndGridOrigin() {
        val existing = StudioProSettings(
            tempo = StudioTempoSettings(bpm = 96f, gridOriginFrame = 24_000L),
            musicalKey = StudioMusicalKeySettings(StudioPitchClass.A, StudioScaleMode.MINOR),
        )
        val edited = StudioProSettings(
            tempo = StudioTempoSettings(bpm = 104f, beatsPerBar = 3, metronomeEnabled = true),
            vocalFx = StudioVocalFxSettings(reverbWet = 0.25f),
        )

        val merged = StudioProSettingsMerge.preserveMusicalMetadata(existing, edited)

        assertEquals(104f, merged.tempo.bpm)
        assertEquals(3, merged.tempo.beatsPerBar)
        assertEquals(true, merged.tempo.metronomeEnabled)
        assertEquals(24_000L, merged.tempo.gridOriginFrame)
        assertEquals(StudioPitchClass.A, merged.musicalKey.root)
        assertEquals(StudioScaleMode.MINOR, merged.musicalKey.scale)
        assertEquals(0.25f, merged.vocalFx.reverbWet)
    }
}
