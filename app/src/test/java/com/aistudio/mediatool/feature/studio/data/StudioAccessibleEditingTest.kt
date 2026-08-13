package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioAsset
import com.aistudio.mediatool.feature.studio.domain.StudioAssetKind
import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.Assert.assertEquals
import org.junit.Test

class StudioAccessibleEditingTest {
    @Test
    fun buttonSizedStepsMoveClipExactlyWithoutChangingSource() {
        val project = projectAtTenSeconds()
        val clip = project.tracks.single().clips.single()

        val fiveSecondsLeft = StudioEditEngine.move(project, clip.id, -5L * SAMPLE_RATE).project
        val oneSecondRight = StudioEditEngine.move(fiveSecondsLeft, clip.id, SAMPLE_RATE.toLong()).project
        val hundredMsRight = StudioEditEngine.move(oneSecondRight, clip.id, SAMPLE_RATE / 10L).project
        val tenMsRight = StudioEditEngine.move(hundredMsRight, clip.id, SAMPLE_RATE / 100L).project
        val moved = tenMsRight.tracks.single().clips.single()

        assertEquals(6L * SAMPLE_RATE + SAMPLE_RATE / 10L + SAMPLE_RATE / 100L, moved.timelineStartFrame)
        assertEquals(clip.sourceAssetId, moved.sourceAssetId)
        assertEquals(clip.sourceStartFrame, moved.sourceStartFrame)
        assertEquals(clip.sourceEndFrame, moved.sourceEndFrame)
    }

    @Test
    fun movingLeftPastStartClampsAtZero() {
        val project = projectAtTenSeconds()
        val clip = project.tracks.single().clips.single()

        val moved = StudioEditEngine.move(project, clip.id, -60L * SAMPLE_RATE).project
            .tracks.single().clips.single()

        assertEquals(0L, moved.timelineStartFrame)
    }

    private fun projectAtTenSeconds(): StudioProject {
        val asset = StudioAsset(
            id = ASSET_ID,
            kind = StudioAssetKind.TAKE,
            relativePath = "takes/$ASSET_ID.wav",
            displayName = "Accessible edit test",
            mimeType = "audio/wav",
            bytes = 44L + SAMPLE_RATE * 4L,
            sampleRate = SAMPLE_RATE,
            channelCount = 1,
            durationFrames = SAMPLE_RATE * 2L,
        )
        val clip = StudioClip(
            id = CLIP_ID,
            sourceAssetId = ASSET_ID,
            timelineStartFrame = SAMPLE_RATE * 10L,
            sourceStartFrame = 0L,
            sourceEndFrame = SAMPLE_RATE * 2L,
        )
        return StudioProject(
            id = "accessible-edit-project",
            name = "Accessible edit",
            createdAt = 1L,
            updatedAt = 1L,
            assets = listOf(asset),
            tracks = listOf(
                StudioTrack(
                    id = TRACK_ID,
                    type = StudioTrackType.VOCAL,
                    name = "Giọng chính",
                    clips = listOf(clip),
                ),
            ),
        )
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val TRACK_ID = "track-accessible"
        private const val ASSET_ID = "asset-accessible"
        private const val CLIP_ID = "clip-accessible"
    }
}
