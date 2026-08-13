package com.aistudio.mediatool.feature.studio.audio

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private const val LATENCY_PROFILE_SCHEMA = 1

data class StudioLatencyRoute(
    val inputFingerprint: String,
    val outputFingerprint: String,
    val inputMode: StudioInputMode,
    val sampleRate: Int,
) {
    val key: String = buildString {
        append(Build.MANUFACTURER.lowercase(Locale.ROOT))
        append('/')
        append(Build.MODEL.lowercase(Locale.ROOT))
        append('|')
        append(inputFingerprint)
        append('|')
        append(outputFingerprint)
        append('|')
        append(inputMode.name)
        append('|')
        append(sampleRate)
    }
}

data class StudioLatencyProfile(
    val key: String,
    val inputFingerprint: String,
    val outputFingerprint: String,
    val inputMode: StudioInputMode,
    val sampleRate: Int,
    val automaticFrames: Long,
    val manualFrames: Long = 0L,
    val confidence: Float = 0f,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val totalFrames: Long
        get() = (automaticFrames + manualFrames).coerceAtLeast(0L)

    val milliseconds: Double
        get() = if (sampleRate > 0) totalFrames * 1_000.0 / sampleRate else 0.0

    fun compensationFrames(projectSampleRate: Int): Long {
        if (sampleRate <= 0 || projectSampleRate <= 0) return 0L
        return (totalFrames.toDouble() * projectSampleRate.toDouble() / sampleRate.toDouble())
            .toLong()
            .coerceAtLeast(0L)
    }
}

class StudioLatencyStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "studio").apply { mkdirs() }
    private val file = File(root, "latency_profiles.json")

    @Synchronized
    fun all(): List<StudioLatencyProfile> = readProfiles()

    @Synchronized
    fun find(route: StudioLatencyRoute): StudioLatencyProfile? =
        readProfiles().firstOrNull { it.key == route.key }

    @Synchronized
    fun saveAutomatic(
        route: StudioLatencyRoute,
        measuredFrames: Long,
        confidence: Float,
    ): StudioLatencyProfile {
        val profiles = readProfiles().toMutableList()
        val previous = profiles.firstOrNull { it.key == route.key }
        val profile = StudioLatencyProfile(
            key = route.key,
            inputFingerprint = route.inputFingerprint,
            outputFingerprint = route.outputFingerprint,
            inputMode = route.inputMode,
            sampleRate = route.sampleRate,
            automaticFrames = measuredFrames.coerceAtLeast(0L),
            manualFrames = previous?.manualFrames ?: 0L,
            confidence = confidence.coerceIn(0f, 1f),
            updatedAt = System.currentTimeMillis(),
        )
        profiles.removeAll { it.key == route.key }
        profiles += profile
        writeProfiles(profiles)
        return profile
    }

    @Synchronized
    fun adjustManual(route: StudioLatencyRoute, deltaMilliseconds: Double): StudioLatencyProfile {
        val profiles = readProfiles().toMutableList()
        val previous = profiles.firstOrNull { it.key == route.key }
        val deltaFrames = (deltaMilliseconds * route.sampleRate.toDouble() / 1_000.0).toLong()
        val profile = StudioLatencyProfile(
            key = route.key,
            inputFingerprint = route.inputFingerprint,
            outputFingerprint = route.outputFingerprint,
            inputMode = route.inputMode,
            sampleRate = route.sampleRate,
            automaticFrames = previous?.automaticFrames ?: 0L,
            manualFrames = ((previous?.manualFrames ?: 0L) + deltaFrames),
            confidence = previous?.confidence ?: 0f,
            updatedAt = System.currentTimeMillis(),
        )
        profiles.removeAll { it.key == route.key }
        profiles += profile
        writeProfiles(profiles)
        return profile
    }

    @Synchronized
    fun reset(route: StudioLatencyRoute) {
        val profiles = readProfiles().filterNot { it.key == route.key }
        writeProfiles(profiles)
    }

    private fun readProfiles(): List<StudioLatencyProfile> {
        if (!file.isFile || file.length() <= 0L) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("schema", LATENCY_PROFILE_SCHEMA) != LATENCY_PROFILE_SCHEMA) return@runCatching emptyList()
            val items = root.optJSONArray("profiles") ?: JSONArray()
            buildList {
                for (index in 0 until items.length()) {
                    val json = items.optJSONObject(index) ?: continue
                    val rate = json.optInt("sampleRate", 0)
                    val key = json.optString("key")
                    if (rate <= 0 || key.isBlank()) continue
                    add(
                        StudioLatencyProfile(
                            key = key,
                            inputFingerprint = json.optString("inputFingerprint", "default-input"),
                            outputFingerprint = json.optString("outputFingerprint", "default-output"),
                            inputMode = runCatching { StudioInputMode.valueOf(json.optString("inputMode")) }
                                .getOrDefault(StudioInputMode.AUTO),
                            sampleRate = rate,
                            automaticFrames = json.optLong("automaticFrames", 0L).coerceAtLeast(0L),
                            manualFrames = json.optLong("manualFrames", 0L),
                            confidence = json.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                            updatedAt = json.optLong("updatedAt", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeProfiles(profiles: List<StudioLatencyProfile>) {
        val body = JSONObject().apply {
            put("schema", LATENCY_PROFILE_SCHEMA)
            put("profiles", JSONArray().apply {
                profiles.sortedByDescending { it.updatedAt }.take(64).forEach { profile ->
                    put(JSONObject().apply {
                        put("key", profile.key)
                        put("inputFingerprint", profile.inputFingerprint)
                        put("outputFingerprint", profile.outputFingerprint)
                        put("inputMode", profile.inputMode.name)
                        put("sampleRate", profile.sampleRate)
                        put("automaticFrames", profile.automaticFrames)
                        put("manualFrames", profile.manualFrames)
                        put("confidence", profile.confidence.toDouble())
                        put("updatedAt", profile.updatedAt)
                    })
                }
            })
        }.toString(2).toByteArray(Charsets.UTF_8)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(body)
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }
}
