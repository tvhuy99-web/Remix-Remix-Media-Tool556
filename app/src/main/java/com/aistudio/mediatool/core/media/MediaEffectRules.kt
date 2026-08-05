package com.aistudio.mediatool.core.media

import java.util.Locale
import kotlin.math.roundToInt

enum class MediaAudioEffect {
    DENOISE,
    NOISE_GATE,
    PAN,
    COMPRESSOR,
    EQUALIZER,
}

object MediaEffectRules {
    private const val BASE_SAMPLE_RATE = 44_100

    fun supportsTimeline(effect: MediaAudioEffect): Boolean = when (effect) {
        MediaAudioEffect.DENOISE,
        MediaAudioEffect.NOISE_GATE,
        MediaAudioEffect.EQUALIZER -> true

        MediaAudioEffect.PAN,
        MediaAudioEffect.COMPRESSOR -> false
    }

    fun supportsSilenceRemoval(isVideoMode: Boolean, modeIndex: Int): Boolean =
        !(isVideoMode && modeIndex == 0)

    fun denoiseReductionDb(value: Float): Float =
        value.takeIf { it.isFinite() }?.coerceIn(0.01f, 97f) ?: 12f

    fun denoiseFilter(reductionDb: Float, timelineExpression: String = ""): String =
        "afftdn=nr=${format(denoiseReductionDb(reductionDb).toDouble(), 2)}$timelineExpression"

    fun reverbFilter(roomSize: Float, damping: Float, wet: Float): String? {
        if (!wet.isFinite() || wet <= 0.0001f) return null
        val safeRoom = roomSize.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f) ?: 0.5f
        val safeDamping = damping.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        val safeWet = wet.coerceIn(0.0001f, 0.8f)
        val firstDelay = safeRoom * 100f
        val secondDelay = safeRoom * 150f
        val absorption = (1f - safeDamping).coerceIn(0.05f, 1f)
        val firstDecay = (safeWet * absorption).coerceIn(0.001f, 0.9f)
        val secondDecay = (firstDecay * 0.55f).coerceIn(0.001f, 0.9f)
        return "aecho=0.8:0.8:${format(firstDelay.toDouble(), 2)}|${format(secondDelay.toDouble(), 2)}:" +
            "${format(firstDecay.toDouble(), 4)}|${format(secondDecay.toDouble(), 4)}"
    }

    fun speedPitchFilters(speed: Float, pitch: Float, isVideoMode: Boolean): List<String> {
        val safePitch = pitch.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f
        val requestedSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.5f, 2f) ?: 1f
        val safeSpeed = if (isVideoMode) 1f else requestedSpeed
        val shiftedSampleRate = (BASE_SAMPLE_RATE * safePitch).roundToInt().coerceAtLeast(8_000)
        return buildList {
            add("aresample=$BASE_SAMPLE_RATE")
            add("asetrate=$shiftedSampleRate")
            add("aresample=$BASE_SAMPLE_RATE")
            addAll(atempoFilters(safeSpeed / safePitch))
        }
    }

    fun appendFinalLoudnessFilters(
        filters: MutableList<String>,
        enabled: Boolean,
        targetPeakPercent: Float,
    ) {
        if (!enabled) return
        val truePeakDb = AudioMath.truePeakDbFromPercent(targetPeakPercent)
        filters += "loudnorm=I=-16:LRA=11:TP=${format(truePeakDb, 3)}"
        filters += "aresample=48000"
    }

    internal fun atempoFilters(value: Float): List<String> {
        var remaining = value.takeIf { it.isFinite() }?.toDouble()?.coerceIn(0.25, 4.0) ?: 1.0
        val filters = mutableListOf<String>()
        while (remaining > 2.0) {
            filters += "atempo=2.0"
            remaining /= 2.0
        }
        while (remaining < 0.5) {
            filters += "atempo=0.5"
            remaining /= 0.5
        }
        if (kotlin.math.abs(remaining - 1.0) > 0.0001) {
            filters += "atempo=${format(remaining, 5)}"
        }
        return filters
    }

    private fun format(value: Double, decimals: Int): String =
        String.format(Locale.US, "%.${decimals}f", value)
}
