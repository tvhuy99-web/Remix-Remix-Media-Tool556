package com.aistudio.mediatool.core.ml

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StemPcmToolkitTest {
    @Test
    fun residualReconstructsOriginalMix() {
        val mix = pcmFile(floatArrayOf(0.8f, -0.4f, 0.2f, 0.6f))
        val vocals = pcmFile(floatArrayOf(0.3f, -0.1f, -0.2f, 0.1f))
        val music = File.createTempFile("stem-music", ".f32")
        try {
            StemPcmToolkit.createResidual(mix, vocals, music, channels = 2)
            val report = StemPcmToolkit.analyze(
                referenceMix = mix,
                stemFiles = linkedMapOf("vocals" to vocals, "music" to music),
                reconstructionStemNames = setOf("vocals", "music"),
                channels = 2,
                seamFrames = emptyList(),
            )
            assertTrue(report.reconstruction.rmsErrorDbfs <= -130.0)
            assertTrue(report.reconstruction.correlation >= 0.999999)
        } finally {
            mix.delete()
            vocals.delete()
            music.delete()
        }
    }

    @Test
    fun sharedGainOnlyActivatesAboveFullScale() {
        assertEquals(0.0, StemPcmToolkit.sharedGainDbForPeak(1.0), 0.0)
        val gain = StemPcmToolkit.sharedGainDbForPeak(2.0)
        assertTrue(gain < -6.9 && gain > -7.1)
    }

    @Test
    fun seamMetricsDetectDiscontinuity() {
        val samples = FloatArray(4_096 * 2)
        for (frame in 2_048 until 4_096) {
            samples[frame * 2] = 0.8f
            samples[frame * 2 + 1] = 0.8f
        }
        val mix = pcmFile(samples)
        val silence = pcmFile(FloatArray(samples.size))
        try {
            val report = StemPcmToolkit.analyze(
                referenceMix = mix,
                stemFiles = linkedMapOf("main" to mix, "zero" to silence),
                reconstructionStemNames = setOf("main", "zero"),
                channels = 2,
                seamFrames = listOf(2_048L),
            )
            assertTrue(checkNotNull(report.stems["main"]).seam.maximumSampleJump > 0.7)
        } finally {
            mix.delete()
            silence.delete()
        }
    }

    private fun pcmFile(samples: FloatArray): File = File.createTempFile("stem-pcm", ".f32").also { file ->
        DataOutputStream(FileOutputStream(file)).use { output ->
            samples.forEach { value -> output.writeFloatLittleEndian(value) }
        }
    }

    private fun DataOutputStream.writeFloatLittleEndian(value: Float) {
        val bits = value.toRawBits()
        writeByte(bits and 0xff)
        writeByte((bits ushr 8) and 0xff)
        writeByte((bits ushr 16) and 0xff)
        writeByte((bits ushr 24) and 0xff)
    }
}
