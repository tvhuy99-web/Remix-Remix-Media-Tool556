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
    fun automaticPlanUsesOnlyPhaseSafeWetDamping() {
        val plan = SpatialHissProtector.plan(
            SpatialHissProtection.AUTO,
            SpatialHissProfile(risk = 1f),
        )

        assertEquals(0f, plan.noiseReductionDb, 0f)
        assertEquals(null, SpatialHissProtector.spatialInputFilter(plan))
        assertTrue(plan.wetHighShelfDb in -0.8f..-0.2f)
        assertTrue(plan.contralateralHighDampingDb in 1.5f..3.5f)
        assertTrue(plan.contralateralHighDampingDb > -plan.wetHighShelfDb)
        assertTrue(plan.reverbHighEqScale in 0.8f..0.92f)
        assertTrue(plan.reverbHighRt60Scale in 0.65f..0.85f)
        assertTrue(plan.enabled)
    }

    @Test
    fun strongModeCompensatesFftLatency() {
        val plan = SpatialHissProtector.plan(
            SpatialHissProtection.STRONG,
            SpatialHissProfile(risk = 1f),
        )
        val filter = SpatialHissProtector.spatialInputFilter(plan).orEmpty()

        assertTrue(plan.usesFftDenoise)
        assertTrue(filter.contains("afftdn="))
        assertTrue(filter.contains("atrim=start_sample=1200"))
        assertTrue(filter.contains("apad=pad_len=1200"))
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

    @Test
    fun nativeRendererTargetsOnlyTheFarEarHighBand() {
        val source = File("src/main/cpp/spatial_audio_jni.cpp").readText()
        assertTrue(source.contains("farFactor = channel == 0"))
        assertTrue(source.contains("dampContralateralHigh(spatial, channel, pose.direction.x)"))
        assertTrue(source.contains("0.040f * sampleRate"))
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
