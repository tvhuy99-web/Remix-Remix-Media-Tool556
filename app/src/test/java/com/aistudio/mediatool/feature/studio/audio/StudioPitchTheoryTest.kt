package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioPitchClass
import com.aistudio.mediatool.feature.studio.domain.StudioScaleMode
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioPitchTheoryTest {
    @Test
    fun cMajorThirdAboveCIsE() {
        assertEquals(
            64f,
            StudioPitchTheory.harmonyTargetMidi(
                60f,
                StudioPitchClass.C,
                StudioScaleMode.MAJOR,
                StudioHarmonyPreset.THIRD_ABOVE,
            ),
            0.001f,
        )
    }

    @Test
    fun cMajorThirdBelowEIsC() {
        assertEquals(
            60f,
            StudioPitchTheory.harmonyTargetMidi(
                64f,
                StudioPitchClass.C,
                StudioScaleMode.MAJOR,
                StudioHarmonyPreset.THIRD_BELOW,
            ),
            0.001f,
        )
    }

    @Test
    fun cMajorFifthAboveCIsG() {
        assertEquals(
            67f,
            StudioPitchTheory.harmonyTargetMidi(
                60f,
                StudioPitchClass.C,
                StudioScaleMode.MAJOR,
                StudioHarmonyPreset.FIFTH_ABOVE,
            ),
            0.001f,
        )
    }
}
