package com.aistudio.mediatool.core.spatial

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/** Lưu các lựa chọn thân thiện; tham số kỹ thuật được suy ra từ preset phòng. */
object SpatialAudioPreferences {
    private const val PREFS = "spatial_audio_preferences"
    private const val KEY_CONFIG = "config_v4_room_aware"
    private const val PREVIOUS_FINAL_KEY = "config_v3_final"
    private const val PREVIOUS_SIMPLE_KEY = "config_v2_simple"
    private const val LEGACY_KEY = "config_v1"
    private const val REFLECTION_CURVE_EXPONENT = 1.6f

    fun load(context: Context): SpatialAudioConfig {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        preferences.getString(KEY_CONFIG, null)?.let { raw ->
            return runCatching { parseCurrent(raw) }.getOrDefault(defaultConfig())
        }

        preferences.getString(PREVIOUS_FINAL_KEY, null)?.let { raw ->
            val migrated = runCatching { parsePreviousFinal(raw) }.getOrDefault(defaultConfig())
            save(context, migrated)
            return migrated
        }

        preferences.getString(PREVIOUS_SIMPLE_KEY, null)?.let { raw ->
            val migrated = runCatching { parsePreviousSimple(raw) }.getOrDefault(defaultConfig())
            save(context, migrated)
            return migrated
        }

        preferences.edit()
            .remove(PREVIOUS_FINAL_KEY)
            .remove(PREVIOUS_SIMPLE_KEY)
            .remove(LEGACY_KEY)
            .apply()
        return defaultConfig()
    }

    fun save(context: Context, config: SpatialAudioConfig) {
        val value = config.normalized()
        val json = JSONObject()
            .put("trajectory", value.trajectory.name)
            .put("room_preset", value.roomPreset.name)
            .put("cycle_seconds", value.cycleSeconds)
            .put("distance_m", max(value.startDistanceM, value.endDistanceM))
            .put("intensity", value.spatialBlend)
            .put("reflection", value.friendlyReflectionPosition())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG, json.toString())
            .remove(PREVIOUS_FINAL_KEY)
            .remove(PREVIOUS_SIMPLE_KEY)
            .remove(LEGACY_KEY)
            .apply()
    }

    private fun parseCurrent(raw: String): SpatialAudioConfig {
        val json = JSONObject(raw)
        val roomPreset = enumValueOrDefault(
            json.optString("room_preset"),
            SpatialRoomPreset.LISTENING_ROOM,
        )
        val base = SpatialAudioConfig()
            .withRoomPreset(roomPreset)
            .withFriendlyTrajectory(
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
            .copy(
                cycleSeconds = cycleSeconds,
                spatialBlend = json.optDouble("intensity", 0.85).toFloat(),
            )
            .withFriendlyDistance(distancePosition(distanceM))
            .withFriendlyReflection(
                json.optDouble("reflection", base.friendlyReflectionPosition().toDouble())
                    .toFloat(),
            )
            .normalized()
    }

    /** v3 stored technical wet directly. Convert it back to friendly intent before applying the cap. */
    private fun parsePreviousFinal(raw: String): SpatialAudioConfig {
        val json = JSONObject(raw)
        val base = defaultConfig().withFriendlyTrajectory(
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
        val legacyWet = json.optDouble("reverb", 0.12).toFloat()

        return base
            .copy(
                cycleSeconds = cycleSeconds,
                spatialBlend = json.optDouble("intensity", 0.85).toFloat(),
            )
            .withFriendlyDistance(distancePosition(distanceM))
            .withFriendlyReflection(legacyWetToReflectionPosition(legacyWet))
            .normalized()
    }

    private fun parsePreviousSimple(raw: String): SpatialAudioConfig {
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
        val legacyWet = json.optDouble("reverb", 0.12).toFloat()

        return defaultConfig()
            .withFriendlyTrajectory(trajectory)
            .copy(
                cycleSeconds = oldCycleSeconds,
                spatialBlend = json.optDouble("intensity", 0.85).toFloat(),
            )
            .withFriendlyDistance(distancePosition(oldDistanceM))
            .withFriendlyReflection(legacyWetToReflectionPosition(legacyWet))
            .normalized()
    }

    private fun defaultConfig(): SpatialAudioConfig =
        SpatialAudioConfig().withRoomPreset(SpatialRoomPreset.LISTENING_ROOM)

    private fun legacyWetToReflectionPosition(legacyWet: Float): Float {
        val maxWet = SpatialRoomPreset.LISTENING_ROOM.acoustics.maxReflectionWet
        val curved = (legacyWet / maxWet).coerceIn(0f, 1f)
        return curved.pow(1f / REFLECTION_CURVE_EXPONENT)
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
