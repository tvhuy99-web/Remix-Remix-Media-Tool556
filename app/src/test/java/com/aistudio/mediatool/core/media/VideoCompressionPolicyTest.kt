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
    fun `70 percent means about 70 percent total bitrate`() {
        val sourceTotal = 7_000_000
        val targetVideo = VideoCompressionPolicy.targetVideoBitrate(
            sourceBitrate = sourceTotal,
            sourceHeight = 1080,
            outputHeight = null,
            qualityPercent = 70,
        )
        val targetTotal = targetVideo + VideoCompressionPolicy.TARGET_AUDIO_BITRATE
        assertEquals(4_900_000, targetTotal)
    }

    @Test
    fun `100 percent targets original total bitrate`() {
        val sourceTotal = 10_000_000
        val targetVideo = VideoCompressionPolicy.targetVideoBitrate(
            sourceBitrate = sourceTotal,
            sourceHeight = 1080,
            outputHeight = null,
            qualityPercent = 100,
        )
        assertEquals(sourceTotal, targetVideo + VideoCompressionPolicy.TARGET_AUDIO_BITRATE)
    }

    @Test
    fun `lower target size produces lower bitrate`() {
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
    fun `target total bitrate helper follows requested ratio`() {
        assertEquals(3_500_000, VideoCompressionPolicy.targetTotalBitrate(5_000_000, 70))
    }
}
