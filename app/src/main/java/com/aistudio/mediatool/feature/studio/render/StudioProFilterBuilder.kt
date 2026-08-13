package com.aistudio.mediatool.feature.studio.render

import com.aistudio.mediatool.feature.studio.domain.StudioVocalFxSettings
import java.util.Locale
import kotlin.math.pow

/** Deterministic FFmpeg filter chain shared by Studio Pro derived renders. */
object StudioProFilterBuilder {
    fun build(settings: StudioVocalFxSettings): String {
        if (!settings.enabled) return "anull"
        val fx = settings.normalized()
        return buildList {
            add("highpass=f=${number(fx.highPassHz)}")
            add("equalizer=f=180:t=q:w=0.85:g=${number(fx.lowGainDb)}")
            add("equalizer=f=2600:t=q:w=1.0:g=${number(fx.midGainDb)}")
            add("equalizer=f=9000:t=q:w=0.8:g=${number(fx.highGainDb)}")
            if (fx.compressorEnabled) {
                val threshold = 10.0.pow(fx.compressorThresholdDb / 20.0).coerceIn(0.000_976, 1.0)
                val makeup = 10.0.pow(fx.compressorMakeupDb / 20.0).coerceIn(1.0, 7.9)
                add(
                    "acompressor=threshold=${decimal(threshold)}:ratio=${number(fx.compressorRatio)}:" +
                        "attack=${number(fx.compressorAttackMs)}:release=${number(fx.compressorReleaseMs)}:" +
                        "makeup=${decimal(makeup)}",
                )
            }
            if (fx.reverbWet > 0.001f && fx.reverbDecay > 0.001f) {
                val wet = fx.reverbWet.coerceIn(0f, 0.65f)
                val decay = (fx.reverbDecay * (0.55f + wet)).coerceIn(0.01f, 0.9f)
                val outputGain = (1f - wet * 0.18f).coerceIn(0.75f, 1f)
                add(
                    "aecho=0.86:${number(outputGain)}:${number(fx.reverbDelayMs)}:${number(decay)}",
                )
            }
            add("alimiter=limit=0.98:attack=5:release=50:level=0:latency=1")
        }.joinToString(",")
    }

    fun polishPreset(): StudioVocalFxSettings = StudioVocalFxSettings(
        enabled = true,
        highPassHz = 85f,
        lowGainDb = -1f,
        midGainDb = 1.8f,
        highGainDb = 0.8f,
        compressorEnabled = true,
        compressorThresholdDb = -19f,
        compressorRatio = 3.2f,
        compressorAttackMs = 8f,
        compressorReleaseMs = 125f,
        compressorMakeupDb = 1.2f,
        reverbWet = 0.04f,
        reverbDelayMs = 48f,
        reverbDecay = 0.12f,
    )

    fun naturalPreset(): StudioVocalFxSettings = StudioVocalFxSettings(
        highPassHz = 75f,
        lowGainDb = 0f,
        midGainDb = 0.8f,
        highGainDb = 0.3f,
        compressorThresholdDb = -16f,
        compressorRatio = 2.2f,
        compressorAttackMs = 14f,
        compressorReleaseMs = 150f,
        compressorMakeupDb = 0.5f,
        reverbWet = 0.06f,
        reverbDelayMs = 58f,
        reverbDecay = 0.16f,
    )

    fun rapPreset(): StudioVocalFxSettings = StudioVocalFxSettings(
        highPassHz = 95f,
        lowGainDb = -1.5f,
        midGainDb = 2.8f,
        highGainDb = 1.2f,
        compressorThresholdDb = -22f,
        compressorRatio = 4.5f,
        compressorAttackMs = 5f,
        compressorReleaseMs = 90f,
        compressorMakeupDb = 2f,
        reverbWet = 0.035f,
        reverbDelayMs = 42f,
        reverbDecay = 0.10f,
    )

    fun brightPreset(): StudioVocalFxSettings = StudioVocalFxSettings(
        highPassHz = 90f,
        lowGainDb = -1f,
        midGainDb = 1.5f,
        highGainDb = 3f,
        compressorThresholdDb = -18f,
        compressorRatio = 2.8f,
        compressorAttackMs = 9f,
        compressorReleaseMs = 130f,
        compressorMakeupDb = 1f,
        reverbWet = 0.09f,
        reverbDelayMs = 62f,
        reverbDecay = 0.20f,
    )

    fun warmPreset(): StudioVocalFxSettings = StudioVocalFxSettings(
        highPassHz = 65f,
        lowGainDb = 2f,
        midGainDb = 0.5f,
        highGainDb = -1.2f,
        compressorThresholdDb = -17f,
        compressorRatio = 2.5f,
        compressorAttackMs = 16f,
        compressorReleaseMs = 180f,
        compressorMakeupDb = 0.8f,
        reverbWet = 0.12f,
        reverbDelayMs = 72f,
        reverbDecay = 0.24f,
    )

    private fun StudioVocalFxSettings.normalized() = copy(
        highPassHz = highPassHz.coerceIn(20f, 300f),
        lowGainDb = lowGainDb.coerceIn(-12f, 12f),
        midGainDb = midGainDb.coerceIn(-12f, 12f),
        highGainDb = highGainDb.coerceIn(-12f, 12f),
        compressorThresholdDb = compressorThresholdDb.coerceIn(-48f, -2f),
        compressorRatio = compressorRatio.coerceIn(1f, 20f),
        compressorAttackMs = compressorAttackMs.coerceIn(1f, 200f),
        compressorReleaseMs = compressorReleaseMs.coerceIn(20f, 2_000f),
        compressorMakeupDb = compressorMakeupDb.coerceIn(0f, 18f),
        reverbWet = reverbWet.coerceIn(0f, 0.65f),
        reverbDelayMs = reverbDelayMs.coerceIn(20f, 250f),
        reverbDecay = reverbDecay.coerceIn(0f, 0.9f),
    )

    private fun number(value: Float): String = String.format(Locale.US, "%.3f", value)
    private fun decimal(value: Double): String = String.format(Locale.US, "%.6f", value)
}
