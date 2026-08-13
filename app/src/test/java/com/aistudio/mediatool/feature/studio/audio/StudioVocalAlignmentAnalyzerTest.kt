package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioProSettings
import com.aistudio.mediatool.feature.studio.domain.StudioTempoSettings
import kotlin.math.abs
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioVocalAlignmentAnalyzerTest {
    @Test
    fun repeatedOnsetsLateBy100MsSuggestMovingEarlier() {
        val sampleRate = 48_000
        val tempo = StudioTempoSettings(bpm = 120f, beatsPerBar = 4, gridOriginFrame = 0L)
        val project = project(tempo)
        val halfBeat = 12_000L
        val late = 4_800L // 100 ms
        val onsets = (0L until 12L).map { beat -> beat * halfBeat + late }

        val suggestion = StudioVocalAlignmentMath.suggestOffset(onsets, sampleRate, project)

        assertNotNull(suggestion)
        val value = requireNotNull(suggestion)
        assertTrue(abs(value.offsetMillis + 100L) <= 10L)
        assertTrue(value.averageErrorAfterMillis < value.averageErrorBeforeMillis)
        assertTrue(value.confidence > 0.5f)
    }

    @Test
    fun alreadyAlignedOnsetsStayNearZero() {
        val sampleRate = 48_000
        val project = project(StudioTempoSettings(bpm = 120f, gridOriginFrame = 0L))
        val onsets = (0L until 8L).map { it * 12_000L }

        val suggestion = StudioVocalAlignmentMath.suggestOffset(onsets, sampleRate, project)

        assertNotNull(suggestion)
        assertTrue(abs(requireNotNull(suggestion).offsetMillis) <= 10L)
    }

    @Test
    fun emptyOnsetsReturnNoSuggestion() {
        assertTrue(
            StudioVocalAlignmentMath.suggestOffset(
                onsetTimelineFrames = emptyList(),
                timelineSampleRate = 48_000,
                project = project(StudioTempoSettings()),
            ) == null,
        )
    }

    private fun project(tempo: StudioTempoSettings) = StudioProject(
        id = "p",
        name = "Project",
        createdAt = 0L,
        updatedAt = 0L,
        proSettings = StudioProSettings(tempo = tempo),
    )
}
