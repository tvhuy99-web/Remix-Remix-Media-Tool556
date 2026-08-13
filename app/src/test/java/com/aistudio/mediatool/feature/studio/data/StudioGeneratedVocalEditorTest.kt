package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.audio.StudioHarmonyPreset
import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioGeneratedVocalEditorTest {
    @Test
    fun autoTuneMutesButKeepsSource() {
        val project = fixture()
        val result = StudioGeneratedVocalEditor.addAutoTuneVersion(project, "vocal", "generated")
        assertTrue(result.project.tracks.first { it.id == "vocal" }.muted)
        assertTrue(result.project.tracks.any { it.id == result.generatedTrackId })
        assertEquals(2, result.project.tracks.size)
    }

    @Test
    fun harmonyKeepsSourceAndCreatesBackingVocal() {
        val project = fixture()
        val result = StudioGeneratedVocalEditor.addHarmonyLayer(
            project, "vocal", "generated", StudioHarmonyPreset.THIRD_ABOVE, -7f, 0.3f,
        )
        assertFalse(result.project.tracks.first { it.id == "vocal" }.muted)
        val harmony = result.project.tracks.first { it.id == result.generatedTrackId }
        assertEquals(StudioTrackType.BACKING_VOCAL, harmony.type)
        assertEquals(0.3f, harmony.pan, 0.001f)
    }

    private fun fixture(): StudioProject {
        val asset = StudioAsset(
            id = "generated",
            kind = StudioAssetKind.DERIVED,
            relativePath = "derived/generated.wav",
            displayName = "Generated",
            sampleRate = 48_000,
            channelCount = 1,
            durationFrames = 48_000,
        )
        return StudioProject(
            id = "project",
            name = "Song",
            createdAt = 1L,
            updatedAt = 1L,
            assets = listOf(asset),
            tracks = listOf(StudioTrack(id = "vocal", type = StudioTrackType.VOCAL, name = "Giọng chính")),
        )
    }
}
