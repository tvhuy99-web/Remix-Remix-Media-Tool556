package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmStereoAnalyzerTest {
    @Test
    fun detectsDualMonoFromFinalPcm() {
        val file = rawStereo(
            0.25f to 0.25f,
            -0.5f to -0.5f,
            0.75f to 0.75f,
        )

        val metrics = PcmStereoAnalyzer.analyze(file)

        assertTrue(metrics.dualMono)
        assertEquals(1f, metrics.correlation, 0.0001f)
        assertEquals(0f, metrics.balanceDb, 0.0001f)
        assertEquals(-160f, metrics.differenceRmsDbfs, 0.0001f)
    }

    @Test
    fun measuresWideStereoInsteadOfReportingMidBranch() {
        val file = rawStereo(
            0.5f to -0.5f,
            -0.25f to 0.25f,
            0.75f to -0.75f,
        )

        val metrics = PcmStereoAnalyzer.analyze(file)

        assertFalse(metrics.dualMono)
        assertEquals(-1f, metrics.correlation, 0.0001f)
        assertEquals(0.75f, metrics.peak, 0.0001f)
        assertEquals(0L, metrics.nonFiniteSamples)
        assertEquals(0L, metrics.clippedSamples)
    }

    @Test
    fun countsInvalidAndClippedSamples() {
        val file = rawStereo(
            Float.NaN to 0f,
            1.25f to -1.5f,
        )

        val metrics = PcmStereoAnalyzer.analyze(file)

        assertEquals(1L, metrics.nonFiniteSamples)
        assertEquals(2L, metrics.clippedSamples)
        assertEquals(2L, metrics.frames)
        assertEquals(1L, metrics.finiteFrames)
    }

    private fun rawStereo(vararg frames: Pair<Float, Float>): File {
        val file = File.createTempFile("pcm_stereo_", ".f32")
        file.deleteOnExit()
        val bytes = ByteBuffer.allocate(frames.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        frames.forEach { (left, right) ->
            bytes.putFloat(left)
            bytes.putFloat(right)
        }
        file.writeBytes(bytes.array())
        return file
    }
}
