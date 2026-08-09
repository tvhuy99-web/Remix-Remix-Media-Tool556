package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioEditEngineTest {
    @Test
    fun splitCreatesTwoMetadataClipsWithoutChangingSourceAsset() {
        val project = baseProject()
        val materialized = StudioEditEngine.materializeTrack(project, TRACK_ID)
        val original = materialized.project.tracks.single().clips.single()

        val split = StudioEditEngine.split(materialized.project, original.id, 96_000L)
        val clips = split.project.tracks.single().clips

        assertEquals(2, clips.size)
        assertEquals(ASSET_OLD, clips[0].sourceAssetId)
        assertEquals(ASSET_OLD, clips[1].sourceAssetId)
        assertEquals(96_000L, clips[0].sourceEndFrame)
        assertEquals(96_000L, clips[1].sourceStartFrame)
        assertEquals(96_000L, clips[1].timelineStartFrame)
        assertNotEquals(clips[0].id, clips[1].id)
        assertEquals(1, split.project.assets.size)
    }

    @Test
    fun trimStartRejectsPlayheadOutsideClip() {
        val materialized = StudioEditEngine.materializeTrack(baseProject(), TRACK_ID)
        val clip = materialized.project.tracks.single().clips.single()

        assertThrows(IllegalArgumentException::class.java) {
            StudioEditEngine.trimStart(materialized.project, clip.id, -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            StudioEditEngine.trimEnd(materialized.project, clip.id, 300_000L)
        }
    }

    @Test
    fun punchKeepsOldSidesAndUsesOnlySelectedPartOfNewTake() {
        val project = projectWithPunchTake()
        val result = StudioEditEngine.replacePunchRange(
            project = project,
            trackId = TRACK_ID,
            newTakeId = TAKE_NEW,
            punchStart = 96_000L,
            punchEnd = 144_000L,
            recordedTakeStart = 48_000L,
        )
        val clips = result.project.tracks.single().clips.sortedBy { it.timelineStartFrame }

        assertEquals(3, clips.size)
        assertEquals(ASSET_OLD, clips[0].sourceAssetId)
        assertEquals(0L, clips[0].timelineStartFrame)
        assertEquals(96_000L, clips[0].sourceEndFrame)

        val punch = clips[1]
        assertEquals(ASSET_NEW, punch.sourceAssetId)
        assertEquals(96_000L, punch.timelineStartFrame)
        assertEquals(48_000L, punch.sourceStartFrame)
        assertEquals(96_000L, punch.sourceEndFrame)
        assertTrue(punch.fadeInFrames > 0L)
        assertTrue(punch.fadeOutFrames > 0L)

        assertEquals(ASSET_OLD, clips[2].sourceAssetId)
        assertEquals(144_000L, clips[2].timelineStartFrame)
        assertEquals(144_000L, clips[2].sourceStartFrame)
        assertEquals(2, result.project.tracks.single().takes.size)
    }

    private fun baseProject(): StudioProject {
        val oldAsset = asset(ASSET_OLD, 192_000L)
        val oldTake = take(TAKE_OLD, ASSET_OLD, 192_000L)
        return StudioProject(
            id = "project-test-01",
            name = "Test",
            createdAt = 1L,
            updatedAt = 1L,
            assets = listOf(oldAsset),
            tracks = listOf(
                StudioTrack(
                    id = TRACK_ID,
                    type = StudioTrackType.VOCAL,
                    name = "Vocal",
                    primaryAssetId = ASSET_OLD,
                    activeTakeId = TAKE_OLD,
                    takes = listOf(oldTake),
                ),
            ),
        )
    }

    private fun projectWithPunchTake(): StudioProject {
        val old = baseProject()
        val newAsset = asset(ASSET_NEW, 192_000L)
        val newTake = take(TAKE_NEW, ASSET_NEW, 192_000L, recordedStart = 48_000L)
        return old.copy(
            assets = old.assets + newAsset,
            tracks = listOf(old.tracks.single().copy(takes = old.tracks.single().takes + newTake)),
        )
    }

    private fun asset(id: String, frames: Long) = StudioAsset(
        id = id,
        kind = StudioAssetKind.TAKE,
        relativePath = "takes/$id.wav",
        displayName = id,
        mimeType = "audio/wav",
        bytes = 44L + frames * 2L,
        sampleRate = 48_000,
        channelCount = 1,
        durationFrames = frames,
    )

    private fun take(id: String, assetId: String, frames: Long, recordedStart: Long = 0L) = StudioTake(
        id = id,
        assetId = assetId,
        recordedTimelineFrame = recordedStart,
        recordedFrames = frames,
        inputSampleRate = 48_000,
        status = StudioTakeStatus.COMPLETE,
    )

    companion object {
        private const val TRACK_ID = "track-vocal"
        private const val TAKE_OLD = "take-old"
        private const val TAKE_NEW = "take-new"
        private const val ASSET_OLD = "asset-old"
        private const val ASSET_NEW = "asset-new"
    }
}
