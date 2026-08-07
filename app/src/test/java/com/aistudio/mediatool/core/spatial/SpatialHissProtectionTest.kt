package com.aistudio.mediatool.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class SpatialHissProtectionTest {
    @Test
    fun steadyHighFrequencyNoiseGetsHigherRiskThanCleanTone() {
        val clean = signalFile(addHiss = false)
        val noisy = signalFile(addHiss = true)

        val cleanProfile = SpatialHissProtector.analyze(clean)
        val noisyProfile = SpatialHissProtector.analyze(noisy)

        assertTrue(noisyProfile.risk > cleanProfile.risk + 0.15f)
        assertTrue(noisyProfile.quietHighBandDbfs > cleanProfile.quietHighBandDbfs + 8f)
    }

    @Test
    fun automaticPlanStaysMildAndWetOnly() {
        val plan = SpatialHissProtector.plan(
            SpatialHissProtection.AUTO,
            SpatialHissProfile(risk = 1f),
        )

        assertTrue(plan.noiseReductionDb in 1.5f..4f)
        assertTrue(plan.wetHighShelfDb in -2.5f..-0.8f)
        assertTrue(plan.reverbHighEqScale in 0.8f..0.92f)
        assertTrue(plan.reverbHighRt60Scale in 0.65f..0.85f)
        assertTrue(plan.enabled)
    }

    @Test
    fun offModeIsTransparent() {
        val plan = SpatialHissProtector.plan(
            SpatialHissProtection.OFF,
            SpatialHissProfile(risk = 1f),
        )
        val config = SpatialAudioConfig(
            reverbEqHigh = 0.7f,
            reverbRt60High = 0.9f,
        )

        assertEquals(null, SpatialHissProtector.spatialInputFilter(plan))
        assertEquals(null, SpatialHissProtector.wetBranchFilter(plan))
        assertEquals(config.normalized(), SpatialHissProtector.protectConfig(config, plan))
    }

    private fun signalFile(addHiss: Boolean): File {
        val sampleRate = 48_000
        val seconds = 2
        val frames = sampleRate * seconds
        val bytes = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        var noiseState = 0x12345678

        repeat(frames) { index ->
            val time = index.toDouble() / sampleRate.toDouble()
            val envelope = if ((index / 12_000) % 2 == 0) 0.002 else 0.02
            val musical = envelope * sin(2.0 * PI * 700.0 * time)
            noiseState = noiseState * 1_664_525 + 1_013_904_223
            val white = (((noiseState ushr 8) and 0xFFFF) / 32767.5) - 1.0
            val hiss = if (addHiss) white * 0.004 else 0.0
            val sample = (musical + hiss).toFloat()
            bytes.putFloat(sample)
            bytes.putFloat(sample)
        }

        return File.createTempFile("spatial_hiss_", ".f32").apply {
            deleteOnExit()
            writeBytes(bytes.array())
        }
    }
}
