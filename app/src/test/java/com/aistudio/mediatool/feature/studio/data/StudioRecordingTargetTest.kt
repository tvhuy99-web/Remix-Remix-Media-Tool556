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
    fun explicitRoleCreatesBackingVocalWithAccessibleName() {
        val project = projectWithTracks(beatTrack())

        val first = project.selectRecordingTrack(
            StudioRecordingTargetRequest.NewLayerForRole(StudioTrackType.BACKING_VOCAL),
        )
        val second = first.project.selectRecordingTrack(
            StudioRecordingTargetRequest.NewLayerForRole(StudioTrackType.BACKING_VOCAL),
        )

        assertEquals(StudioTrackType.BACKING_VOCAL, first.track.type)
        assertEquals("Giọng bè", first.track.name)
        assertEquals("Giọng bè 2", second.track.name)
        assertTrue(first.track.isAutoRecordingLayer())
        assertTrue(second.track.isAutoRecordingLayer())
    }

    @Test
    fun explicitRoleSupportsMainAdlibAndDuetNames() {
        assertEquals("Giọng chính", recordingLayerName(StudioTrackType.VOCAL, 1))
        assertEquals("Giọng chính 2", recordingLayerName(StudioTrackType.VOCAL, 2))
        assertEquals("Giọng phụ", recordingLayerName(StudioTrackType.ADLIB, 1))
        assertEquals("Song ca / khác", recordingLayerName(StudioTrackType.OTHER, 1))
    }

    @Test
    fun explicitRoleRequestIsOneShotAndKeepsTheRole() {
        StudioRecordingTargetRequests.requestNewLayer(StudioTrackType.ADLIB)

        assertEquals(
            StudioRecordingTargetRequest.NewLayerForRole(StudioTrackType.ADLIB),
            StudioRecordingTargetRequests.consume(),
        )
        assertNull(StudioRecordingTargetRequests.consume())
    }

    @Test
    fun normalRecUsesTheRoleChosenBeforeRecording() {
        StudioRecordingTargetRequests.setNextNewLayerRole(StudioTrackType.BACKING_VOCAL)
        StudioRecordingTargetRequests.requestNewLayer()

        assertEquals(StudioTrackType.BACKING_VOCAL, StudioRecordingTargetRequests.nextNewLayerRole())
        assertEquals(
            StudioRecordingTargetRequest.NewLayerForRole(StudioTrackType.BACKING_VOCAL),
            StudioRecordingTargetRequests.consume(),
        )
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
            StudioRecordingTargetRequest.NewLayerForRole(StudioTrackType.VOCAL),
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
