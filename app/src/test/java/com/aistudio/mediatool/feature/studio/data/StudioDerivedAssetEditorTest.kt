package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioDerivedAssetEditorTest {
    @Test
    fun activeTake_becomesExplicitDerivedClip_withoutMutatingTake() {
        val source = StudioAsset(
            id = "source",
            kind = StudioAssetKind.TAKE,
            relativePath = "takes/source.wav",
            displayName = "Take",
            sampleRate = 48_000,
            channelCount = 1,
            durationFrames = 48_000,
        )
        val derived = StudioAsset(
            id = "derived",
            kind = StudioAssetKind.DERIVED,
            relativePath = "derived/clean.wav",
            displayName = "Clean",
            sourceAssetId = source.id,
            processorId = "voice_cleanup",
            sampleRate = 48_000,
            channelCount = 1,
            durationFrames = 48_000,
        )
        val take = StudioTake(
            id = "take",
            assetId = source.id,
            recordedTimelineFrame = 4_800,
            recordedFrames = 48_000,
            inputSampleRate = 48_000,
            latencyCompensationFrames = 2_400,
            status = StudioTakeStatus.COMPLETE,
        )
        val project = project(source, derived, take, clips = emptyList())

        val updated = StudioDerivedAssetEditor.apply(project, source.id, derived.id)
        val track = updated.tracks.single()

        assertEquals(source.id, track.takes.single().assetId)
        assertEquals(derived.id, track.clips.single().sourceAssetId)
        assertEquals(2_400L, track.clips.single().timelineStartFrame)
    }

    @Test
    fun explicitClip_scalesSourceFrames_whenDerivedRateDiffers() {
        val source = StudioAsset(
            id = "source",
            kind = StudioAssetKind.TAKE,
            relativePath = "takes/source.wav",
            displayName = "Take",
            sampleRate = 44_100,
            channelCount = 1,
            durationFrames = 44_100,
        )
        val derived = StudioAsset(
            id = "derived",
            kind = StudioAssetKind.DERIVED,
            relativePath = "derived/pro.wav",
            displayName = "Pro",
            sourceAssetId = source.id,
            processorId = "pro_vocal_chain",
            sampleRate = 48_000,
            channelCount = 1,
            durationFrames = 48_000,
        )
        val take = StudioTake(
            id = "take",
            assetId = source.id,
            recordedTimelineFrame = 0,
            recordedFrames = 44_100,
            inputSampleRate = 44_100,
            status = StudioTakeStatus.COMPLETE,
        )
        val clip = StudioClip(
            id = "clip",
            sourceAssetId = source.id,
            sourceTakeId = take.id,
            timelineStartFrame = 0,
            sourceStartFrame = 4_410,
            sourceEndFrame = 22_050,
            fadeInFrames = 441,
            fadeOutFrames = 882,
        )
        val project = project(source, derived, take, clips = listOf(clip))

        val updated = StudioDerivedAssetEditor.apply(project, source.id, derived.id)
        val mapped = updated.tracks.single().clips.single()

        assertEquals(4_800L, mapped.sourceStartFrame)
        assertEquals(24_000L, mapped.sourceEndFrame)
        assertEquals(480L, mapped.fadeInFrames)
        assertEquals(960L, mapped.fadeOutFrames)
    }

    @Test
    fun unrelatedDerivedAsset_isRejected() {
        val source = StudioAsset("source", StudioAssetKind.TAKE, "takes/a.wav", "A", sampleRate = 48_000)
        val other = StudioAsset("other", StudioAssetKind.TAKE, "takes/b.wav", "B", sampleRate = 48_000)
        val derived = StudioAsset(
            "derived",
            StudioAssetKind.DERIVED,
            "derived/b.wav",
            "B clean",
            sourceAssetId = other.id,
            sampleRate = 48_000,
        )
        val take = StudioTake("take", source.id, 0, 48_000, inputSampleRate = 48_000, status = StudioTakeStatus.COMPLETE)
        val project = project(source, derived, take, clips = emptyList()).copy(assets = listOf(source, other, derived))

        val failed = runCatching { StudioDerivedAssetEditor.apply(project, source.id, derived.id) }.isFailure

        assertTrue(failed)
    }

    private fun project(
        source: StudioAsset,
        derived: StudioAsset,
        take: StudioTake,
        clips: List<StudioClip>,
    ) = StudioProject(
        id = "project",
        name = "Project",
        createdAt = 1,
        updatedAt = 1,
        assets = listOf(source, derived),
        tracks = listOf(
            StudioTrack(
                id = "vocal",
                type = StudioTrackType.VOCAL,
                name = "Vocal",
                primaryAssetId = source.id,
                activeTakeId = take.id,
                takes = listOf(take),
                clips = clips,
            ),
        ),
    )
}
