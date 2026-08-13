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
import org.junit.Assert.assertNotNull
import org.junit.Test

class StudioPunchLatencyTest {
    @Test
    fun punchUsesCompensatedTakeStartWhenSelectingSourceRange() {
        val oldAsset = StudioAsset(
            id = "old-asset",
            kind = StudioAssetKind.TAKE,
            relativePath = "takes/old.wav",
            displayName = "Old",
            sampleRate = 48_000,
            channelCount = 1,
            durationFrames = 4_000,
        )
        val punchAsset = StudioAsset(
            id = "punch-asset",
            kind = StudioAssetKind.TAKE,
            relativePath = "takes/punch.wav",
            displayName = "Punch",
            sampleRate = 48_000,
            channelCount = 1,
            durationFrames = 2_000,
        )
        val oldTake = StudioTake(
            id = "old-take",
            assetId = oldAsset.id,
            recordedTimelineFrame = 0L,
            recordedFrames = 4_000L,
            inputSampleRate = 48_000,
            status = StudioTakeStatus.COMPLETE,
        )
        val punchTake = StudioTake(
            id = "punch-take",
            assetId = punchAsset.id,
            recordedTimelineFrame = 1_000L,
            recordedFrames = 2_000L,
            inputSampleRate = 48_000,
            latencyCompensationFrames = 100L,
            status = StudioTakeStatus.COMPLETE,
        )
        val track = StudioTrack(
            id = "vocal",
            type = StudioTrackType.VOCAL,
            name = "Vocal",
            activeTakeId = oldTake.id,
            takes = listOf(oldTake, punchTake),
            clips = listOf(
                StudioClip(
                    id = "old-clip",
                    sourceAssetId = oldAsset.id,
                    sourceTakeId = oldTake.id,
                    timelineStartFrame = 0L,
                    sourceStartFrame = 0L,
                    sourceEndFrame = 4_000L,
                ),
            ),
        )
        val project = StudioProject(
            id = "project",
            name = "Project",
            createdAt = 0L,
            updatedAt = 0L,
            assets = listOf(oldAsset, punchAsset),
            tracks = listOf(track),
        )

        val result = StudioEditEngine.replacePunchRange(
            project = project,
            trackId = track.id,
            newTakeId = punchTake.id,
            punchStart = 1_100L,
            punchEnd = 1_400L,
            recordedTakeStart = 1_000L,
        )
        val punchClip = result.project.tracks.single().clips.firstOrNull { it.sourceTakeId == punchTake.id }

        assertNotNull(punchClip)
        assertEquals(200L, punchClip!!.sourceStartFrame)
        assertEquals(500L, punchClip.sourceEndFrame)
        assertEquals(1_100L, punchClip.timelineStartFrame)
    }
}
