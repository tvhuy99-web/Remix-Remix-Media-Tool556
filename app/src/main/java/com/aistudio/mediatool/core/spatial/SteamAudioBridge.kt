package com.aistudio.mediatool.core.spatial

import org.json.JSONObject
import java.io.File

object SteamAudioBridge {
    private val loadResult: Result<Unit> by lazy {
        runCatching {
            System.loadLibrary("phonon")
            System.loadLibrary("mediatool_spatial")
        }
    }

    fun render(input: File, output: File, config: SpatialAudioConfig): SpatialRenderMetrics {
        loadResult.getOrThrow()
        require(input.isFile && input.length() > 0L) { "PCM đầu vào spatial không hợp lệ" }
        output.parentFile?.mkdirs()
        output.delete()
        val value = config.normalized()
        val response = nativeRender(
            inputPath = input.absolutePath,
            outputPath = output.absolutePath,
            sofaPath = value.customSofaPath.orEmpty(),
            sampleRate = 48_000,
            frameSize = value.frameSize,
            trajectory = value.trajectory.ordinal,
            interpolation = value.interpolation.ordinal,
            motionMode = value.motionMode.ordinal,
            startAzimuthDeg = value.startAzimuthDeg,
            endAzimuthDeg = value.endAzimuthDeg,
            startElevationDeg = value.startElevationDeg,
            endElevationDeg = value.endElevationDeg,
            startDistanceM = value.startDistanceM,
            endDistanceM = value.endDistanceM,
            cycleSeconds = value.cycleSeconds,
            spatialBlend = value.spatialBlend,
            distanceMinM = value.distanceMinM,
            distanceRolloff = value.distanceRolloff,
            airAbsorption = value.airAbsorption,
            directivityWeight = value.directivityWeight,
            directivityPower = value.directivityPower,
            sourceYawDeg = value.sourceYawDeg,
            reverbWet = value.reverbWet,
            reverbRt60Low = value.reverbRt60Low,
            reverbRt60Mid = value.reverbRt60Mid,
            reverbRt60High = value.reverbRt60High,
            reverbEqLow = value.reverbEqLow,
            reverbEqMid = value.reverbEqMid,
            reverbEqHigh = value.reverbEqHigh,
            outputGainDb = value.outputGainDb,
            effectStartSeconds = value.effectStartSeconds,
            effectEndSeconds = value.effectEndSeconds,
        )
        val json = JSONObject(response)
        if (!json.optBoolean("ok", false)) {
            error(json.optString("error", "Steam Audio không thể render"))
        }
        require(output.isFile && output.length() > 0L) { "Steam Audio không tạo PCM đầu ra" }
        return SpatialRenderMetrics(
            frames = json.optLong("frames"),
            blocks = json.optLong("blocks"),
            tailFrames = json.optLong("tail_frames"),
            renderMs = json.optLong("render_ms"),
            peakBeforeGain = json.optDouble("peak_before_gain").toFloat(),
            peakAfterGain = json.optDouble("peak_after_gain").toFloat(),
            rmsDbfs = json.optDouble("rms_dbfs", -160.0).toFloat(),
            appliedGainDb = json.optDouble("applied_gain_db").toFloat(),
            nonFiniteSamples = json.optLong("nonfinite_samples"),
            clippedSamplesBeforeGain = json.optLong("clipped_samples_before_gain"),
            hrtfType = json.optString("hrtf_type", "unknown"),
            steamAudioVersion = json.optString("steam_audio_version", "unknown"),
        )
    }

    private external fun nativeRender(
        inputPath: String,
        outputPath: String,
        sofaPath: String,
        sampleRate: Int,
        frameSize: Int,
        trajectory: Int,
        interpolation: Int,
        motionMode: Int,
        startAzimuthDeg: Float,
        endAzimuthDeg: Float,
        startElevationDeg: Float,
        endElevationDeg: Float,
        startDistanceM: Float,
        endDistanceM: Float,
        cycleSeconds: Float,
        spatialBlend: Float,
        distanceMinM: Float,
        distanceRolloff: Float,
        airAbsorption: Float,
        directivityWeight: Float,
        directivityPower: Float,
        sourceYawDeg: Float,
        reverbWet: Float,
        reverbRt60Low: Float,
        reverbRt60Mid: Float,
        reverbRt60High: Float,
        reverbEqLow: Float,
        reverbEqMid: Float,
        reverbEqHigh: Float,
        outputGainDb: Float,
        effectStartSeconds: Float,
        effectEndSeconds: Float,
    ): String
}

data class SpatialRenderMetrics(
    val frames: Long,
    val blocks: Long,
    val tailFrames: Long,
    val renderMs: Long,
    val peakBeforeGain: Float,
    val peakAfterGain: Float,
    val rmsDbfs: Float,
    val appliedGainDb: Float,
    val nonFiniteSamples: Long,
    val clippedSamplesBeforeGain: Long,
    val hrtfType: String,
    val steamAudioVersion: String,
) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "frames" to frames,
        "blocks" to blocks,
        "tail_frames" to tailFrames,
        "render_ms" to renderMs,
        "peak_before_gain" to peakBeforeGain,
        "peak_after_gain" to peakAfterGain,
        "rms_dbfs" to rmsDbfs,
        "applied_gain_db" to appliedGainDb,
        "nonfinite_samples" to nonFiniteSamples,
        "clipped_samples_before_gain" to clippedSamplesBeforeGain,
        "hrtf_type" to hrtfType,
        "steam_audio_version" to steamAudioVersion,
    )
}
