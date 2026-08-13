package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTake
import com.aistudio.mediatool.feature.studio.domain.StudioTakeStatus
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioTrackEditorTest {
    @Test
    fun duplicateMaterializesActiveTakeWithoutDuplicatingTakeIdentity() {
        val take = StudioTake(
            id = "take-1",
            assetId = "asset-1",
            recordedTimelineFrame = 1_000L,
            recordedFrames = 4_000L,
            inputSampleRate = 48_000,
            latencyCompensationFrames = 100L,
            status = StudioTakeStatus.COMPLETE,
        )
        val vocal = StudioTrack(
            id = "vocal",
            type = StudioTrackType.VOCAL,
            name = "Giọng chính",
            activeTakeId = take.id,
            takes = listOf(take),
            pan = -0.25f,
        )
        val project = project(beat(), vocal)

        val result = StudioTrackEditor.duplicate(project, vocal.id)
        val copy = result.tracks[2]

        assertNotEquals(vocal.id, copy.id)
        assertEquals("Giọng chính bản sao", copy.name)
        assertTrue(copy.takes.isEmpty())
        assertEquals(1, copy.clips.size)
        assertEquals(take.assetId, copy.clips.single().sourceAssetId)
        assertEquals(take.id, copy.clips.single().sourceTakeId)
        assertEquals(900L, copy.clips.single().timelineStartFrame)
        assertEquals(-0.25f, copy.pan)
    }

    @Test
    fun duplicateExistingArrangementCreatesIndependentClipIds() {
        val source = StudioTrack(
            id = "backing",
            type = StudioTrackType.BACKING_VOCAL,
            name = "Bè cao",
            clips = listOf(
                StudioClip(
                    id = "clip-a",
                    sourceAssetId = "asset-a",
                    timelineStartFrame = 2_000L,
                    sourceStartFrame = 50L,
                    sourceEndFrame = 500L,
                    gainDb = -2f,
                ),
            ),
        )

        val result = StudioTrackEditor.duplicate(project(beat(), source), source.id)
        val copy = result.tracks[2]

        assertEquals("Bè cao bản sao", copy.name)
        assertNotEquals(source.clips.single().id, copy.clips.single().id)
        assertEquals(source.clips.single().sourceAssetId, copy.clips.single().sourceAssetId)
        assertEquals(source.clips.single().gainDb, copy.clips.single().gainDb)
    }

    @Test
    fun renameRoleDeleteAndMoveKeepBeatLockedInPlace() {
        val first = StudioTrack(id = "first", type = StudioTrackType.VOCAL, name = "Giọng chính")
        val second = StudioTrack(id = "second", type = StudioTrackType.OTHER, name = "Giọng 2")
        val initial = project(beat(), first, second)

        val renamed = StudioTrackEditor.rename(initial, second.id, "  Song   ca  ")
        assertEquals("Song ca", renamed.tracks[2].name)

        val roleChanged = StudioTrackEditor.setRole(renamed, second.id, StudioTrackType.BACKING_VOCAL)
        assertEquals(StudioTrackType.BACKING_VOCAL, roleChanged.tracks[2].type)

        val moved = StudioTrackEditor.move(roleChanged, second.id, -1)
        assertEquals(listOf("beat", "second", "first"), moved.tracks.map { it.id })

        val cannotMoveBeforeBeat = StudioTrackEditor.move(moved, second.id, -1)
        assertEquals(listOf("beat", "second", "first"), cannotMoveBeforeBeat.tracks.map { it.id })

        val deleted = StudioTrackEditor.delete(cannotMoveBeforeBeat, second.id)
        assertEquals(listOf("beat", "first"), deleted.tracks.map { it.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun beatCannotBeDeleted() {
        StudioTrackEditor.delete(project(beat()), "beat")
    }

    @Test
    fun duplicateResetsMuteSoloAndLock() {
        val source = StudioTrack(
            id = "voice",
            type = StudioTrackType.OTHER,
            name = "Song ca",
            muted = true,
            solo = true,
        )
        val copy = StudioTrackEditor.duplicate(project(beat(), source), source.id).tracks.last()

        assertFalse(copy.muted)
        assertFalse(copy.solo)
        assertFalse(copy.locked)
    }

    private fun beat() = StudioTrack(
        id = "beat",
        type = StudioTrackType.BEAT,
        name = "Beat",
        locked = true,
    )

    private fun project(vararg tracks: StudioTrack) = StudioProject(
        id = "project",
        name = "Project",
        createdAt = 0L,
        updatedAt = 0L,
        tracks = tracks.toList(),
    )
}
