package com.aistudio.mediatool.core.spatial

import org.json.JSONObject

internal data class SpatialLoudnessReading(
    val integratedLufs: Double?,
    val truePeakDbtp: Double?,
    val loudnessRangeLu: Double?,
    val thresholdLufs: Double?,
) {
    fun diagnosticFields(prefix: String): Map<String, Any?> = mapOf(
        "${prefix}_integrated_lufs" to integratedLufs,
        "${prefix}_true_peak_dbtp" to truePeakDbtp,
        "${prefix}_loudness_range_lu" to loudnessRangeLu,
        "${prefix}_loudness_threshold_lufs" to thresholdLufs,
    )
}

internal fun SpatialLoudnessReading?.diagnosticFields(prefix: String): Map<String, Any?> =
    this?.diagnosticFields(prefix) ?: mapOf(
        "${prefix}_integrated_lufs" to null,
        "${prefix}_true_peak_dbtp" to null,
        "${prefix}_loudness_range_lu" to null,
        "${prefix}_loudness_threshold_lufs" to null,
    )

/** Extracts the last complete loudnorm JSON object without depending on FFmpeg log formatting. */
internal object SpatialLoudnessParser {
    fun parse(logs: String): SpatialLoudnessReading? {
        var result: SpatialLoudnessReading? = null
        for (block in jsonObjects(logs)) {
            val json = runCatching { JSONObject(block) }.getOrNull() ?: continue
            if (!json.has("input_i") || !json.has("input_tp")) continue
            result = SpatialLoudnessReading(
                integratedLufs = number(json, "input_i"),
                truePeakDbtp = number(json, "input_tp"),
                loudnessRangeLu = number(json, "input_lra"),
                thresholdLufs = number(json, "input_thresh"),
            )
        }
        return result
    }

    private fun number(json: JSONObject, name: String): Double? =
        json.optString(name).toDoubleOrNull()?.takeIf(Double::isFinite)

    private fun jsonObjects(text: String): Sequence<String> = sequence {
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        text.forEachIndexed { index, character ->
            if (start < 0) {
                if (character == '{') {
                    start = index
                    depth = 1
                    inString = false
                    escaped = false
                }
                return@forEachIndexed
            }
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
                return@forEachIndexed
            }
            when (character) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        yield(text.substring(start, index + 1))
                        start = -1
                    }
                }
            }
        }
    }
}
