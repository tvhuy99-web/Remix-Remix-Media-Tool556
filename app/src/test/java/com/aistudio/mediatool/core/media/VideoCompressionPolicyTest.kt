package com.aistudio.mediatool.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCompressionPolicyTest {
    @Test
    fun `720p request downscales 1080p source`() {
        assertEquals(720, VideoCompressionPolicy.effectiveHeight(1080, 1))
    }

    @Test
    fun `720p request never upscales 480p source`() {
        assertNull(VideoCompressionPolicy.effectiveHeight(480, 1))
    }

    @Test
    fun `same-resolution target stays below source bitrate`() {
        val source = 10_000_000
        val target = VideoCompressionPolicy.targetVideoBitrate(
            sourceBitrate = source,
            sourceHeight = 1080,
            outputHeight = null,
            qualityPercent = 100,
        )
        assertTrue(target <= (source * 0.95).toInt())
    }

    @Test
    fun `lower quality produces lower bitrate`() {
        val low = VideoCompressionPolicy.targetVideoBitrate(
            sourceBitrate = 20_000_000,
            sourceHeight = 1080,
            outputHeight = null,
            qualityPercent = 30,
        )
        val high = VideoCompressionPolicy.targetVideoBitrate(
            sourceBitrate = 20_000_000,
            sourceHeight = 1080,
            outputHeight = null,
            qualityPercent = 90,
        )
        assertTrue(low < high)
    }

    @Test
    fun `downscale target is substantially below high bitrate source`() {
        val source = 24_000_000
        val target = VideoCompressionPolicy.targetVideoBitrate(
            sourceBitrate = source,
            sourceHeight = 2160,
            outputHeight = 720,
            qualityPercent = 80,
        )
        assertTrue(target < source / 2)
    }
}
