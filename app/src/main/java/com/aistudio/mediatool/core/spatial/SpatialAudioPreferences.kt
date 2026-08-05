package com.aistudio.mediatool.core.spatial

import android.content.Context
import org.json.JSONObject

/** Chỉ lưu năm lựa chọn thân thiện; tham số kỹ thuật dùng bộ mặc định đã kiểm chứng. */
object SpatialAudioPreferences {
    private const val PREFS = "spatial_audio_preferences"
    private const val KEY_CONFIG = "config_v2_simple"
    private const val LEGACY_KEY = "config_v1"

    fun load(context: Context): SpatialAudioConfig {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = preferences.getString(KEY_CONFIG, null)
        if (raw == null) {
            preferences.edit().remove(LEGACY_KEY).apply()
            return SpatialAudioConfig()
        }
        return runCatching {
            val json = JSONObject(raw)
            val trajectory = enumValueOrDefault(
                json.optString("trajectory"),
                SpatialTrajectory.HORIZONTAL_CIRCLE,
            )
            SpatialAudioConfig()
                .withFriendlyTrajectory(trajectory)
                .withFriendlySpeed(json.optDouble("speed", SpatialAudioConfig().friendlySpeedPosition().toDouble()).toFloat())
                .withFriendlyDistance(json.optDouble("distance", SpatialAudioConfig().friendlyDistancePosition().toDouble()).toFloat())
                .copy(
                    spatialBlend = json.optDouble("intensity", 0.85).toFloat(),
                    reverbWet = json.optDouble("reverb", 0.12).toFloat(),
                )
                .normalized()
        }.getOrDefault(SpatialAudioConfig())
    }

    fun save(context: Context, config: SpatialAudioConfig) {
        val value = config.normalized()
        val json = JSONObject()
            .put("trajectory", value.trajectory.name)
            .put("speed", value.friendlySpeedPosition())
            .put("distance", value.friendlyDistancePosition())
            .put("intensity", value.spatialBlend)
            .put("reverb", value.reverbWet)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG, json.toString())
            .remove(LEGACY_KEY)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
