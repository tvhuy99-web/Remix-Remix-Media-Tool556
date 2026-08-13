package com.aistudio.mediatool.feature.studio.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioBeatGridTest {
    @Test
    fun oneHundredTwentyBpmAt48kHasTwentyFourThousandFramesPerBeat() {
        assertEquals(24_000.0, StudioBeatGrid.framesPerBeat(48_000, 120f), 0.0001)
    }

    @Test
    fun fourFourGridMarksBarStartsEveryNinetySixThousandFrames() {
        val tempo = StudioTempoSettings(bpm = 120f, beatsPerBar = 4)
        val markers = StudioBeatGrid.markersBetween(
            startFrame = 0L,
            endFrame = 192_000L,
            sampleRate = 48_000,
            tempo = tempo,
        )

        assertEquals(listOf(0L, 24_000L, 48_000L, 72_000L, 96_000L), markers.take(5).map { it.frame })
        assertTrue(markers[0].isBarStart)
        assertFalse(markers[1].isBarStart)
        assertEquals(1, markers[0].beatInBar)
        assertEquals(4, markers[3].beatInBar)
        assertTrue(markers[4].isBarStart)
        assertEquals(1L, markers[4].barIndex)
    }

    @Test
    fun gridOriginShiftsEveryBeatWithoutChangingTempo() {
        val tempo = StudioTempoSettings(
            bpm = 120f,
            beatsPerBar = 4,
            gridOriginFrame = 12_000L,
        )

        assertEquals(12_000L, StudioBeatGrid.frameForBeat(0L, 48_000, tempo))
        assertEquals(36_000L, StudioBeatGrid.frameForBeat(1L, 48_000, tempo))
        assertEquals(60_000L, StudioBeatGrid.frameForBeat(2L, 48_000, tempo))
    }

    @Test
    fun nearestBeatUsesPersistedOriginAndRoundsToClosestBeat() {
        val tempo = StudioTempoSettings(
            bpm = 120f,
            beatsPerBar = 4,
            gridOriginFrame = 12_000L,
        )

        assertEquals(12_000L, StudioBeatGrid.nearestBeat(20_000L, 48_000, tempo).frame)
        assertEquals(36_000L, StudioBeatGrid.nearestBeat(30_000L, 48_000, tempo).frame)
    }

    @Test
    fun markersBeforeGridOriginRemainValidAndNonNegative() {
        val tempo = StudioTempoSettings(
            bpm = 120f,
            beatsPerBar = 4,
            gridOriginFrame = 36_000L,
        )
        val markers = StudioBeatGrid.markersBetween(0L, 60_000L, 48_000, tempo)

        assertEquals(listOf(12_000L, 36_000L, 60_000L), markers.map { it.frame })
        assertEquals(listOf(-1L, 0L, 1L), markers.map { it.beatIndex })
        assertEquals(listOf(4, 1, 2), markers.map { it.beatInBar })
    }

    @Test
    fun musicalKeyIsUnknownUntilBothRootAndScaleArePresent() {
        assertFalse(StudioMusicalKeySettings().isKnown)
        assertFalse(StudioMusicalKeySettings(root = StudioPitchClass.A).isKnown)
        assertTrue(
            StudioMusicalKeySettings(
                root = StudioPitchClass.A,
                scale = StudioScaleMode.MINOR,
            ).isKnown,
        )
    }
}
