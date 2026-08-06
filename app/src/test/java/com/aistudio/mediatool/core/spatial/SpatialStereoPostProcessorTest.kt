package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpatialStereoPostProcessorTest {
    @Test
    fun zeroPreservationKeepsNativeBinauralFrame() {
        val value = SpatialStereoPostProcessor.reconstructFrame(
            nativeLeft = 0.30f,
            nativeRight = 0.10f,
            sourceLeft = 0.90f,
            sourceRight = -0.90f,
            preservation = 0f,
        )
        assertEquals(0.30f, value.first, 1e-6f)
        assertEquals(0.10f, value.second, 1e-6f)
    }

    @Test
    fun fullPreservationUsesNativeMidAndSourceSide() {
        val value = SpatialStereoPostProcessor.reconstructFrame(
            nativeLeft = 0.30f,
            nativeRight = 0.10f,
            sourceLeft = 0.80f,
            sourceRight = 0.20f,
            preservation = 1f,
        )
        assertEquals(0.50f, value.first, 1e-6f)
        assertEquals(-0.10f, value.second, 1e-6f)
    }

    @Test
    fun distanceNarrowsRestoredWidthWithoutCollapsingIt() {
        val near = SpatialStereoPostProcessor.distanceWidthScale(0.8f)
        val medium = SpatialStereoPostProcessor.distanceWidthScale(5f)
        val far = SpatialStereoPostProcessor.distanceWidthScale(20f)
        assertEquals(1f, near, 1e-6f)
        assertTrue(medium < near)
        assertTrue(far < medium)
        assertTrue(far >= 0.35f)
    }

    @Test
    fun fileProcessorAttenuatesOnlyWhenReconstructionExceedsPeakCeiling() {
        val directory = createTempDir(prefix = "spatial_stereo_test_")
        try {
            val source = File(directory, "source.f32")
            val rendered = File(directory, "rendered.f32")
            val output = File(directory, "output.f32")
            writeFrames(source, listOf(1f to -1f, 1f to -1f))
            writeFrames(rendered, listOf(0.8f to 0.8f, 0.8f to 0.8f))

            val metrics = SpatialStereoPostProcessor.process(
                sourceStereo = source,
                pointRenderedStereo = rendered,
                output = output,
                spatialBlend = 1f,
                startDistanceM = 1f,
                endDistanceM = 1f,
                inputDualMono = false,
            )

            assertEquals("mid_side_preserved", metrics.mode)
            assertTrue(metrics.peakBefore > 0.89125094f)
            assertTrue(metrics.peakAfter <= 0.89125094f + 1e-5f)
            assertTrue(metrics.peakGainDb < 0f)
            assertEquals(16L, output.length())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun movingObjectModeDoesNotReinjectTheOriginalSideChannel() {
        val directory = createTempDir(prefix = "spatial_object_test_")
        try {
            val source = File(directory, "source.f32")
            val rendered = File(directory, "rendered.f32")
            val output = File(directory, "output.f32")
            writeFrames(source, listOf(1f to -1f, 1f to -1f))
            writeFrames(rendered, listOf(0.30f to 0.10f, 0.20f to 0.05f))

            val expected = rendered.readBytes()
            val metrics = SpatialStereoPostProcessor.process(
                sourceStereo = source,
                pointRenderedStereo = rendered,
                output = output,
                spatialBlend = 1f,
                startDistanceM = 2f,
                endDistanceM = 2f,
                inputDualMono = false,
                preserveSourceSide = false,
            )

            assertEquals("moving_object_native", metrics.mode)
            assertEquals(0f, metrics.preservation, 0f)
            assertTrue(expected.contentEquals(output.readBytes()))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeFrames(file: File, frames: List<Pair<Float, Float>>) {
        val buffer = ByteBuffer.allocate(frames.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        frames.forEach { (left, right) ->
            buffer.putFloat(left)
            buffer.putFloat(right)
        }
        file.writeBytes(buffer.array())
    }
}
