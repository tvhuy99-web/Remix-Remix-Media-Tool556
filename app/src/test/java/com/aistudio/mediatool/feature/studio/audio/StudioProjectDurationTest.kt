package com.aistudio.mediatool.feature.studio.audio

import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioProjectDurationTest {
    @Test
    fun inactiveLongTakeDoesNotExtendTimeline() {
        val longTake = take("long", frames = 144_000L)
        val activeTake = take("active", frames = 48_000L)
        val project = project(
            track = StudioTrack(
                id = "vocal-track",
                type = StudioTrackType.VOCAL,
                name = "Vocal",
                activeTakeId = activeTake.id,
                takes = listOf(longTake, activeTake),
            ),
        )

        assertEquals(48_000L, StudioSessionRuntime.projectDurationFrames(project))
    }

    @Test
    fun materializedClipsDefineDurationInsteadOfHistoricalTakes() {
        val historical = take("historical", frames = 192_000L)
        val project = project(
            track = StudioTrack(
                id = "vocal-track",
                type = StudioTrackType.VOCAL,
                name = "Vocal",
                activeTakeId = historical.id,
                takes = listOf(historical),
                clips = listOf(
                    StudioClip(
                        id = "clip",
                        sourceAssetId = "take-asset",
                        sourceTakeId = historical.id,
                        timelineStartFrame = 24_000L,
                        sourceStartFrame = 0L,
                        sourceEndFrame = 48_000L,
                    ),
                ),
            ),
        )

        assertEquals(72_000L, StudioSessionRuntime.projectDurationFrames(project))
    }

    private fun project(track: StudioTrack): StudioProject = StudioProject(
        id = "project-duration-test",
        name = "Test",
        createdAt = 0L,
        updatedAt = 0L,
        beatAssetId = "beat",
        assets = listOf(
            StudioAsset(
                id = "beat",
                kind = StudioAssetKind.BEAT,
                relativePath = "beat.wav",
                displayName = "Beat",
                sampleRate = 48_000,
                channelCount = 2,
                durationFrames = 48_000L,
            ),
            StudioAsset(
                id = "take-asset",
                kind = StudioAssetKind.TAKE,
                relativePath = "take.wav",
                displayName = "Take",
                sampleRate = 48_000,
                channelCount = 1,
                durationFrames = 192_000L,
            ),
        ),
        tracks = listOf(
            StudioTrack(
                id = "beat-track",
                type = StudioTrackType.BEAT,
                name = "Beat",
                primaryAssetId = "beat",
            ),
            track,
        ),
    )

    private fun take(id: String, frames: Long): StudioTake = StudioTake(
        id = id,
        assetId = "take-asset",
        recordedTimelineFrame = 0L,
        recordedFrames = frames,
        inputSampleRate = 48_000,
        status = StudioTakeStatus.COMPLETE,
    )
}
