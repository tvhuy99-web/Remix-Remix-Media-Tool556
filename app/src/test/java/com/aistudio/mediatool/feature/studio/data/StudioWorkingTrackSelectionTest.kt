package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioClip
import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudioWorkingTrackSelectionTest {
    @After
    fun tearDown() {
        StudioWorkingTrackSelection.clear()
    }

    @Test
    fun selectedClipMovesWorkingTrackToItsLayer() {
        val project = project()
        StudioWorkingTrackSelection.select(project, MAIN)

        StudioWorkingTrackSelection.sync(project, BACKING_CLIP)

        assertEquals(BACKING, StudioWorkingTrackSelection.state.value.trackId)
        assertEquals(BACKING, StudioWorkingTrackSelection.state.value.selectedClipTrackId)
        assertEquals(BACKING, StudioWorkingTrackSelection.preferredPunchTrackId(MAIN))
    }

    @Test
    fun explicitWorkingTrackWinsOverLegacyMainFallback() {
        val project = project()
        StudioWorkingTrackSelection.sync(project, null)
        StudioWorkingTrackSelection.select(project, BACKING)

        assertEquals(BACKING, StudioWorkingTrackSelection.preferredPunchTrackId(MAIN))
    }

    @Test
    fun aRealNonDefaultWorkspaceTrackStillWins() {
        val project = project()
        StudioWorkingTrackSelection.sync(project, null)
        StudioWorkingTrackSelection.select(project, MAIN)

        assertEquals(BACKING, StudioWorkingTrackSelection.preferredPunchTrackId(BACKING))
    }

    @Test
    fun switchingProjectDropsStaleTrackIds() {
        StudioWorkingTrackSelection.sync(project(), null)
        StudioWorkingTrackSelection.select(project(), BACKING)
        val other = StudioProject(
            id = "other",
            name = "Other",
            createdAt = 0L,
            updatedAt = 0L,
            tracks = listOf(
                StudioTrack(id = "other-beat", type = StudioTrackType.BEAT, name = "Beat"),
                StudioTrack(id = "other-main", type = StudioTrackType.VOCAL, name = "Giọng chính"),
            ),
        )

        StudioWorkingTrackSelection.sync(other, null)

        assertEquals("other-main", StudioWorkingTrackSelection.state.value.trackId)
        assertEquals(setOf("other-main"), StudioWorkingTrackSelection.state.value.validTrackIds)
    }

    @Test
    fun beatOnlyProjectHasNoWorkingVoiceTrack() {
        val beatOnly = StudioProject(
            id = "beat-only",
            name = "Beat only",
            createdAt = 0L,
            updatedAt = 0L,
            tracks = listOf(StudioTrack(id = "beat", type = StudioTrackType.BEAT, name = "Beat")),
        )

        StudioWorkingTrackSelection.sync(beatOnly, null)

        assertNull(StudioWorkingTrackSelection.state.value.trackId)
        assertNull(StudioWorkingTrackSelection.preferredPunchTrackId(null))
    }

    private fun project(): StudioProject = StudioProject(
        id = "project",
        name = "Project",
        createdAt = 0L,
        updatedAt = 0L,
        tracks = listOf(
            StudioTrack(id = "beat", type = StudioTrackType.BEAT, name = "Beat"),
            StudioTrack(
                id = MAIN,
                type = StudioTrackType.VOCAL,
                name = "Giọng chính",
                clips = listOf(
                    StudioClip(
                        id = MAIN_CLIP,
                        sourceAssetId = "a1",
                        timelineStartFrame = 0L,
                        sourceStartFrame = 0L,
                        sourceEndFrame = 48_000L,
                    ),
                ),
            ),
            StudioTrack(
                id = BACKING,
                type = StudioTrackType.BACKING_VOCAL,
                name = "Giọng bè",
                clips = listOf(
                    StudioClip(
                        id = BACKING_CLIP,
                        sourceAssetId = "a2",
                        timelineStartFrame = 48_000L,
                        sourceStartFrame = 0L,
                        sourceEndFrame = 48_000L,
                    ),
                ),
            ),
        ),
    )

    companion object {
        private const val MAIN = "main"
        private const val BACKING = "backing"
        private const val MAIN_CLIP = "main-clip"
        private const val BACKING_CLIP = "backing-clip"
    }
}
