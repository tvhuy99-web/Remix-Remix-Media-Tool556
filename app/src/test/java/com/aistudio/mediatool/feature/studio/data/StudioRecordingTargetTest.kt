package com.aistudio.mediatool.feature.studio.data

import com.aistudio.mediatool.feature.studio.domain.StudioProject
import com.aistudio.mediatool.feature.studio.domain.StudioTrack
import com.aistudio.mediatool.feature.studio.domain.StudioTrackType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioRecordingTargetTest {
    @After
    fun tearDown() {
        StudioRecordingTargetRequests.clear()
    }

    @Test
    fun firstNormalRecordingCreatesMainVocalLayer() {
        val project = projectWithTracks(beatTrack())

        val selection = project.selectRecordingTrack(StudioRecordingTargetRequest.NewLayer)

        assertTrue(selection.createdNewTrack)
        assertEquals(StudioTrackType.VOCAL, selection.track.type)
        assertEquals("Giọng chính", selection.track.name)
        assertEquals(2, selection.project.tracks.size)
    }

    @Test
    fun nextNormalRecordingCreatesIndependentNamedLayer() {
        val main = StudioTrack(
            id = "main-vocal",
            type = StudioTrackType.VOCAL,
            name = "Giọng chính",
        )
        val project = projectWithTracks(beatTrack(), main)

        val selection = project.selectRecordingTrack(StudioRecordingTargetRequest.NewLayer)

        assertTrue(selection.createdNewTrack)
        assertEquals(StudioTrackType.OTHER, selection.track.type)
        assertEquals("Giọng 2", selection.track.name)
        assertNotEquals(main.id, selection.track.id)
        assertEquals(listOf("beat", "main-vocal", selection.track.id), selection.project.tracks.map { it.id })
        assertTrue(selection.track.isAutoRecordingLayer())
    }

    @Test
    fun punchRequestKeepsTheRequestedExistingTrack() {
        val main = StudioTrack(
            id = "main-vocal",
            type = StudioTrackType.VOCAL,
            name = "Giọng chính",
        )
        val harmony = StudioTrack(
            id = "harmony",
            type = StudioTrackType.OTHER,
            name = "Giọng 2",
        )
        val project = projectWithTracks(beatTrack(), main, harmony)

        val selection = project.selectRecordingTrack(
            StudioRecordingTargetRequest.ExistingTrack(harmony.id),
        )

        assertFalse(selection.createdNewTrack)
        assertSame(project, selection.project)
        assertEquals(harmony.id, selection.track.id)
        assertEquals(3, selection.project.tracks.size)
    }

    @Test
    fun requestStoreIsOneShotSoIntentCannotLeakAcrossTakes() {
        StudioRecordingTargetRequests.requestNewLayer()

        assertEquals(
            StudioRecordingTargetRequest.NewLayer,
            StudioRecordingTargetRequests.consume(),
        )
        assertNull(StudioRecordingTargetRequests.consume())
    }

    private fun beatTrack() = StudioTrack(
        id = "beat",
        type = StudioTrackType.BEAT,
        name = "Beat",
    )

    private fun projectWithTracks(vararg tracks: StudioTrack) = StudioProject(
        id = "project",
        name = "Project",
        createdAt = 0L,
        updatedAt = 0L,
        tracks = tracks.toList(),
    )
}
