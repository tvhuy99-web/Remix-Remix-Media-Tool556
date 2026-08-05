package com.aistudio.mediatool.core.spatial

import android.content.Context
import org.json.JSONObject
import java.io.File

object SpatialAudioPreferences {
    private const val PREFS = "spatial_audio_preferences"
    private const val KEY_CONFIG = "config_v1"

    fun load(context: Context): SpatialAudioConfig {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIG, null)
            ?: return SpatialAudioConfig()
        return runCatching {
            val json = JSONObject(raw)
            SpatialAudioConfig(
                trajectory = enumValueOrDefault(json.optString("trajectory"), SpatialTrajectory.HORIZONTAL_CIRCLE),
                interpolation = enumValueOrDefault(json.optString("interpolation"), SpatialInterpolation.BILINEAR),
                motionMode = enumValueOrDefault(json.optString("motionMode"), SpatialMotionMode.LOOP),
                startAzimuthDeg = json.optDouble("startAzimuthDeg", -90.0).toFloat(),
                endAzimuthDeg = json.optDouble("endAzimuthDeg", 270.0).toFloat(),
                startElevationDeg = json.optDouble("startElevationDeg", 0.0).toFloat(),
                endElevationDeg = json.optDouble("endElevationDeg", 0.0).toFloat(),
                startDistanceM = json.optDouble("startDistanceM", 1.5).toFloat(),
                endDistanceM = json.optDouble("endDistanceM", 1.5).toFloat(),
                cycleSeconds = json.optDouble("cycleSeconds", 8.0).toFloat(),
                spatialBlend = json.optDouble("spatialBlend", 1.0).toFloat(),
                distanceMinM = json.optDouble("distanceMinM", 1.0).toFloat(),
                distanceRolloff = json.optDouble("distanceRolloff", 1.0).toFloat(),
                airAbsorption = json.optDouble("airAbsorption", 1.0).toFloat(),
                directivityWeight = json.optDouble("directivityWeight", 0.0).toFloat(),
                directivityPower = json.optDouble("directivityPower", 1.0).toFloat(),
                sourceYawDeg = json.optDouble("sourceYawDeg", 0.0).toFloat(),
                reverbWet = json.optDouble("reverbWet", 0.0).toFloat(),
                reverbRt60Low = json.optDouble("reverbRt60Low", 0.8).toFloat(),
                reverbRt60Mid = json.optDouble("reverbRt60Mid", 0.7).toFloat(),
                reverbRt60High = json.optDouble("reverbRt60High", 0.5).toFloat(),
                reverbEqLow = json.optDouble("reverbEqLow", 1.0).toFloat(),
                reverbEqMid = json.optDouble("reverbEqMid", 1.0).toFloat(),
                reverbEqHigh = json.optDouble("reverbEqHigh", 1.0).toFloat(),
                outputGainDb = json.optDouble("outputGainDb", 0.0).toFloat(),
                effectStartSeconds = json.optDouble("effectStartSeconds", 0.0).toFloat(),
                effectEndSeconds = json.optDouble("effectEndSeconds", -1.0).toFloat(),
                customSofaPath = json.optString("customSofaPath")
                    .takeIf(String::isNotBlank)
                    ?.takeIf { File(it).isFile },
                frameSize = json.optInt("frameSize", 1024),
            ).normalized()
        }.getOrDefault(SpatialAudioConfig())
    }

    fun save(context: Context, config: SpatialAudioConfig) {
        val value = config.normalized()
        val json = JSONObject()
            .put("trajectory", value.trajectory.name)
            .put("interpolation", value.interpolation.name)
            .put("motionMode", value.motionMode.name)
            .put("startAzimuthDeg", value.startAzimuthDeg)
            .put("endAzimuthDeg", value.endAzimuthDeg)
            .put("startElevationDeg", value.startElevationDeg)
            .put("endElevationDeg", value.endElevationDeg)
            .put("startDistanceM", value.startDistanceM)
            .put("endDistanceM", value.endDistanceM)
            .put("cycleSeconds", value.cycleSeconds)
            .put("spatialBlend", value.spatialBlend)
            .put("distanceMinM", value.distanceMinM)
            .put("distanceRolloff", value.distanceRolloff)
            .put("airAbsorption", value.airAbsorption)
            .put("directivityWeight", value.directivityWeight)
            .put("directivityPower", value.directivityPower)
            .put("sourceYawDeg", value.sourceYawDeg)
            .put("reverbWet", value.reverbWet)
            .put("reverbRt60Low", value.reverbRt60Low)
            .put("reverbRt60Mid", value.reverbRt60Mid)
            .put("reverbRt60High", value.reverbRt60High)
            .put("reverbEqLow", value.reverbEqLow)
            .put("reverbEqMid", value.reverbEqMid)
            .put("reverbEqHigh", value.reverbEqHigh)
            .put("outputGainDb", value.outputGainDb)
            .put("effectStartSeconds", value.effectStartSeconds)
            .put("effectEndSeconds", value.effectEndSeconds)
            .put("customSofaPath", value.customSofaPath.orEmpty())
            .put("frameSize", value.frameSize)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG, json.toString())
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
