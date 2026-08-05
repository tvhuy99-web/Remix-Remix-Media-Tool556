package com.aistudio.mediatool.core.spatial

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max

/** Chỉ lưu năm lựa chọn thân thiện; tham số kỹ thuật dùng bộ mặc định đã kiểm chứng. */
object SpatialAudioPreferences {
    private const val PREFS = "spatial_audio_preferences"
    private const val KEY_CONFIG = "config_v3_final"
    private const val PREVIOUS_KEY = "config_v2_simple"
    private const val LEGACY_KEY = "config_v1"

    fun load(context: Context): SpatialAudioConfig {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(KEY_CONFIG, null)?.let { raw ->
            return runCatching { parseCurrent(raw) }.getOrDefault(SpatialAudioConfig())
        }

        preferences.getString(PREVIOUS_KEY, null)?.let { raw ->
            val migrated = runCatching { parsePrevious(raw) }.getOrDefault(SpatialAudioConfig())
            save(context, migrated)
            return migrated
        }

        preferences.edit()
            .remove(PREVIOUS_KEY)
            .remove(LEGACY_KEY)
            .apply()
        return SpatialAudioConfig()
    }

    fun save(context: Context, config: SpatialAudioConfig) {
        val value = config.normalized()
        val json = JSONObject()
            .put("trajectory", value.trajectory.name)
            .put("cycle_seconds", value.cycleSeconds)
            .put("distance_m", max(value.startDistanceM, value.endDistanceM))
            .put("intensity", value.spatialBlend)
            .put("reverb", value.reverbWet)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG, json.toString())
            .remove(PREVIOUS_KEY)
            .remove(LEGACY_KEY)
            .apply()
    }

    private fun parseCurrent(raw: String): SpatialAudioConfig {
        val json = JSONObject(raw)
        val base = SpatialAudioConfig().withFriendlyTrajectory(
            enumValueOrDefault(
                json.optString("trajectory"),
                SpatialTrajectory.HORIZONTAL_CIRCLE,
            ),
        )
        val cycleSeconds = json.optDouble("cycle_seconds", base.cycleSeconds.toDouble())
            .toFloat()
            .coerceIn(
                SpatialAudioConfig.FRIENDLY_SPEED_MIN_SECONDS,
                SpatialAudioConfig.FRIENDLY_SPEED_MAX_SECONDS,
            )
        val distanceM = json.optDouble(
            "distance_m",
            max(base.startDistanceM, base.endDistanceM).toDouble(),
        ).toFloat()

        return base
            .copy(cycleSeconds = cycleSeconds)
            .withFriendlyDistance(distancePosition(distanceM))
            .copy(
                spatialBlend = json.optDouble("intensity", 0.85).toFloat(),
                reverbWet = json.optDouble("reverb", 0.12).toFloat(),
            )
            .normalized()
    }

    private fun parsePrevious(raw: String): SpatialAudioConfig {
        val json = JSONObject(raw)
        val trajectory = enumValueOrDefault(
            json.optString("trajectory"),
            SpatialTrajectory.HORIZONTAL_CIRCLE,
        )
        val oldSpeedPosition = json.optDouble("speed", (18.0 - 8.0) / 15.0)
            .toFloat()
            .coerceIn(0f, 1f)
        val oldDistancePosition = json.optDouble("distance", (1.2 - 0.8) / 3.2)
            .toFloat()
            .coerceIn(0f, 1f)
        val oldCycleSeconds = 18f - 15f * oldSpeedPosition
        val oldDistanceM = 0.8f + 3.2f * oldDistancePosition

        return SpatialAudioConfig()
            .withFriendlyTrajectory(trajectory)
            .copy(cycleSeconds = oldCycleSeconds)
            .withFriendlyDistance(distancePosition(oldDistanceM))
            .copy(
                spatialBlend = json.optDouble("intensity", 0.85).toFloat(),
                reverbWet = json.optDouble("reverb", 0.12).toFloat(),
            )
            .normalized()
    }

    private fun distancePosition(distanceM: Float): Float {
        val minimum = SpatialAudioConfig.FRIENDLY_DISTANCE_MIN_M
        val maximum = SpatialAudioConfig.FRIENDLY_DISTANCE_MAX_M
        val distance = distanceM.coerceIn(minimum, maximum)
        return (
            ln((distance / minimum).toDouble()) /
                ln((maximum / minimum).toDouble())
            ).toFloat().coerceIn(0f, 1f)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback
}
